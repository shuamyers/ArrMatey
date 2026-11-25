package com.dnfapps.arrmatey.arr.api.client

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url

internal const val SEERR_SESSION_COOKIE = "connect.sid"

/**
 * Cookie storage that always offers the saved Seerr session.
 *
 * Ktor's HttpCookies plugin saves a hand-set `Cookie: connect.sid=…` header into the storage under the
 * request's own path, then sends only what the storage returns for that URL (no Cookie header if
 * nothing matches). When the dashboard fires `request?take=50` and `request/count` together, the second
 * capture replaces the first `connect.sid` with one scoped to `/api/v1/request/count`, so the list call
 * goes out unsigned and Seerr answers 401 (seen on the phone 2026-11-25). Offering the saved session
 * from the storage for every URL avoids this. A newer `connect.sid` set by the server wins.
 */
internal class SeerrSessionCookiesStorage(
    private val sessionToken: String,
    private val delegate: CookiesStorage = AcceptAllCookiesStorage(),
) : CookiesStorage {
    override suspend fun get(requestUrl: Url): List<Cookie> {
        val stored = delegate.get(requestUrl)
        if (sessionToken.isEmpty() || stored.any { it.name == SEERR_SESSION_COOKIE }) return stored
        // RAW: the token is kept exactly as the server sent it (already URL-encoded by Seerr).
        return stored + Cookie(SEERR_SESSION_COOKIE, sessionToken, encoding = CookieEncoding.RAW)
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = delegate.addCookie(requestUrl, cookie)

    override fun close() = delegate.close()
}
