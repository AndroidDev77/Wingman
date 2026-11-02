package com.wingman.integrations.hinge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The on-device Hinge session (D-021 item 3). It never leaves the device: it is not a contract
 * type, it is not serializable with `WingmanJson`, nothing in `core/` can see this package
 * (static test), and no Wingman request body is built from it (dynamic test).
 *
 * The Sendbird `Session-Key` (from the WebSocket `LOGI` frame) is deliberately NOT here: it lives
 * in memory only ([SendbirdChatTransport]).
 */
@Serializable
class HingeSession(
    /** Client-generated per-install ids the reference sends as headers. Not secrets, still private. */
    @SerialName("install_id") val installId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("install_registered") val installRegistered: Boolean = false,
    /** Hinge identity id (also the Sendbird user id). */
    @SerialName("identity_id") val identityId: String? = null,
    @SerialName("hinge_token") val hingeToken: String? = null,
    @SerialName("hinge_expires_at") val hingeExpiresAtMillis: Long? = null,
    /** Sendbird JWT from `/message/authenticate` ("chat token"). */
    @SerialName("chat_token") val chatToken: String? = null,
    @SerialName("chat_expires_at") val chatExpiresAtMillis: Long? = null,
) {
    fun copy(
        installRegistered: Boolean = this.installRegistered,
        identityId: String? = this.identityId,
        hingeToken: String? = this.hingeToken,
        hingeExpiresAtMillis: Long? = this.hingeExpiresAtMillis,
        chatToken: String? = this.chatToken,
        chatExpiresAtMillis: Long? = this.chatExpiresAtMillis,
    ) = HingeSession(installId, deviceId, sessionId, installRegistered, identityId, hingeToken, hingeExpiresAtMillis, chatToken, chatExpiresAtMillis)

    fun hasHingeAuth(nowMillis: Long): Boolean =
        !hingeToken.isNullOrEmpty() && !identityId.isNullOrEmpty() && (hingeExpiresAtMillis ?: 0) > nowMillis

    fun hasChatToken(nowMillis: Long): Boolean = !chatToken.isNullOrEmpty() && (chatExpiresAtMillis ?: 0) > nowMillis

    /** Every token and id is redacted. */
    override fun toString(): String =
        "HingeSession(installRegistered=$installRegistered, identity=${identityId?.let { "<redacted>" }}, " +
            "hingeToken=${redacted(hingeToken)}, hingeExpires=$hingeExpiresAtMillis, chatToken=${redacted(chatToken)}, chatExpires=$chatExpiresAtMillis)"

    companion object {
        /** Fresh client ids, upper-case UUIDs as in the reference. */
        fun newInstall(newId: () -> String = { UUID.randomUUID().toString().uppercase() }) = HingeSession(newId(), newId(), newId())
    }
}

/**
 * Symmetric encryption of the session file. The Android implementation
 * ([com.wingman.integrations.hinge.android.KeystoreHingeCipher]) uses a non-exportable AES-256-GCM
 * key in the Android Keystore. Tests use a fake.
 */
interface HingeSecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    /** Throws when the data was not produced by this key (e.g. the key was deleted). */
    fun decrypt(sealed: ByteArray): ByteArray
    /** Destroys the key, when the implementation can (opt-out). */
    fun destroyKey() {}
}

/** Where the session lives. [wipe] is immediate and durable when it returns. */
interface HingeSessionStore {
    fun load(): HingeSession?
    fun save(session: HingeSession)
    fun wipe()
}

class InMemoryHingeSessionStore : HingeSessionStore {
    @Volatile private var session: HingeSession? = null
    @Synchronized override fun load(): HingeSession? = session
    @Synchronized override fun save(session: HingeSession) { this.session = session }
    @Synchronized override fun wipe() { session = null }
}

/**
 * Keystore-encrypted file store. On a device [file] is under `Context.getNoBackupFilesDir()`
 * (never backed up; the manifest also sets `allowBackup="false"`). Writes go through a temp file
 * and an atomic rename. A file that cannot be decrypted is deleted and treated as "no session",
 * so the user logs in again; it is never reported or logged.
 */
class EncryptedFileHingeSessionStore(
    private val file: File,
    private val cipher: HingeSecretCipher,
) : HingeSessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: HingeSession? = null
    private var loaded = false

    @Synchronized
    override fun load(): HingeSession? {
        if (loaded) return cache
        loaded = true
        cache = if (!file.isFile) null else runCatching {
            json.decodeFromString(HingeSession.serializer(), String(cipher.decrypt(file.readBytes()), Charsets.UTF_8))
        }.getOrElse {
            file.delete()
            null
        }
        return cache
    }

    @Synchronized
    override fun save(session: HingeSession) {
        val sealed = cipher.encrypt(json.encodeToString(HingeSession.serializer(), session).toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        cache = session
        loaded = true
    }

    @Synchronized
    override fun wipe() {
        cache = null
        loaded = true
        File(file.parentFile, file.name + ".tmp").delete()
        if (file.exists() && !file.delete()) file.writeBytes(ByteArray(0))
        runCatching { cipher.destroyKey() }
    }
}
