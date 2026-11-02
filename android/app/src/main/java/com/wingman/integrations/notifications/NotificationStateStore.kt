package com.wingman.integrations.notifications

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable key/value state for the notification connector: access state, user toggles, dedup
 * claims, the reply ledger and the local event inbox. Same contract as WING-016's SmsStateStore
 * (kept separate so the two connectors share no state file): [put] and [remove] are durable when
 * they return, implementations are thread-safe, [keys] is in insertion order.
 */
interface NotificationStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}

/** Process-local store for tests. Share one instance between "restarted" components to model a restart. */
class InMemoryNotificationStateStore : NotificationStateStore {
    private val map = LinkedHashMap<String, String>()

    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }
    @Synchronized override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
}

/**
 * File-backed store: one JSON file rewritten through a temp file and an atomic rename. Use a file
 * under `Context.getFilesDir()` (app-private; the manifest sets `allowBackup="false"`). It holds
 * private message text while an event waits in the inbox, so never place it on shared storage.
 * One instance per file.
 */
class FileNotificationStateStore(private val file: File) : NotificationStateStore {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json
    private var cache: LinkedHashMap<String, String>? = null

    private fun load(): LinkedHashMap<String, String> {
        cache?.let { return it }
        val loaded = if (file.isFile) LinkedHashMap(json.decodeFromString(serializer, file.readText(Charsets.UTF_8))) else LinkedHashMap()
        cache = loaded
        return loaded
    }

    private fun flush(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, map), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    @Synchronized override fun get(key: String): String? = load()[key]

    @Synchronized override fun put(key: String, value: String) {
        val map = load()
        val previous = map.put(key, value)
        try {
            flush(map)
        } catch (e: IOException) {
            if (previous == null) map.remove(key) else map[key] = previous
            throw e
        }
    }

    @Synchronized override fun remove(key: String) {
        val map = load()
        val previous = map.remove(key) ?: return
        try {
            flush(map)
        } catch (e: IOException) {
            map[key] = previous
            throw e
        }
    }

    @Synchronized override fun keys(prefix: String): List<String> = load().keys.filter { it.startsWith(prefix) }
}

/**
 * The user's choices and the listener's persisted observations. Everything defaults to OFF:
 * a platform is read only after the user enables it, and direct reply needs its own opt-in.
 */
class NotificationSettings(private val store: NotificationStateStore) {
    /** Notification access as last reported by the system (listener connected / disconnected and probe). */
    var accessGranted: Boolean
        get() = store.get(KEY_ACCESS) == "granted"
        set(value) = store.put(KEY_ACCESS, if (value) "granted" else "revoked")

    fun readEnabled(platform: String): Boolean = store.get(KEY_READ + platform) == "on"

    fun setReadEnabled(platform: String, enabled: Boolean) = toggle(KEY_READ + platform, enabled)

    /** The per-platform opt-in for direct reply through a notification's RemoteInput. Off by default. */
    fun directReplyEnabled(platform: String): Boolean = store.get(KEY_REPLY + platform) == "on"

    fun setDirectReplyEnabled(platform: String, enabled: Boolean) = toggle(KEY_REPLY + platform, enabled)

    /** Set once a free-form RemoteInput action was seen on an authentic notification of [platform]. */
    fun remoteInputObserved(platform: String): Boolean = store.get(KEY_RI_SEEN + platform) != null

    fun markRemoteInputObserved(platform: String, atMillis: Long) {
        if (store.get(KEY_RI_SEEN + platform) == null) store.put(KEY_RI_SEEN + platform, atMillis.toString())
    }

    private fun toggle(key: String, enabled: Boolean) {
        if (enabled) store.put(key, "on") else if (store.get(key) != null) store.remove(key)
    }

    companion object {
        const val KEY_ACCESS = "settings:access"
        const val KEY_READ = "settings:read:"
        const val KEY_REPLY = "settings:direct_reply:"
        const val KEY_RI_SEEN = "observed:remote_input:"
    }
}
