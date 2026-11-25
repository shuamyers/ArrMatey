package com.dnfapps.arrmatey.arr.api.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.dnfapps.arrmatey.instances.model.InstanceType
import com.dnfapps.networking.HttpRateLimitException
import com.dnfapps.networking.NetworkResult
import com.dnfapps.networking.safeGet
import com.dnfapps.networking.safePost
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ServiceRateLimitTest {
    private val quietLogger = object : Logger {
        override fun log(message: String) = Unit
    }

    @Test
    fun setupRateLimitBlocksSavedReadsAndWritesAfterRelaunch() = runTest {
        withRateLimitFile { path ->
            val clock = TestClock()
            val origin = "https://service.test"
            var requests = 0

            withStore(path) { store ->
                val limitedEngine = MockEngine {
                    requests++
                    respond(
                        "Rate limited",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.RetryAfter, "90"),
                    )
                }
                configuredClient(limitedEngine, ServiceRateLimit(store, clock)).use { setupClient ->
                    val factory = object : HttpClientFactory(Json, quietLogger) {
                        override fun createGeneric(): HttpClient = setupClient
                    }
                    val setup = GenericClient(factory)
                    assertFalse(setup.test(origin, "fixture-key", InstanceType.Sonarr))
                    assertFalse(setup.test(origin, "another-fixture-key", InstanceType.Sonarr))
                    assertEquals(1, requests)
                }
            }

            withStore(path) { store ->
                val readyEngine = MockEngine {
                    requests++
                    respond("ready")
                }
                configuredClient(readyEngine, ServiceRateLimit(store, clock)).use { savedClient ->
                    clock.time += 90.seconds - 1.milliseconds
                    val read = savedClient.safeGet<String>("$origin:443/api/queue")
                    val write = savedClient.safePost<String>("$origin/api/pause")
                    assertEquals(429, assertIs<NetworkResult.Error>(read).code)
                    assertEquals(429, assertIs<NetworkResult.Error>(write).code)
                    assertEquals(1, requests)

                    assertIs<NetworkResult.Success<String>>(savedClient.safeGet<String>("https://other.test/api/queue"))
                    assertEquals(2, requests)

                    clock.time += 1.milliseconds
                    assertIs<NetworkResult.Success<String>>(savedClient.safeGet<String>("$origin/api/queue"))
                    assertEquals(3, requests)
                }
            }
        }
    }

    @Test
    fun serverDateCompensatesForClockSkewAndShorterResponsesCannotLiftCooldown() = runTest {
        withRateLimitFile { path ->
            withStore(path) { store ->
                val clock = TestClock()
                val policy = ServiceRateLimit(store, clock)
                val url = Url("https://service.test")
                policy.record(url, "Sun, 06 Nov 1994 08:50:37 GMT", "Sun, 06 Nov 1994 08:49:37 GMT")
                policy.record(url, "3", null)

                clock.time += 59.seconds
                assertFailsWith<HttpRateLimitException> { policy.check(url) }

                clock.time += 1.seconds
                policy.check(url)
            }
        }
    }

    @Test
    fun missingOrInvalidRetryAfterUsesOneDay() = runTest {
        withRateLimitFile { path ->
            withStore(path) { store ->
                val clock = TestClock()
                val policy = ServiceRateLimit(store, clock)
                val missing = Url("https://missing.test")
                val invalid = Url("https://invalid.test")
                policy.record(missing, null, null)
                policy.record(invalid, "later", null)

                clock.time += 1.days - 1.milliseconds
                assertFailsWith<HttpRateLimitException> { policy.check(missing) }
                assertFailsWith<HttpRateLimitException> { policy.check(invalid) }

                clock.time += 1.milliseconds
                policy.check(missing)
                policy.check(invalid)
            }
        }
    }

    @Test
    fun redirectCannotBypassDestinationCooldown() = runTest {
        withRateLimitFile { path ->
            withStore(path) { store ->
                val policy = ServiceRateLimit(store, TestClock())
                policy.record(Url("https://limited.test"), "90", null)
                val requests = mutableListOf<String>()
                val engine = MockEngine { request ->
                    requests += request.url.host
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://limited.test/api/queue"),
                    )
                }
                configuredClient(engine, policy).use { client ->
                    val result = client.safeGet<String>("https://redirect.test")
                    assertEquals(429, assertIs<NetworkResult.Error>(result).code)
                    assertEquals(listOf("redirect.test"), requests)
                }
            }
        }
    }

    private fun configuredClient(
        engine: MockEngine,
        policy: ServiceRateLimit,
    ) = HttpClient(engine) {
        configureInstanceClient(null, Json, quietLogger, policy)
    }

    private suspend fun withRateLimitFile(block: suspend (Path) -> Unit) {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "rate-limit-${Random.nextLong()}.preferences_pb"
        try {
            block(path)
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    private suspend fun withStore(
        path: Path,
        block: suspend (DataStore<Preferences>) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            block(PreferenceDataStoreFactory.createWithPath(scope = scope) { path })
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private class TestClock : Clock {
        var time = Instant.parse("2026-11-25T20:00:00Z")

        override fun now(): Instant = time
    }
}
