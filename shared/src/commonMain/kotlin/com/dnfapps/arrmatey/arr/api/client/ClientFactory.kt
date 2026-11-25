package com.dnfapps.arrmatey.arr.api.client

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dnfapps.arrmatey.arr.api.client.logging.DynamicLogger
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.arrmatey.downloadclient.model.DownloadClientType
import com.dnfapps.arrmatey.instances.model.HeaderRestrictionType
import com.dnfapps.arrmatey.instances.model.Instance
import com.dnfapps.arrmatey.instances.model.InstanceType
import com.dnfapps.arrmatey.instances.model.SeerrAuthType
import com.dnfapps.arrmatey.mediaserver.model.MediaServerIntegration
import com.dnfapps.arrmatey.utils.getNetworkUtils
import com.dnfapps.networking.HttpRateLimitException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

private const val HEADER_X_API_KEY = "X-Api-Key"
const val DEFAULT_SLOW_TIMEOUT = 300

/** Instance client setup, split out so tests can apply it to a mock engine. */
internal fun HttpClientConfig<*>.configureInstanceClient(
    instance: Instance?,
    json: Json,
    customLogger: Logger,
    serviceRateLimit: ServiceRateLimit? = null,
) {
    expectSuccess = true
    enforceServiceRateLimits(serviceRateLimit)
    install(ContentNegotiation) {
        json(json)
    }

    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000

        if (instance?.slowInstance == true) {
            val timeoutMillis = (instance.customTimeout ?: DEFAULT_SLOW_TIMEOUT).toLong() * 1_000
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    }

    install(HttpRequestRetry) {
        // Only reads are retried. Writes (approve, decline, delete, …) are never resent automatically:
        // a resend after a lost answer could repeat an action the server already performed.
        retryIf(maxRetries = 3) { request, response ->
            request.method.isRetryableRead() && response.status.value in 500..599
        }
        retryOnExceptionIf(maxRetries = 3) { request, cause ->
            request.method.isRetryableRead() && cause !is CancellationException && cause !is HttpRateLimitException && !cause.isTimeout()
        }
        exponentialDelay()
    }

    install(Logging) {
        logger = customLogger
        level = (customLogger as? DynamicLogger)?.level ?: LogLevel.HEADERS
    }

    val usesSeerrSession = instance?.type == InstanceType.Seerr && instance.seerrAuthType != SeerrAuthType.ApiKey

    install(HttpCookies) {
        // The Seerr session comes from the cookie storage, not a hand-set Cookie header (see
        // SeerrSessionCookiesStorage: concurrent calls lost that header and got 401).
        storage = if (usesSeerrSession) {
            SeerrSessionCookiesStorage(instance?.sessionToken?.value.orEmpty())
        } else {
            AcceptAllCookiesStorage()
        }
    }

    defaultRequest {
        if (!url.user.isNullOrBlank() && !url.password.isNullOrBlank()) {
            basicAuth(url.user!!, url.password!!)
            url.user = null
            url.password = null
        }

        instance?.let { instance ->
            if (usesSeerrSession) {
                // Session cookie is supplied by SeerrSessionCookiesStorage.
            } else if (!instance.noApiKeyRequired && instance.apiKey.value.isNotEmpty()) {
                when (instance.type) {
                    InstanceType.Tracearr ->
                        header(HttpHeaders.Authorization, "Bearer ${instance.apiKey.value}")
                    else ->
                        header(HEADER_X_API_KEY, instance.apiKey.value)
                }
            }
            instance.headers.forEach { header ->
                val shouldSend =
                    when (header.restrictionType) {
                        HeaderRestrictionType.Always -> true
                        HeaderRestrictionType.RemoteOnly -> !instance.isUsingLocalNetwork()
                        HeaderRestrictionType.SpecificSsids -> {
                            val currentSsid = getNetworkUtils().getCurrentWifiSsid()
                            currentSsid != null && header.restrictedSsids.contains(currentSsid)
                        }
                    }

                if (shouldSend) {
                    header(header.key, header.value)
                }
            }
        }
    }
}

open class HttpClientFactory(
    private val json: Json,
    private val logger: Logger,
    rateLimitPreferences: DataStore<Preferences>? = null,
) {
    private val serviceRateLimit = rateLimitPreferences?.let(::ServiceRateLimit)

    open fun create(instance: Instance): HttpClient = configuredInstanceClient(instance)

    fun createDownloadClient(downloadClient: DownloadClient): HttpClient = HttpClient {
        expectSuccess = true
        enforceServiceRateLimits(serviceRateLimit)
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }

        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }

        install(Logging) {
            this.logger = logger
            level = (logger as? DynamicLogger)?.level ?: LogLevel.HEADERS
        }

        defaultRequest {
            url(downloadClient.getEffectiveBaseUrl().trimEnd('/') + "/")
            if (!url.user.isNullOrBlank() && !url.password.isNullOrBlank()) {
                basicAuth(url.user!!, url.password!!)
                url.user = null
                url.password = null
            }
            if (!downloadClient.noApiKeyRequired && downloadClient.apiKey.value.isNotEmpty()) {
                when (downloadClient.type) {
                    // qBittorrent >= 5.2 only accepts API keys via Authorization: Bearer.
                    DownloadClientType.QBittorrent ->
                        header(HttpHeaders.Authorization, "Bearer ${downloadClient.apiKey.value}")
                    DownloadClientType.NZBGet,
                    DownloadClientType.RTorrent,
                    -> Unit
                    else ->
                        header(HEADER_X_API_KEY, downloadClient.apiKey.value)
                }
            }
            downloadClient.headers.forEach { header ->
                val shouldSend =
                    when (header.restrictionType) {
                        HeaderRestrictionType.Always -> true
                        HeaderRestrictionType.RemoteOnly -> !downloadClient.isUsingLocalNetwork()
                        HeaderRestrictionType.SpecificSsids -> {
                            val currentSsid = getNetworkUtils().getCurrentWifiSsid()
                            currentSsid != null && header.restrictedSsids.contains(currentSsid)
                        }
                    }

                if (shouldSend) {
                    header(header.key, header.value)
                }
            }
        }
    }

    open fun createGeneric(): HttpClient = configuredInstanceClient(null)

    open fun createMediaServerClient(integration: MediaServerIntegration): HttpClient = HttpClient {
        expectSuccess = true
        enforceServiceRateLimits(serviceRateLimit)
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        install(Logging) {
            this.logger = this@HttpClientFactory.logger
            level = (this@HttpClientFactory.logger as? DynamicLogger)?.level ?: LogLevel.HEADERS
        }
        defaultRequest {
            integration.headers.forEach { header ->
                val shouldSend =
                    when (header.restrictionType) {
                        HeaderRestrictionType.Always -> true
                        HeaderRestrictionType.RemoteOnly -> !integration.isUsingLocalNetwork()
                        HeaderRestrictionType.SpecificSsids -> {
                            val currentSsid = getNetworkUtils().getCurrentWifiSsid()
                            currentSsid != null && header.restrictedSsids.contains(currentSsid)
                        }
                    }
                if (shouldSend) {
                    header(header.key, header.value)
                }
            }
        }
    }

    private fun configuredInstanceClient(instance: Instance?): HttpClient = HttpClient {
        configureInstanceClient(instance, json, logger, serviceRateLimit)
    }
}

private fun HttpMethod.isRetryableRead(): Boolean = this == HttpMethod.Get || this == HttpMethod.Head

// Matches Ktor's own default, which does not retry timeouts.
private fun Throwable.isTimeout(): Boolean = this is HttpRequestTimeoutException || this is ConnectTimeoutException || this is SocketTimeoutException
