package com.dnfapps.arrmatey.downloadclient.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

private const val KEY_ALIAS = "ArrmadaQBittorrentLoginIdentity"

private val signingKey: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
    existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build())
        generateKey()
    }
}

internal actual fun identifyQBittorrentCredentials(input: ByteArray): String = Mac.getInstance("HmacSHA256").run {
    init(signingKey)
    doFinal(input).toHexString()
}
