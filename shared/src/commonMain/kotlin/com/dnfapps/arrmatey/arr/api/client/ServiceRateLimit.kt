package com.dnfapps.arrmatey.arr.api.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.dnfapps.networking.HttpRateLimitException
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class ServiceRateLimit(
    private val preferences: DataStore<Preferences>,
    private val clock: Clock = Clock.System,
) {
    // A second client may send a request before the first client's 429 is written to disk.
    private val observed = MutableStateFlow<Map<Preferences.Key<Long>, Long>>(emptyMap())

    suspend fun check(url: Url) {
        val key = url.cooldownKey()
        val stored = preferences.data.first()[key] ?: 0L
        val deadline = maxOf(stored, observed.value[key] ?: 0L)
        if (deadline > clock.now().toEpochMilliseconds()) {
            throw HttpRateLimitException(Instant.fromEpochMilliseconds(deadline))
        }
    }

    suspend fun record(
        url: Url,
        retryAfter: String?,
        serverDate: String?,
    ) {
        val now = clock.now()
        val nowMillis = now.toEpochMilliseconds()
        val until = retryDeadline(now, retryAfter, serverDate).toEpochMilliseconds()
        if (until <= nowMillis) return

        val key = url.cooldownKey()
        observed.update { current ->
            current.filterValues { it > nowMillis } + Pair(key, maxOf(until, current[key] ?: 0L))
        }
        withContext(NonCancellable) {
            try {
                preferences.edit { settings ->
                    settings[key] = maxOf(until, settings[key] ?: 0L)
                }
            } catch (_: IOException) {
                // Keep enforcing the in-process cooldown if the device cannot write it to disk.
            }
        }
    }
}

private class ServiceRateLimitConfig {
    lateinit var policy: ServiceRateLimit
}

private val ServiceRateLimitPlugin = createClientPlugin("ServiceRateLimit", ::ServiceRateLimitConfig) {
    val policy = pluginConfig.policy
    on(SendingRequest) { request, _ ->
        policy.check(request.url.build())
    }
    onResponse { response ->
        if (response.status == HttpStatusCode.TooManyRequests) {
            policy.record(response.call.request.url, response.headers[HttpHeaders.RetryAfter], response.headers[HttpHeaders.Date])
        }
    }
}

internal fun HttpClientConfig<*>.enforceServiceRateLimits(rateLimit: ServiceRateLimit?) {
    if (rateLimit == null) return
    install(ServiceRateLimitPlugin) {
        policy = rateLimit
    }
}

// Use the actual network origin so setup, saved connections and redirected requests share the
// same cooldown without persisting URLs containing credentials, query parameters or paths.
private fun Url.cooldownKey() = longPreferencesKey("${protocol.name}:${host.trimEnd('.').lowercase()}:$port")

private fun retryDeadline(
    now: Instant,
    retryAfter: String?,
    serverDate: String?,
): Instant {
    val value = retryAfter?.trim()
    val seconds = value?.toLongOrNull()?.takeIf { it >= 0 }
    if (seconds != null) return now + seconds.seconds

    val retryDate = value.parseHttpDate() ?: return now + 1.days
    val responseDate = serverDate.parseHttpDate() ?: return retryDate
    return now + (retryDate - responseDate)
}

private fun String?.parseHttpDate(): Instant? = this?.let { value ->
    runCatching { Instant.fromEpochMilliseconds(value.fromHttpToGmtDate().timestamp) }.getOrNull()
}
