package com.nuvio.app.features.plugins

/**
 * Bridge to the Swift CryptoKit AES-GCM implementation. Swift implements [NuvioCryptoBridge] (backed
 * by CryptoKit's public `AES.GCM`) and registers a factory at app startup, mirroring
 * [com.nuvio.app.features.player.cast.NuvioCastBridge].
 *
 * Rationale: CommonCrypto's AES-GCM (`CCCryptorGCMEncrypt`/`Decrypt`/`Final` and the one-shot
 * `CCCryptorGCMOneshotEncrypt`/`Decrypt` variants) all live in CommonCryptorSPI.h and are non-public
 * API — App Store static analysis rejects them (ITMS-90338). There is no public C AES-GCM on iOS, so
 * GCM is delegated to CryptoKit through this Swift bridge. The other AES modes (CBC/ECB), digests,
 * HMAC and PBKDF2 stay on public CommonCrypto in PluginCrypto.ios.kt.
 *
 * Payloads cross the bridge as lowercase hex to keep the Kotlin↔Swift surface free of byte-array
 * marshalling, matching the hex transport the QuickJS crypto bridge already uses.
 */
interface NuvioCryptoBridge {
    /**
     * AES-GCM seal. Returns `ciphertext || 16-byte tag` as hex (the WebCrypto/JCE wire format), or
     * null on failure (bad key/nonce size or CryptoKit error).
     */
    fun aesGcmEncryptHex(keyHex: String, ivHex: String, dataHex: String): String?

    /**
     * AES-GCM open. [dataHex] is `ciphertext || 16-byte tag` as hex. Returns the plaintext as hex, or
     * null when authentication fails or the input is malformed.
     */
    fun aesGcmDecryptHex(keyHex: String, ivHex: String, dataHex: String): String?
}

/** Registry for the crypto bridge factory; Swift registers during app startup before Compose starts. */
object NuvioCryptoBridgeFactory {
    private var factoryRef: NuvioCryptoBridgeCreator? = null

    fun registerFactory(creator: NuvioCryptoBridgeCreator) {
        this.factoryRef = creator
    }

    fun create(): NuvioCryptoBridge? = factoryRef?.createBridge()

    val isRegistered: Boolean get() = factoryRef != null
}

interface NuvioCryptoBridgeCreator {
    fun createBridge(): NuvioCryptoBridge
}
