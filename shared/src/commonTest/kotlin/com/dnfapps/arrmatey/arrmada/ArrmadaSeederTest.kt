package com.dnfapps.arrmatey.arrmada

import com.dnfapps.arrmatey.database.dao.ConflictField
import com.dnfapps.arrmatey.database.dao.InsertResult
import com.dnfapps.arrmatey.downloadclient.database.DownloadClientConflictField
import com.dnfapps.arrmatey.downloadclient.database.DownloadClientInsertResult
import com.dnfapps.arrmatey.downloadclient.model.DownloadClient
import com.dnfapps.arrmatey.downloadclient.model.DownloadClientType
import com.dnfapps.arrmatey.instances.model.Instance
import com.dnfapps.arrmatey.instances.model.InstanceType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArrmadaSeederTest {
    private val instances = mutableListOf<Instance>()
    private val clients = mutableListOf<DownloadClient>()

    // Same conflict rule as the real repositories: name or address already taken.
    private val seeder = ArrmadaSeeder(
        addInstance = { i ->
            if (instances.any { it.url == i.url || it.label == i.label }) {
                InsertResult.Conflict(listOf(ConflictField.InstanceUrl))
            } else {
                instances += i
                InsertResult.Success(instances.size.toLong())
            }
        },
        addDownloadClient = { c ->
            if (clients.any { it.url == c.url || it.label == c.label }) {
                DownloadClientInsertResult.Conflict(listOf(DownloadClientConflictField.DownloadClientUrl))
            } else {
                clients += c
                DownloadClientInsertResult.Success(clients.size.toLong())
            }
        },
    )

    private val seed = """
        {
          "instances": [
            { "type": "Sonarr", "label": "Sonarr", "url": "http://sonarr.example.test" },
            { "type": "radarr", "label": "Radarr", "url": "http://radarr.example.test" },
            { "type": "Nope", "label": "Mystery", "url": "http://mystery.example.test" }
          ],
          "downloadClients": [
            { "type": "SABnzbd", "label": "SABnzbd", "url": "http://sabnzbd.example.test" }
          ]
        }
    """.trimIndent()

    @Test
    fun addsServicesWithoutCredentials() = runTest {
        val outcome = seeder.seed(seed)
        assertEquals(listOf("Sonarr", "Radarr", "SABnzbd"), outcome.added)
        assertEquals(listOf(InstanceType.Sonarr, InstanceType.Radarr), instances.map { it.type })
        assertEquals(DownloadClientType.SABnzbd, clients.single().type)
        assertTrue(instances.all { it.apiKey.value.isEmpty() }, "seeded instances must not carry a key")
        assertTrue(clients.all { it.apiKey.value.isEmpty() && it.password.value.isEmpty() })
    }

    @Test
    fun unknownTypeIsReportedNotAdded() = runTest {
        val outcome = seeder.seed(seed)
        assertTrue(outcome.failed.single().startsWith("Mystery"))
    }

    @Test
    fun runningTwiceAddsNothingAndKeepsExistingKeys() = runTest {
        seeder.seed(seed)
        // The user pastes a key into Sonarr; seeding again must not replace or duplicate it.
        val withKey = instances[0].copy(apiKey = com.dnfapps.arrmatey.database.EncryptedString("user-key"))
        instances[0] = withKey
        val second = seeder.seed(seed)
        assertEquals(emptyList(), second.added)
        assertEquals(listOf("Sonarr", "Radarr", "SABnzbd"), second.alreadyPresent)
        assertEquals("user-key", instances[0].apiKey.value)
        assertEquals(2, instances.size)
    }

    @Test
    fun existingServiceWithSameAddressIsLeftAlone() = runTest {
        instances += Instance(
            type = InstanceType.Sonarr,
            label = "My Sonarr",
            url = "http://sonarr.example.test",
            apiKey = com.dnfapps.arrmatey.database.EncryptedString("k"),
        )
        val outcome = seeder.seed(seed)
        assertTrue("Sonarr" in outcome.alreadyPresent)
        assertEquals("My Sonarr", instances.first().label)
    }
}
