package com.hongguo.android

import com.hongguo.api.util.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded recent series IDs per hashed owner; no credentials, titles or server sessions are stored. */
internal class RecommendationHistory(private val directory: File) {
    private data class Record(val ids: List<String>, val updatedAt: Long)
    private val file = File(directory, "recommendations-v1.json")
    private var records: MutableMap<String, Record>? = null
    private var writable = true

    @Synchronized fun recent(owner: String): List<String> = load()[key(owner)]?.ids.orEmpty()

    @Synchronized fun remember(owner: String, ids: List<String>) {
        val current = load()
        val ownerKey = key(owner)
        current[ownerKey] = Record(normalized(current[ownerKey]?.ids.orEmpty() + ids), System.currentTimeMillis())
        val keep = current.entries.sortedByDescending { it.value.updatedAt }.take(8).map { it.key }.toSet()
        current.keys.retainAll(keep)
        if (!writable) return
        var temporary: File? = null
        try {
            check(directory.isDirectory || directory.mkdirs())
            temporary = File.createTempFile("recommendations-", ".tmp", directory)
            temporary.writeText(Json.stringify(current.mapValues { (_, row) -> mapOf("ids" to row.ids, "updated_at" to row.updatedAt) }))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) { /* Optional cache: continue recommending if storage is unavailable. */ }
        finally { temporary?.delete() }
    }

    private fun load(): MutableMap<String, Record> {
        records?.let { return it }
        val loaded = linkedMapOf<String, Record>()
        try {
            if (file.exists()) {
                require(file.length() <= 2 * 1024 * 1024)
                val root = Json.parseObject(file.readText())
                require(root.size <= 8)
                root.forEach { (owner, value) ->
                    require(owner.matches(Regex("[a-f0-9]{64}")))
                    val row = Json.optObj(value) ?: error("Invalid recommendation history")
                    loaded[owner] = Record(normalized(Json.optArr(row["ids"]).filterIsInstance<String>()), Json.optLong(row["updated_at"], 0))
                }
            }
        } catch (_: Exception) { loaded.clear(); writable = false }
        records = loaded
        return loaded
    }

    private fun key(owner: String) = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val LIMIT = 540
        fun normalized(ids: List<String>): List<String> = ids.asReversed().asSequence()
            .filter { it.matches(Regex("[0-9]{1,30}")) && it.any { digit -> digit != '0' } }
            .distinct().take(LIMIT).toList().asReversed()
    }
}
