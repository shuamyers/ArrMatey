package com.dnfapps.arrmatey.downloadclient.api

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.networking.ErrorType
import com.dnfapps.networking.NetworkResult
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.IOException

/** A setup draft and a saved connection can have different IDs and share the same login. */
internal class QBittorrentLoginProtection(
    private val preferences: DataStore<Preferences>,
    private val identify: (ByteArray) -> String = ::identifyQBittorrentCredentials,
) {
    private val originLocks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private val refused = MutableStateFlow<Map<String, Int>>(emptyMap())

    suspend fun authenticate(
        client: DownloadClient,
        endpoint: Url,
        attempt: suspend () -> NetworkResult<Unit>,
    ): NetworkResult<Unit> {
        val origin = "${endpoint.protocol.name}:${endpoint.host.trimEnd('.').lowercase()}:${endpoint.port}"
        val lock = originLocks.updateAndGet { locks ->
            if (origin in locks) locks else locks + (origin to Mutex())
        }.getValue(origin)

        return lock.withLock {
            val tag: String
            val previousCode: Int?
            try {
                tag = identify(client.credentialIdentity(endpoint, origin))
                previousCode = refused.value[tag] ?: preferences.data.first()[intPreferencesKey(tag)]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock NetworkResult.Error(message = "Arrmada couldn't check qBittorrent's saved login protection.")
            }

            if (previousCode != null) return@withLock qBittorrentLoginRefused(previousCode)

            val result = attempt()
            if (result is NetworkResult.Error && (result.code == 401 || result.code == 403)) {
                val code = checkNotNull(result.code)
                refused.update { it + (tag to code) }
                withContext(NonCancellable) {
                    try {
                        preferences.edit { it[intPreferencesKey(tag)] = code }
                    } catch (_: IOException) {
                        // Keep enforcing the refusal in this process if storage is unavailable.
                    }
                }
            }
            result
        }
    }
}

internal fun qBittorrentLoginRefused(code: Int = 401) = NetworkResult.Error(
    code = code,
    message = "qBittorrent refused the login. Update the credentials in settings.",
    errorType = ErrorType.Http,
)

private fun DownloadClient.credentialIdentity(endpoint: Url, origin: String): ByteArray {
    val proxyCredentials = headers.filter { header ->
        header.key.equals(HttpHeaders.Authorization, ignoreCase = true) ||
            header.key.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true) ||
            header.key.equals(HttpHeaders.Cookie, ignoreCase = true)
    }
    val fields = listOf(
        origin,
        endpoint.encodedPath.trimEnd('/'),
        endpoint.user.orEmpty(),
        endpoint.password.orEmpty(),
        username.value,
        password.value,
    ) + proxyCredentials.flatMap { listOf(it.key.lowercase(), it.value) }
    return Json.encodeToString(fields).encodeToByteArray()
}

internal expect fun identifyQBittorrentCredentials(input: ByteArray): String
