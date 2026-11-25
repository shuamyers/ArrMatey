package com.dnfapps.arrmatey.arrmada

import com.dnfapps.arrmatey.database.EncryptedString
import com.dnfapps.arrmatey.database.dao.InsertResult
import com.dnfapps.arrmatey.downloadclient.database.DownloadClientInsertResult
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.arrmatey.downloadclient.model.DownloadClientType
import com.dnfapps.arrmatey.instances.model.Instance
import com.dnfapps.arrmatey.instances.model.InstanceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Arrmada: the household's services, pre-added on first launch so the user only pastes each API key.
 *
 * The list comes from a private `ArrmadaSeed.json` bundled with personal builds (git-ignored; the public repo
 * only has an example). It holds service types, names and addresses, never credentials: every service is
 * added with an empty key, which the user fills in once.
 */
@Serializable
data class ArrmadaSeed(
    val instances: List<Service> = emptyList(),
    val downloadClients: List<Service> = emptyList(),
) {
    @Serializable
    data class Service(
        val type: String,
        val label: String,
        val url: String,
    )
}

data class ArrmadaSeedOutcome(
    val added: List<String>,
    val alreadyPresent: List<String>,
    val failed: List<String>,
) {
    val summary: String
        get() = "added ${added.size} $added, already present ${alreadyPresent.size}, failed ${failed.size} $failed"
}

class ArrmadaSeeder(
    private val addInstance: suspend (Instance) -> InsertResult,
    private val addDownloadClient: suspend (DownloadClient) -> DownloadClientInsertResult,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Adds every listed service that is not already there (same name or address). Never changes existing ones. */
    suspend fun seed(seedJson: String): ArrmadaSeedOutcome {
        val seed = json.decodeFromString<ArrmadaSeed>(seedJson)
        val added = mutableListOf<String>()
        val present = mutableListOf<String>()
        val failed = mutableListOf<String>()

        seed.instances.forEach { service ->
            val type = InstanceType.entries.firstOrNull { it.name.equals(service.type, ignoreCase = true) }
            if (type == null) {
                failed += "${service.label} (unknown type ${service.type})"
                return@forEach
            }
            val instance = Instance(type = type, label = service.label, url = service.url, apiKey = EncryptedString(""))
            when (addInstance(instance)) {
                is InsertResult.Success -> added += service.label
                is InsertResult.Conflict -> present += service.label
                is InsertResult.Error -> failed += service.label
            }
        }

        seed.downloadClients.forEach { service ->
            val type = DownloadClientType.entries.firstOrNull { it.name.equals(service.type, ignoreCase = true) }
            if (type == null) {
                failed += "${service.label} (unknown type ${service.type})"
                return@forEach
            }
            when (addDownloadClient(DownloadClient(type = type, label = service.label, url = service.url))) {
                is DownloadClientInsertResult.Success -> added += service.label
                is DownloadClientInsertResult.Conflict -> present += service.label
                is DownloadClientInsertResult.Error -> failed += service.label
            }
        }

        return ArrmadaSeedOutcome(added, present, failed)
    }
}
