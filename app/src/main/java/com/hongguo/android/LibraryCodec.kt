package com.hongguo.android

import com.hongguo.api.util.Json

data class LibraryEntry(
    val card: DramaCard,
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
)

data class LibrarySnapshot(
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
)

/** Pure state/JSON boundary; IDs remain strings throughout persistence. */
object LibraryCodec {
    const val MAX_ENTRIES = 200
    private const val MAX_JSON_CHARS = 4 * 1024 * 1024
    private const val MAX_ID_CHARS = 128

    fun decode(json: String?): LibrarySnapshot {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS || !safeNesting(json)) return LibrarySnapshot()
        return try {
            val root = Json.optObj(Json.parse(json)) ?: return LibrarySnapshot()
            val version = root["version"]
            if (version != null && nonNegativeLong(version, -1) != 1L) return LibrarySnapshot()
            normalize(LibrarySnapshot(readEntries(root["favorites"]), readEntries(root["history"])))
        } catch (_: Exception) {
            LibrarySnapshot()
        }
    }

    fun encode(snapshot: LibrarySnapshot): String {
        val clean = normalize(snapshot)
        return Json.stringify(linkedMapOf(
            "version" to 1,
            "favorites" to clean.favorites.map(::entryObject),
            "history" to clean.history.map(::entryObject),
        ))
    }

    /** Stable newest-first order, most recent duplicate wins, and each list has its own limit. */
    fun normalize(snapshot: LibrarySnapshot): LibrarySnapshot {
        val history = normalizedEntries(snapshot.history)
        val historyById = history.associateBy { it.card.id }
        val favorites = normalizedEntries(snapshot.favorites).map { favorite ->
            val last = historyById[favorite.card.id]
            if (last == null) favorite else favorite.copy(
                episodeId = last.episodeId,
                episodeIndex = last.episodeIndex,
                positionMs = last.positionMs,
            )
        }
        return LibrarySnapshot(favorites, history)
    }

    internal fun toggleFavorite(snapshot: LibrarySnapshot, card: DramaCard, now: Long): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = normalizedCard(card) ?: return current
        if (current.favorites.any { it.card.id == clean.id }) {
            return current.copy(favorites = current.favorites.filterNot { it.card.id == clean.id })
        }
        val last = current.history.firstOrNull { it.card.id == clean.id }
        val timestamp = maxOf(now.coerceAtLeast(0), current.favorites.maxOfOrNull { it.updatedAt } ?: 0)
        val entry = LibraryEntry(clean, last?.episodeId.orEmpty(), last?.episodeIndex ?: 0,
            last?.positionMs ?: 0, timestamp)
        return normalize(current.copy(favorites = listOf(entry) + current.favorites))
    }

    /** A real first frame is the only caller event allowed to add/move history. */
    internal fun recordPlayback(
        snapshot: LibrarySnapshot,
        card: DramaCard,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        now: Long,
    ): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = normalizedCard(card) ?: return current
        val episode = id(episodeId) ?: return current
        val timestamp = maxOf(now.coerceAtLeast(0), current.history.maxOfOrNull { it.updatedAt } ?: 0)
        val entry = LibraryEntry(clean, episode, episodeIndex.coerceAtLeast(0), positionMs.coerceAtLeast(0), timestamp)
        return normalize(LibrarySnapshot(
            favorites = current.favorites.map {
                if (it.card.id != clean.id) it else entry.copy(updatedAt = it.updatedAt)
            },
            history = listOf(entry) + current.history.filterNot { it.card.id == clean.id },
        ))
    }

    /** Does not add records, change timestamps, or let an old episode replace a newer selection. */
    internal fun updatePosition(
        snapshot: LibrarySnapshot,
        card: DramaCard,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
    ): LibrarySnapshot {
        val current = normalize(snapshot)
        val cardId = id(card.id) ?: return current
        val episode = id(episodeId) ?: return current
        val index = episodeIndex.coerceAtLeast(0)
        fun update(entry: LibraryEntry): LibraryEntry =
            if (entry.card.id == cardId && entry.episodeId == episode &&
                (entry.episodeIndex == index || entry.episodeIndex == 0 || index == 0)) {
                entry.copy(positionMs = positionMs.coerceAtLeast(0))
            } else entry
        return normalize(LibrarySnapshot(current.favorites.map(::update), current.history.map(::update)))
    }

    internal fun deleteHistory(snapshot: LibrarySnapshot, cardId: String): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = id(cardId) ?: return current
        return current.copy(history = current.history.filterNot { it.card.id == clean })
    }

    internal fun clearHistory(snapshot: LibrarySnapshot): LibrarySnapshot = normalize(snapshot).copy(history = emptyList())
    internal fun clearFavorites(snapshot: LibrarySnapshot): LibrarySnapshot = normalize(snapshot).copy(favorites = emptyList())

    /** Metadata refresh never changes favorite/history order or playback state. */
    internal fun refreshCard(snapshot: LibrarySnapshot, card: DramaCard): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = normalizedCard(card) ?: return current
        fun refresh(entry: LibraryEntry) = if (entry.card.id == clean.id) entry.copy(card = clean) else entry
        return current.copy(favorites = current.favorites.map(::refresh), history = current.history.map(::refresh))
    }

    private fun readEntries(value: Any?): List<LibraryEntry> = Json.optArr(value).mapNotNull { raw ->
        val row = Json.optObj(raw) ?: return@mapNotNull null
        val card = Json.optObj(row["card"]) ?: return@mapNotNull null
        val cardId = id(card["id"]) ?: return@mapNotNull null
        val episode = if (row["episode_id"] == null || row["episode_id"] == "") "" else
            id(row["episode_id"]) ?: return@mapNotNull null
        val metadata = DramaCard(
            id = cardId,
            title = string(card["title"], 256),
            cover = string(card["cover"], 2048),
            episodeCount = nonNegativeLong(card["episode_count"]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            summary = string(card["summary"], 4000),
        )
        LibraryEntry(metadata, episode,
            nonNegativeLong(row["episode_index"]).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            nonNegativeLong(row["position_ms"]), nonNegativeLong(row["updated_at"]))
    }

    private fun normalizedEntries(entries: List<LibraryEntry>): List<LibraryEntry> {
        val byId = linkedMapOf<String, LibraryEntry>()
        entries.forEach { raw ->
            val card = normalizedCard(raw.card) ?: return@forEach
            val episode = if (raw.episodeId.isEmpty()) "" else id(raw.episodeId) ?: return@forEach
            val entry = raw.copy(
                card = card,
                episodeId = episode,
                episodeIndex = if (episode.isEmpty()) 0 else raw.episodeIndex.coerceAtLeast(0),
                positionMs = if (episode.isEmpty()) 0 else raw.positionMs.coerceAtLeast(0),
                updatedAt = raw.updatedAt.coerceAtLeast(0),
            )
            val previous = byId[card.id]
            if (previous == null || entry.updatedAt > previous.updatedAt) byId[card.id] = entry
        }
        return byId.values.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
    }

    private fun normalizedCard(card: DramaCard): DramaCard? {
        val cardId = id(card.id) ?: return null
        return card.copy(id = cardId, title = card.title.take(256), cover = card.cover.take(2048),
            episodeCount = card.episodeCount.coerceAtLeast(0), summary = card.summary.take(4000))
    }

    private fun entryObject(entry: LibraryEntry): Map<String, Any> = linkedMapOf(
        "card" to linkedMapOf(
            "id" to entry.card.id, "title" to entry.card.title, "cover" to entry.card.cover,
            "episode_count" to entry.card.episodeCount, "summary" to entry.card.summary,
        ),
        "episode_id" to entry.episodeId,
        "episode_index" to entry.episodeIndex,
        "position_ms" to entry.positionMs,
        "updated_at" to entry.updatedAt,
    )

    private fun id(value: Any?): String? {
        val text = (value as? String)?.trim() ?: return null
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS && it.none(Char::isISOControl) }
    }

    private fun string(value: Any?, limit: Int): String = (value as? String).orEmpty().take(limit)

    private fun nonNegativeLong(value: Any?, default: Long = 0): Long {
        val result = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is Double -> if (value.isFinite() && value >= 0 && value <= 9_007_199_254_740_991.0 && value % 1.0 == 0.0) value.toLong() else return default
            is Float -> if (value.isFinite() && value >= 0 && value <= 16_777_215f && value % 1f == 0f) value.toLong() else return default
            else -> return default
        }
        return if (result >= 0) result else default
    }

    /** Bound recursion before invoking the shared JSON parser on potentially damaged disk data. */
    private fun safeNesting(json: String): Boolean {
        var quoted = false
        var escaped = false
        var depth = 0
        var structures = 0
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; structures++; if (depth > 24 || structures > 4096) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return !quoted && depth == 0
    }
}
