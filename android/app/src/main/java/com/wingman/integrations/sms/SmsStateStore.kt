package com.wingman.integrations.sms

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Minimal durable key/value state for the SMS connector: inbound dedup claims, the send
 * ledger, and the outbox of envelopes waiting for upload. Everything that must survive
 * process death (the OS kills a BroadcastReceiver's process freely) goes through this.
 *
 * Contract: [put] and [remove] are durable when they return. Implementations are thread-safe.
 */
interface SmsStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    /** Keys starting with [prefix], in insertion order. */
    fun keys(prefix: String): List<String>
}

/** Process-local store for tests. Share one instance between "restarted" components to model a restart. */
class InMemorySmsStateStore : SmsStateStore {
    private val map = LinkedHashMap<String, String>()

    @Synchronized override fun get(key: String): String? = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }
    @Synchronized override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
}

/**
 * File-backed store: the whole map is one JSON file, rewritten through a temp file and an
 * atomic rename, so a crash mid-write leaves either the old or the new file, never a torn one.
 * Pure java.io/nio: on a device, pass a file under `Context.getFilesDir()` (app-private, not
 * backed up because the manifest sets `allowBackup="false"`).
 *
 * Sized for the connector's small state (a few thousand entries); [SmsInboundDedup] and the
 * ledger prune old entries. It holds private message text while an envelope waits in the
 * outbox, so it must never be placed on shared/external storage.
 *
 * Several instances on the same file in one process are not coordinated; use one instance
 * per file (the AppContainer wiring creates exactly one).
 */
class FileSmsStateStore(private val file: File) : SmsStateStore {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { prettyPrint = false }
    private var cache: LinkedHashMap<String, String>? = null

    private fun load(): LinkedHashMap<String, String> {
        cache?.let { return it }
        val loaded = if (file.isFile) {
            LinkedHashMap(json.decodeFromString(serializer, file.readText(Charsets.UTF_8)))
        } else {
            LinkedHashMap()
        }
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
