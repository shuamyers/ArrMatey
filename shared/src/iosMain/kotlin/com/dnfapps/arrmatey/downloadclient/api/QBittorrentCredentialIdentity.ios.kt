@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.dnfapps.arrmatey.downloadclient.api

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.CoreFoundation.*
import platform.Security.*

private const val KEY_ACCOUNT = "com.dnfapps.arrmatey.qbittorrent.login-identity"
private const val SHA256_BYTES = 32

@OptIn(ExperimentalForeignApi::class)
private val signingKey: ByteArray by lazy { readSigningKey() ?: createSigningKey() }

@OptIn(ExperimentalForeignApi::class)
internal actual fun identifyQBittorrentCredentials(input: ByteArray): String = qbittorrentCredentialHmac(input, signingKey)

@OptIn(ExperimentalForeignApi::class)
internal fun qbittorrentCredentialHmac(input: ByteArray, key: ByteArray): String {
    val digest = ByteArray(SHA256_BYTES)
    key.usePinned { secret ->
        input.usePinned { message ->
            digest.usePinned { output ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    secret.addressOf(0),
                    key.size.toULong(),
                    message.addressOf(0),
                    input.size.toULong(),
                    output.addressOf(0),
                )
            }
        }
    }
    return digest.toHexString()
}

@OptIn(ExperimentalForeignApi::class)
private fun readSigningKey(): ByteArray? = withKeychainQuery { query ->
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
    memScoped {
        val result = alloc<CFTypeRefVar>()
        when (val status = SecItemCopyMatching(query, result.ptr)) {
            errSecItemNotFound -> null
            errSecSuccess -> {
                val value = checkNotNull(result.value)
                try {
                    check(CFGetTypeID(value) == CFDataGetTypeID())
                    val data: CFDataRef = value.reinterpret()
                    check(CFDataGetLength(data) == SHA256_BYTES.toLong())
                    checkNotNull(CFDataGetBytePtr(data)).readBytes(SHA256_BYTES)
                } finally {
                    CFRelease(value)
                }
            }
            else -> error("Can't read the qBittorrent login identity key ($status)")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createSigningKey(): ByteArray {
    val key = ByteArray(SHA256_BYTES)
    key.usePinned { secret ->
        check(SecRandomCopyBytes(kSecRandomDefault, key.size.toULong(), secret.addressOf(0)) == errSecSuccess)
    }

    return withKeychainQuery { query ->
        val data = checkNotNull(
            key.usePinned { secret ->
                CFDataCreate(null, secret.addressOf(0).reinterpret(), key.size.toLong())
            },
        )
        try {
            CFDictionarySetValue(query, kSecValueData, data)
            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            when (val status = SecItemAdd(query, null)) {
                errSecSuccess -> key
                errSecDuplicateItem -> checkNotNull(readSigningKey())
                else -> error("Can't store the qBittorrent login identity key ($status)")
            }
        } finally {
            CFRelease(data)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withKeychainQuery(action: (CFMutableDictionaryRef) -> T): T = memScoped {
    val account = checkNotNull(CFStringCreateWithCString(null, KEY_ACCOUNT, kCFStringEncodingUTF8))
    try {
        val query = checkNotNull(
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        )
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            action(query)
        } finally {
            CFRelease(query)
        }
    } finally {
        CFRelease(account)
    }
}
