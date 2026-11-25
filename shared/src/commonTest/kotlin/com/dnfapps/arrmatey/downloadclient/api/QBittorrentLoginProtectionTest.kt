package com.dnfapps.arrmatey.downloadclient.api

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dnfapps.arrmatey.database.EncryptedString
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.arrmatey.downloadclient.model.DownloadClientType
import com.dnfapps.networking.NetworkResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class QBittorrentLoginProtectionTest {
    private val draft = DownloadClient(
        type = DownloadClientType.QBittorrent,
        label = "Setup",
        url = "https://qbittorrent.test/mounted",
        username = EncryptedString("fixture-user"),
        password = EncryptedString("fixture-wrong-password"),
    )

    @Test
    fun setupSaveAndPollingShareTheRefusalAcrossRelaunchUntilPasswordChanges() = runTest {
        withPreferencesFile { path ->
            val loginStarted = CompletableDeferred<Unit>()
            val releaseLogin = CompletableDeferred<Unit>()
            var logins = 0
            var serviceCalls = 0
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/mounted/api/v2/auth/login") {
                    logins++
                    val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    if (form.contains("fixture-right-password")) {
                        respond("Ok.")
                    } else {
                        loginStarted.complete(Unit)
                        releaseLogin.await()
                        respond("Fails.")
                    }
                } else {
                    serviceCalls++
                    respond("version")
                }
            }
            httpClient(engine).use { http ->
                val saved = draft.copy(id = 12, label = "Saved")
                withProtection(path) { policy ->
                    val setup = async { QBittorrentClient(draft, http, policy).testConnection() }
                    loginStarted.await()
                    val save = async { QBittorrentClient(saved, http, policy).testConnection() }
                    val refresh = async { QBittorrentClient(saved, http, policy).getDownloads() }
                    releaseLogin.complete(Unit)

                    assertEquals(401, assertIs<NetworkResult.Error>(setup.await()).code)
                    assertEquals(401, assertIs<NetworkResult.Error>(save.await()).code)
                    assertEquals(401, assertIs<NetworkResult.Error>(refresh.await()).code)
                    assertEquals(1, logins)
                    assertEquals(0, serviceCalls)
                }

                val stored = FileSystem.SYSTEM.read(path) { readUtf8() }
                assertFalse(stored.contains("fixture-wrong-password"))
                assertFalse(stored.contains("fixture-user"))

                withProtection(path) { policy ->
                    val unchanged = saved.copy(url = "https://qbittorrent.test:443/mounted/", label = "Renamed")
                    val previous = QBittorrentClient(unchanged, http, policy).getTransferInfo()
                    assertEquals(401, assertIs<NetworkResult.Error>(previous).code)
                    assertEquals(1, logins)
                    assertEquals(0, serviceCalls)

                    val edited = saved.copy(password = EncryptedString("fixture-right-password"))
                    assertIs<NetworkResult.Success<Unit>>(QBittorrentClient(edited, http, policy).testConnection())
                    assertEquals(2, logins)
                    assertEquals(1, serviceCalls)
                }
            }
        }
    }

    @Test
    fun sessionRefusalDoesNotInvalidateAPreviouslyAcceptedPasswordAcrossSessions() = runTest {
        withPreferencesFile { path ->
            var logins = 0
            val engine = MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/auth/login") -> {
                        logins++
                        respond("Ok.")
                    }
                    request.url.encodedPath.endsWith("/torrents/info") ->
                        respond("Expired session", HttpStatusCode.Unauthorized)
                    else -> respond("version")
                }
            }
            httpClient(engine).use { http ->
                withProtection(path) { policy ->
                    val first = QBittorrentClient(draft, http, policy)
                    assertEquals(401, assertIs<NetworkResult.Error>(first.getDownloads()).code)
                    assertEquals(401, assertIs<NetworkResult.Error>(first.testConnection()).code)
                    assertEquals(1, logins)
                }
                withProtection(path) { policy ->
                    assertIs<NetworkResult.Success<Unit>>(QBittorrentClient(draft, http, policy).testConnection())
                    assertEquals(2, logins)
                }
            }
        }
    }

    @Test
    fun unavailableIdentityDoesNotSendCredentials() = runTest {
        withPreferencesFile { path ->
            withProtection(path, identify = { error("Key unavailable") }) { policy ->
                val engine = MockEngine { error("No network call expected") }
                httpClient(engine).use { http ->
                    val result = QBittorrentClient(draft, http, policy).testConnection()
                    assertEquals("Arrmada couldn't check qBittorrent's saved login protection.", assertIs<NetworkResult.Error>(result).message)
                    assertEquals(0, engine.requestHistory.size)
                }
            }
        }
    }

    private fun httpClient(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = true
        defaultRequest { url("https://qbittorrent.test/mounted/") }
    }

    private suspend fun withPreferencesFile(block: suspend (Path) -> Unit) {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qbittorrent-refusals-${Random.nextLong()}.preferences_pb"
        try {
            block(path)
        } finally {
            FileSystem.SYSTEM.delete(path)
        }
    }

    private suspend fun withProtection(
        path: Path,
        identify: (ByteArray) -> String = { it.toByteString().hmacSha256("test-only-identity-key".encodeUtf8()).hex() },
        block: suspend (QBittorrentLoginProtection) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.createWithPath(scope = scope) { path }
            block(QBittorrentLoginProtection(store, identify))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
