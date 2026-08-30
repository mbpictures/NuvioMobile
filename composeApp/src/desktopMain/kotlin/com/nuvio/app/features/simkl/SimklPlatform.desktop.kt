package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import java.security.MessageDigest
import java.security.SecureRandom

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object SimklPkceCrypto {
    private val random = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray {
        require(size > 0)
        return ByteArray(size).also(random::nextBytes)
    }

    actual fun sha256(value: ByteArray): ByteArray {
        require(value.isNotEmpty())
        return MessageDigest.getInstance("SHA-256").digest(value)
    }
}

internal actual object SimklAuthStorage {
    private const val preferencesName = "nuvio_simkl_auth"
    private const val metadataKey = "simkl_auth_metadata"
    private const val accessTokenKey = "simkl_access_token"
    private const val codeVerifierKey = "simkl_code_verifier"

    actual fun loadMetadataPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(metadataKey))

    actual fun saveMetadataPayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(metadataKey), payload)
    }

    actual fun loadAccessToken(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(accessTokenKey))

    actual fun saveAccessToken(value: String?) {
        DesktopPreferences.putNullableString(preferencesName, ProfileScopedKey.of(accessTokenKey), value)
    }

    actual fun loadCodeVerifier(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(codeVerifierKey))

    actual fun saveCodeVerifier(value: String?) {
        DesktopPreferences.putNullableString(preferencesName, ProfileScopedKey.of(codeVerifierKey), value)
    }

    actual fun removeProfile(profileId: Int) {
        DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(metadataKey, profileId))
        DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(accessTokenKey, profileId))
        DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(codeVerifierKey, profileId))
    }
}
