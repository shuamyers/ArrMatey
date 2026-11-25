package com.dnfapps.arrmatey.arr.api.client

import com.dnfapps.arrmatey.database.EncryptedString
import com.dnfapps.arrmatey.instances.model.Instance
import com.dnfapps.arrmatey.instances.model.InstanceType
import com.dnfapps.arrmatey.instances.model.SeerrAuthType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeerrSessionCookieTest {
    private val base = "https://seerr.example.test/api/v1"
    private val token = "s%3Aabc.def%2Bghi"
    private val quietLogger = object : Logger {
        override fun log(message: String) = Unit
    }

    private fun seerr(authType: SeerrAuthType, sessionToken: String = token) = Instance(
        type = InstanceType.Seerr,
        label = "Seerr",
        url = "https://seerr.example.test",
        apiKey = EncryptedString(if (authType == SeerrAuthType.ApiKey) "test-key" else ""),
        seerrAuthType = authType,
        sessionToken = EncryptedString(sessionToken),
    )

    /** Mock Seerr: records each request's Cookie header and optionally sets cookies on responses. */
    private fun client(instance: Instance, setCookie: (Int) -> String? = { null }): Pair<HttpClient, MutableList<String?>> {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.Cookie]
            val cookie = setCookie(seen.size)
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = if (cookie == null) {
                    headersOf(HttpHeaders.ContentType, "application/json")
                } else {
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.SetCookie to listOf(cookie),
                    )
                },
            )
        }
        val client = HttpClient(engine) {
            configureInstanceClient(instance, Json { ignoreUnknownKeys = true }, quietLogger)
        }
        return client to seen
    }

    private suspend fun HttpClient.call(path: String): HttpResponse = get("$base/$path")

    @Test
    fun freshClientSendsSavedSession() = runTest {
        // Same as a cold app start: nothing in the cookie store yet.
        val (client, seen) = client(seerr(SeerrAuthType.Jellyfin))
        client.call("request?take=50")
        client.call("request/count")
        assertEquals<List<String?>>(listOf("connect.sid=$token", "connect.sid=$token"), seen)
    }

    @Test
    fun sessionSurvivesOtherCookiesFromTheServer() = runTest {
        // Cloudflare adds its own cookie; the session must still be sent alongside it.
        val (client, seen) = client(seerr(SeerrAuthType.Jellyfin)) { n -> if (n == 1) "__cf_bm=cf1; Path=/" else null }
        client.call("discover/movies")
        client.call("request?take=50")
        val second = seen[1].orEmpty()
        assertTrue("connect.sid=$token" in second, "session missing: $second")
        assertTrue("__cf_bm=cf1" in second, "other cookie missing: $second")
    }

    @Test
    fun newerSessionFromServerWins() = runTest {
        val (client, seen) = client(seerr(SeerrAuthType.Jellyfin)) { n -> if (n == 1) "connect.sid=s%3Anew; Path=/" else null }
        client.call("auth/me")
        client.call("request?take=50")
        assertEquals("connect.sid=s%3Anew", seen[1])
    }

    /**
     * Replays the 2026-11-25 failure step by step. HttpCookies saves a hand-written Cookie header into the
     * store under the request's own path. When `request/count` is captured while `request?take=50` is in
     * flight, it replaces the earlier `connect.sid`, and the list call is then sent with no cookie (401).
     */
    @Test
    fun capturedHeaderCookiesHideTheSessionFromSiblingPaths() = runTest {
        val list = Url("$base/request?take=50")
        val count = Url("$base/request/count")
        val captured = Cookie(SEERR_SESSION_COOKIE, token, encoding = CookieEncoding.RAW)

        val ktorDefault = AcceptAllCookiesStorage()
        ktorDefault.addCookie(list, captured) // list call captured
        ktorDefault.addCookie(count, captured) // count call captured before the list call is sent
        assertTrue(ktorDefault.get(list).none { it.name == SEERR_SESSION_COOKIE }, "Ktor behaviour changed")

        val fixed = SeerrSessionCookiesStorage(token)
        fixed.addCookie(count, Cookie(SEERR_SESSION_COOKIE, token, encoding = CookieEncoding.RAW))
        assertTrue(fixed.get(list).any { it.name == SEERR_SESSION_COOKIE && it.value == token })
    }

    @Test
    fun apiKeyInstanceSendsNoSessionCookie() = runTest {
        val (client, seen) = client(seerr(SeerrAuthType.ApiKey))
        client.call("request?take=50")
        assertNull(seen[0])
    }
}
