package com.dnfapps.arrmatey.instances.repository

import com.dnfapps.arrmatey.arr.api.client.configureInstanceClient
import com.dnfapps.arrmatey.database.EncryptedString
import com.dnfapps.arrmatey.instances.model.Instance
import com.dnfapps.arrmatey.instances.model.InstanceType
import com.dnfapps.arrmatey.instances.model.SeerrAuthType
import com.dnfapps.arrmatey.seerr.api.model.ApprovalStatus
import com.dnfapps.networking.NetworkResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Arrmada M3: approve/decline are sent once, and unclear or refused outcomes are read back from Seerr. */
class SeerrRequestDecisionTest {
    private enum class Behaviour { Apply, Conflict, LoseAnswerAfterApplying, LoseAnswerBeforeApplying, ServerError }

    /** Minimal stateful Seerr: request id → status (1 pending, 2 approved, 3 declined). */
    private class FakeSeerr(var behaviour: Behaviour = Behaviour.Apply) {
        val status = mutableMapOf(7L to 1, 8L to 1)
        val decisionPosts = mutableListOf<String>()
        var releasePost: CompletableDeferred<Unit>? = null

        suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
            val path = request.url.encodedPath.removePrefix("/api/v1/")
            val decision = Regex("request/(\\d+)/(approve|decline)").matchEntire(path)
            val single = Regex("request/(\\d+)").matchEntire(path)
            when {
                decision != null && request.method == HttpMethod.Post -> {
                    decisionPosts += path
                    releasePost?.await()
                    val id = decision.groupValues[1].toLong()
                    val wanted = if (decision.groupValues[2] == "approve") 2 else 3
                    when (behaviour) {
                        Behaviour.Apply -> if (status[id] == 1) {
                            status[id] = wanted
                            json(id)
                        } else {
                            conflict()
                        }
                        Behaviour.Conflict -> conflict()
                        Behaviour.LoseAnswerAfterApplying -> {
                            status[id] = wanted
                            throw IOException("connection reset")
                        }
                        Behaviour.LoseAnswerBeforeApplying -> throw IOException("connection reset")
                        Behaviour.ServerError -> respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    }
                }
                single != null && request.method == HttpMethod.Get -> json(single.groupValues[1].toLong())
                else -> error("Unexpected ${request.method.value} $path")
            }
        }

        private val jsonHeaders = headersOf("Content-Type", "application/json")

        private fun MockRequestHandleScope.json(id: Long) = respond(
            """{"id":$id,"status":${status[id]},"type":"movie","media":{"id":$id,"tmdbId":$id,"status":2,"mediaType":"movie"}}""",
            HttpStatusCode.OK,
            jsonHeaders,
        )

        private fun MockRequestHandleScope.conflict() = respond("""{"message":"Only pending requests can be approved or declined."}""", HttpStatusCode.Conflict, jsonHeaders)
    }

    private val instance = Instance(
        id = 1,
        label = "Seerr",
        url = "http://seerr.example.test",
        apiKey = EncryptedString("test-key"),
        type = InstanceType.Seerr,
        seerrAuthType = SeerrAuthType.ApiKey,
    )
    private val quietLogger = object : Logger {
        override fun log(message: String) = Unit
    }

    private fun repository(fake: FakeSeerr): SeerrInstanceRepository {
        // The real instance client setup, so its retry policy is part of the test.
        val client = HttpClient(MockEngine { request -> with(fake) { handle(request) } }) {
            configureInstanceClient(instance, Json { ignoreUnknownKeys = true }, quietLogger)
        }
        return SeerrInstanceRepository(instance, client)
    }

    @Test
    fun approveIsSentOnceAndApplied() = runTest {
        val fake = FakeSeerr()
        val result = repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("request/7/approve"), fake.decisionPosts)
        assertEquals(2, fake.status[7])
    }

    @Test
    fun declineIsSentOnceAndApplied() = runTest {
        val fake = FakeSeerr()
        val result = repository(fake).setRequestStatus(8, ApprovalStatus.Decline)
        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(listOf("request/8/decline"), fake.decisionPosts)
        assertEquals(3, fake.status[8])
    }

    @Test
    fun doubleTapSendsOneDecision() = runTest {
        val fake = FakeSeerr().apply { releasePost = CompletableDeferred() }
        val repo = repository(fake)
        val first = async { repo.setRequestStatus(7, ApprovalStatus.Approve) }
        testScheduler.runCurrent()
        val second = repo.setRequestStatus(7, ApprovalStatus.Approve) // second tap while the first is in flight
        fake.releasePost!!.complete(Unit)
        assertIs<NetworkResult.Success<*>>(first.await())
        assertIs<NetworkResult.Error>(second)
        assertEquals(listOf("request/7/approve"), fake.decisionPosts)
    }

    @Test
    fun lostAnswerAfterApplyingIsReadBackAsSuccess() = runTest {
        val fake = FakeSeerr(Behaviour.LoseAnswerAfterApplying)
        val result = repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertIs<NetworkResult.Success<*>>(result)
        assertEquals(1, fake.decisionPosts.size, "a decision must never be resent")
    }

    @Test
    fun lostAnswerBeforeApplyingStaysAnErrorAndIsNotResent() = runTest {
        val fake = FakeSeerr(Behaviour.LoseAnswerBeforeApplying)
        val result = repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertIs<NetworkResult.Error>(result)
        assertEquals(1, fake.decisionPosts.size, "a decision must never be resent")
        assertEquals(1, fake.status[7], "still pending, so the parent can try again")
    }

    @Test
    fun serverErrorIsNotResent() = runTest {
        val fake = FakeSeerr(Behaviour.ServerError)
        repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertEquals(1, fake.decisionPosts.size, "a decision must never be resent")
    }

    @Test
    fun conflictShowsTheRealState() = runTest {
        val fake = FakeSeerr()
        fake.status[7] = 3 // someone already declined it
        val result = repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertIs<NetworkResult.Error>(result)
        assertEquals(409, result.code)
        assertTrue(result.message.orEmpty().contains("declined"), "message: ${result.message}")
        assertEquals(3, fake.status[7])
    }

    @Test
    fun conflictWhenAlreadyInTheWantedStateIsSuccess() = runTest {
        val fake = FakeSeerr()
        fake.status[7] = 2 // already approved (e.g. by the other parent)
        val result = repository(fake).setRequestStatus(7, ApprovalStatus.Approve)
        assertIs<NetworkResult.Success<*>>(result)
    }
}
