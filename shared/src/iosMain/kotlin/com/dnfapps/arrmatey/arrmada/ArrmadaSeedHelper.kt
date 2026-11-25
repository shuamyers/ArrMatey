package com.dnfapps.arrmatey.arrmada

import com.dnfapps.arrmatey.database.InstanceRepository
import com.dnfapps.arrmatey.downloadclient.repository.DownloadClientRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Called from Swift at launch with the bundled `ArrmadaSeed.json`. */
class ArrmadaSeedHelper : KoinComponent {
    private val instances: InstanceRepository by inject()
    private val downloadClients: DownloadClientRepository by inject()

    fun seed(
        seedJson: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            val result =
                try {
                    val outcome =
                        ArrmadaSeeder(
                            addInstance = { instances.createInstance(it) },
                            addDownloadClient = { downloadClients.createDownloadClient(it) },
                        ).seed(seedJson)
                    true to outcome.summary
                } catch (e: Exception) {
                    false to "failed: ${e.message}"
                }
            onDone(result.first, result.second)
        }
    }
}
