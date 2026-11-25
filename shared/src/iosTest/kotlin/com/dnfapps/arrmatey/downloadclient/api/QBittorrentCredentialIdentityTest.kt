package com.dnfapps.arrmatey.downloadclient.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QBittorrentCredentialIdentityTest {
    @Test
    fun nativeHmacMatchesKnownFingerprintAndChangesWithCredentials() {
        val key = ByteArray(32) { it.toByte() }
        val old = "qbit-test-password".encodeToByteArray()
        val identity = qbittorrentCredentialHmac(old, key)

        assertEquals("e51eefb01e8558788a600d43d26b2133cb5f029be344fea274ebba6a3eaf65de", identity)
        assertEquals(identity, qbittorrentCredentialHmac(old, key))
        assertNotEquals(identity, qbittorrentCredentialHmac("another-test-password".encodeToByteArray(), key))
    }
}
