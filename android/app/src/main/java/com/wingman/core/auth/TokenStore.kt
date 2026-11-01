package com.wingman.core.auth

import kotlinx.serialization.json.Json
import java.util.Base64

/** Persists the one signed-in session. Implementations must keep tokens encrypted at rest. */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

/** Not persisted. Tests and previews only. */
class InMemoryTokenStore(private var tokens: AuthTokens? = null) : TokenStore {
    override fun load(): AuthTokens? = tokens
    override fun save(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

/** Authenticated encryption of small secrets. On Android: an AES-256-GCM key held in the Android Keystore. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Throws if [blob] was not produced by this cipher's key or was modified. */
    fun decrypt(blob: ByteArray): ByteArray
}

/** One string slot of app-private storage (SharedPreferences on Android). Holds ciphertext only. */
interface BlobStore {
    fun read(): String?
    fun write(value: String)
    fun delete()
}

/**
 * [TokenStore] that serializes the session to JSON, encrypts it with [cipher] and keeps only the
 * Base64 ciphertext in [blobs]. An unreadable blob (key invalidated after a lock-screen change,
 * restored from another device, corrupted) is deleted and treated as signed out; its contents are
 * never logged.
 */
class EncryptedTokenStore(
    private val cipher: SecretCipher,
    private val blobs: BlobStore,
    private val logger: AuthLogger = AuthLogger.NONE,
) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): AuthTokens? {
        val stored = blobs.read() ?: return null
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(stored))
            json.decodeFromString(AuthTokens.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            // Decryption, Base64 and JSON errors alike. Their messages could echo input, so only
            // the exception type is logged.
            logger.log("auth: stored session unreadable (${e.javaClass.simpleName}); cleared")
            blobs.delete()
            null
        }
    }

    override fun save(tokens: AuthTokens) {
        val plain = json.encodeToString(AuthTokens.serializer(), tokens).encodeToByteArray()
        blobs.write(Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    override fun clear() = blobs.delete()
}
