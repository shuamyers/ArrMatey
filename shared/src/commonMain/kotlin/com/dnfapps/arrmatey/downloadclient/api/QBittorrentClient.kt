package com.dnfapps.arrmatey.downloadclient.api

import com.dnfapps.arrmatey.arr.api.model.ArrDiskSpace
import com.dnfapps.arrmatey.downloadclient.api.model.QBittorrentSyncMetadataResponse
import com.dnfapps.arrmatey.downloadclient.api.model.QBittorrentTorrent
import com.dnfapps.arrmatey.downloadclient.api.model.QBittorrentTransferInfoResponse
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.arrmatey.downloadclient.model.DownloadClientSystemOverview
import com.dnfapps.arrmatey.downloadclient.model.DownloadItem
import com.dnfapps.arrmatey.downloadclient.model.DownloadItemStatus
import com.dnfapps.arrmatey.downloadclient.model.DownloadTransferInfo
import com.dnfapps.networking.ErrorType
import com.dnfapps.networking.NetworkResult
import com.dnfapps.networking.asSuccess
import com.dnfapps.networking.safeCall
import com.dnfapps.networking.safeGet
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class QBittorrentClient(
    private val downloadClient: DownloadClient,
    private val httpClient: HttpClient,
) : DownloadClientApi {
    private val authMutex = Mutex()
    private var authenticated: Boolean = false
    private var authFailure: NetworkResult.Error? = null

    override suspend fun testConnection(): NetworkResult<Unit> = authenticatedCall {
        // Probe a real endpoint, including for API keys and unauthenticated WebUI access.
        httpClient.safeCall {
            get("api/v2/app/version")
            Unit
        }
    }

    override suspend fun getDownloads(): NetworkResult<List<DownloadItem>> = authenticatedCall {
        httpClient
            .safeGet<List<QBittorrentTorrent>>("api/v2/torrents/info")
            .map { torrents -> torrents.map { it.toDownloadItem() } }
    }

    override suspend fun pauseDownload(ids: List<String>): NetworkResult<Unit> = authenticatedCall { postTorrentAction("api/v2/torrents/stop", ids) }

    override suspend fun resumeDownload(ids: List<String>): NetworkResult<Unit> = authenticatedCall { postTorrentAction("api/v2/torrents/start", ids) }

    override suspend fun deleteDownload(
        ids: List<String>,
        deleteFiles: Boolean,
    ): NetworkResult<Unit> = authenticatedCall {
        httpClient.safeCall {
            post("api/v2/torrents/delete") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("hashes", ids.joinToString("|"))
                            append("deleteFiles", deleteFiles.toString())
                        },
                    ),
                )
            }
            Unit
        }
    }

    suspend fun getSyncMetadata(): NetworkResult<QBittorrentSyncMetadataResponse> = authenticatedCall {
        val result = httpClient.safeGet<QBittorrentSyncMetadataResponse>("api/v2/sync/metadata")
        if (result is NetworkResult.Error && result.code == 404) {
            val maindata = httpClient.safeGet<QBittorrentSyncMetadataResponse>("api/v2/sync/maindata")
            if (maindata is NetworkResult.Error && maindata.code == 404) {
                httpClient.safeGet<QBittorrentSyncMetadataResponse>("sync/metadata")
            } else {
                maindata
            }
        } else {
            result
        }
    }

    override suspend fun getTransferInfo(): NetworkResult<DownloadTransferInfo> = authenticatedCall {
        if (downloadClient.showExternalIpAddress) {
            when (val syncResult = getSyncMetadata()) {
                is NetworkResult.Success -> {
                    val serverState = syncResult.data.serverState
                    NetworkResult.Success(
                        DownloadTransferInfo(
                            client = downloadClient,
                            downloadSpeed = serverState?.downloadSpeed ?: 0L,
                            uploadSpeed = serverState?.uploadSpeed ?: 0L,
                            externalIp = serverState?.lastExternalAddressV4?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
                is NetworkResult.Error -> {
                    if (syncResult.isAuthFailure() || syncResult.code == 429) return@authenticatedCall syncResult
                    httpClient
                        .safeGet<QBittorrentTransferInfoResponse>("api/v2/transfer/info")
                        .map { info ->
                            DownloadTransferInfo(
                                client = downloadClient,
                                downloadSpeed = info.downloadSpeed,
                                uploadSpeed = info.uploadSpeed,
                            )
                        }
                }
                is NetworkResult.Loading -> NetworkResult.Loading
            }
        } else {
            httpClient
                .safeGet<QBittorrentTransferInfoResponse>("api/v2/transfer/info")
                .map { info ->
                    DownloadTransferInfo(
                        client = downloadClient,
                        downloadSpeed = info.downloadSpeed,
                        uploadSpeed = info.uploadSpeed,
                    )
                }
        }
    }

    override suspend fun getSystemOverview(): NetworkResult<DownloadClientSystemOverview> = authenticatedCall {
        val version = httpClient.safeGet<String>("api/v2/app/version")
        version.asAccessError()?.let { return@authenticatedCall it }
        val webApiVersion = httpClient.safeGet<String>("api/v2/app/webapiVersion")
        webApiVersion.asAccessError()?.let { return@authenticatedCall it }
        val defaultSavePath = httpClient.safeGet<String>("api/v2/app/defaultSavePath")
        defaultSavePath.asAccessError()?.let { return@authenticatedCall it }
        val syncResult = getSyncMetadata()
        syncResult.asAccessError()?.let { return@authenticatedCall it }
        val serverState = (syncResult as? NetworkResult.Success)?.data?.serverState

        val downloadsResult = getDownloads()
        downloadsResult.asAccessError()?.let { return@authenticatedCall it }
        val downloads = (downloadsResult as? NetworkResult.Success)?.data.orEmpty()
        val activeCount = downloads.count { it.status == DownloadItemStatus.Downloading }

        val freeSpace = serverState?.freeSpaceOnDisk ?: 0L
        val diskPath = defaultSavePath.asSuccess()?.data?.trim()?.takeIf { it.isNotBlank() }
        val diskSpaces = if (freeSpace > 0L) {
            listOf(
                ArrDiskSpace(
                    path = diskPath,
                    label = diskPath ?: "Free Disk Space",
                    freeSpace = freeSpace,
                    totalSpace = 0L,
                ),
            )
        } else {
            emptyList()
        }

        val transferInfo = DownloadTransferInfo(
            client = downloadClient,
            downloadSpeed = serverState?.downloadSpeed ?: 0L,
            uploadSpeed = serverState?.uploadSpeed ?: 0L,
            externalIp = serverState?.lastExternalAddressV4?.takeIf { it.isNotBlank() },
        )

        NetworkResult.Success(
            DownloadClientSystemOverview(
                version = version.asSuccess()?.data?.trim(),
                webApiVersion = webApiVersion.asSuccess()?.data?.trim(),
                downloadDir = diskPath,
                diskSpaces = diskSpaces,
                connectionStatus = serverState?.connectionStatus?.takeIf { it.isNotBlank() },
                dhtNodes = serverState?.dhtNodes,
                totalPeers = serverState?.totalPeerConnections,
                transferInfo = transferInfo,
                activeDownloadsCount = activeCount,
                alltimeDownloaded = serverState?.alltimeDl,
                alltimeUploaded = serverState?.alltimeUl,
            ),
        )
    }

    private suspend fun <T> authenticatedCall(block: suspend () -> NetworkResult<T>): NetworkResult<T> {
        when (val auth = authMutex.withLock { ensureAuthenticated() }) {
            is NetworkResult.Error -> return auth
            is NetworkResult.Loading -> return NetworkResult.Loading
            is NetworkResult.Success -> Unit
        }
        val result = block()
        if (result is NetworkResult.Error && result.isAuthFailure()) {
            authMutex.withLock { authFailure = result }
        }
        return result
    }

    private suspend fun ensureAuthenticated(): NetworkResult<Unit> {
        authFailure?.let { return it }
        if (authenticated) return NetworkResult.Success(Unit)

        // API-key auth is stateless via Authorization: Bearer; /auth/login rejects keys and repeated failures IP-ban.
        if (downloadClient.apiKey.value.isNotEmpty()) {
            authenticated = true
            return NetworkResult.Success(Unit)
        }

        // Nothing to log in with; rely on WebUI subnet whitelist / disabled auth.
        if (downloadClient.username.value.isEmpty() && downloadClient.password.value.isEmpty()) {
            authenticated = true
            return NetworkResult.Success(Unit)
        }

        val loginResult =
            httpClient.safeCall {
                post("api/v2/auth/login") {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                append("username", downloadClient.username.value)
                                append("password", downloadClient.password.value)
                            },
                        ),
                    )
                }.bodyAsText().trim()
            }

        return when (loginResult) {
            is NetworkResult.Success -> {
                // qBittorrent can reject credentials with HTTP 200 and the response body "Fails.".
                if (loginResult.data != "Ok.") {
                    return NetworkResult.Error(
                        code = 401,
                        message = "qBittorrent refused the login. Check the credentials in settings.",
                        errorType = ErrorType.Http,
                    ).also { authFailure = it }
                }
                authenticated = true
                NetworkResult.Success(Unit)
            }
            is NetworkResult.Error -> {
                if (loginResult.isAuthFailure()) authFailure = loginResult
                loginResult
            }
            is NetworkResult.Loading -> loginResult
        }
    }

    private fun NetworkResult.Error.isAuthFailure(): Boolean = code == 401 || code == 403

    private fun NetworkResult<*>.asAccessError(): NetworkResult.Error? = (this as? NetworkResult.Error)?.takeIf { it.isAuthFailure() || it.code == 429 }

    private suspend fun postTorrentAction(
        endpoint: String,
        hashes: List<String>,
    ): NetworkResult<Unit> = httpClient.safeCall {
        post(endpoint) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("hashes", hashes.joinToString("|"))
                    },
                ),
            )
        }
    }

    private fun QBittorrentTorrent.toDownloadItem(): DownloadItem = DownloadItem(
        client = downloadClient,
        id = hash,
        name = name,
        size = size,
        downloaded = downloaded,
        progress = progress,
        downloadSpeed = downloadSpeed,
        uploadSpeed = uploadSpeed,
        eta = eta,
        status = DownloadItemStatus.from(state),
        category = category,
        addedOn = addedOn,
        tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
    )
}
