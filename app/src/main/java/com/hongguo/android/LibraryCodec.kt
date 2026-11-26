package com.hongguo.android

import com.hongguo.api.util.Json

private val placeholderTitle = Regex("剧集 [0-9]+")

data class LibraryEntry(
    val card: DramaCard,
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
    val completed: Boolean = false,
) {
    val seriesCompleted: Boolean get() = completed && card.episodeCount > 0 && episodeIndex >= card.episodeCount
    internal val hasKnownTitle: Boolean get() = card.title.isNotBlank() && !placeholderTitle.matches(card.title)

    internal fun backfillCard(previous: LibraryEntry): LibraryEntry = copy(card = card.copy(
        title = if (!hasKnownTitle && previous.hasKnownTitle) previous.card.title else card.title,
        cover = card.cover.ifBlank { previous.card.cover },
        summary = card.summary.ifBlank { previous.card.summary },
        episodeCount = if (card.episodeCount > 0) card.episodeCount else previous.card.episodeCount,
    ))
}

data class LibrarySnapshot(
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
    val storageError: String? = null,
    val historyHiddenThrough: Map<String, Long> = emptyMap(),
    val historyClearedThrough: Long? = null,
)

data class ChaseFavorite(val entry: LibraryEntry, val onDevice: Boolean, val inAccount: Boolean)

/** Pure state/JSON boundary; IDs remain strings throughout persistence. */
object LibraryCodec {
    const val MAX_ENTRIES = 200
    private const val MAX_JSON_CHARS = 4 * 1024 * 1024
    private const val MAX_ID_CHARS = 128

    fun decode(json: String?): LibrarySnapshot {
        return try { decodeChecked(json, strictLists = false) } catch (_: Exception) { LibrarySnapshot() }
    }

    /** Storage callers must distinguish an empty library from unreadable data before allowing writes. */
    fun decodeChecked(json: String?, strictLists: Boolean = true): LibrarySnapshot {
        if (json == null) return LibrarySnapshot()
        require(json.isNotBlank() && json.length <= MAX_JSON_CHARS && safeNesting(json))
        val root = Json.optObj(Json.parse(json)) ?: error("Invalid library root")
        val version = root["version"]
        require(version == null || nonNegativeLong(version, -1) == 1L)
        if (strictLists) {
            require(root.containsKey("favorites") || root.containsKey("history"))
            for (key in listOf("favorites", "history")) require(!root.containsKey(key) || root[key] is List<*>)
        }
        val hidden = root["history_hidden_through"]?.let { raw ->
            require(raw is Map<*, *>)
            raw.entries.associate { (key, value) ->
                requireNotNull(id(key)) to nonNegativeLong(value, -1).also { require(it >= 0) }
            }
        }.orEmpty()
        val cleared = root["history_cleared_through"]?.let { nonNegativeLong(it, -1).also { value -> require(value >= 0) } }
        return normalize(LibrarySnapshot(readEntries(root["favorites"]), readEntries(root["history"]),
            historyHiddenThrough = hidden, historyClearedThrough = cleared))
    }

    fun encode(snapshot: LibrarySnapshot): String {
        val clean = normalize(snapshot)
        return Json.stringify(linkedMapOf<String, Any>(
            "version" to 1,
            "favorites" to clean.favorites.map(::entryObject),
            "history" to clean.history.map(::entryObject),
        ).apply {
            if (clean.historyHiddenThrough.isNotEmpty()) put("history_hidden_through", clean.historyHiddenThrough)
            clean.historyClearedThrough?.let { put("history_cleared_through", it) }
        })
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
                completed = last.completed,
            )
        }
        return snapshot.copy(favorites = favorites, history = history)
    }

    /** Collection dates order the unified shelf. Only a device favorite may carry a fallback playback checkpoint. */
    fun mergedFavorites(device: List<LibraryEntry>, cloud: List<LibraryEntry>): List<ChaseFavorite> {
        val byId = latestHistory(device).associateTo(linkedMapOf()) {
            it.card.id to ChaseFavorite(it, onDevice = true, inAccount = false)
        }
        for (remote in cloud) {
            val id = remote.card.id
            val existing = byId[id]
            val previous = existing?.entry
            val entry = when {
                previous == null -> LibraryEntry(remote.card, updatedAt = remote.updatedAt)
                previous.updatedAt >= remote.updatedAt -> previous.backfillCard(remote)
                else -> previous.copy(card = remote.backfillCard(previous).card, updatedAt = remote.updatedAt)
            }
            byId[id] = ChaseFavorite(entry, onDevice = existing?.onDevice == true, inAccount = true)
        }
        return byId.values.sortedByDescending { it.entry.updatedAt }
    }

    /** Cloud rows are only visible alongside the matching signed-in account's local snapshot. */
    fun mergedHistory(device: LibrarySnapshot, account: LibrarySnapshot? = null, cloud: List<LibraryEntry> = emptyList()): List<LibraryEntry> {
        if (account == null) return latestHistory(device.history)
        // Removing a row also erases the scoped local copy. Any later local first frame is a new watch,
        // including when the removed cloud record came from a device with a clock in the future.
        val visibleOtherSources = (device.history + cloud).filter { entry ->
            entry.updatedAt > hiddenThrough(account, entry.card.id)
        }
        return latestHistory(account.history + visibleOtherSources)
    }

    /** Positions come from a single observation; older copies may supply a better title or cover. */
    fun latestHistory(entries: List<LibraryEntry>): List<LibraryEntry> {
        val newest = linkedMapOf<String, LibraryEntry>()
        for (entry in entries.sortedByDescending { it.updatedAt }) {
            val id = entry.card.id
            val first = newest[id]
            if (first == null) {
                newest[id] = entry
                continue
            }
            val merged = first.backfillCard(entry)
            // Old caches omit completion. A confirmed duplicate of the same observation can supply it.
            val completed = entry.completed && entry.episodeId.isNotEmpty() && entry.episodeId == first.episodeId &&
                entry.updatedAt == first.updatedAt && entry.positionMs == first.positionMs
            newest[id] = if (completed && !merged.completed) merged.copy(completed = true) else merged
        }
        return newest.values.toList()
    }

    private fun hiddenThrough(snapshot: LibrarySnapshot, cardId: String): Long =
        maxOf(snapshot.historyClearedThrough ?: -1, snapshot.historyHiddenThrough[cardId] ?: -1)

    internal fun toggleFavorite(snapshot: LibrarySnapshot, card: DramaCard, now: Long): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = normalizedCard(card) ?: return current
        if (current.favorites.any { it.card.id == clean.id }) {
            return current.copy(favorites = current.favorites.filterNot { it.card.id == clean.id })
        }
        val last = current.history.firstOrNull { it.card.id == clean.id }
        val timestamp = maxOf(now.coerceAtLeast(0), current.favorites.maxOfOrNull { it.updatedAt } ?: 0)
        val entry = LibraryEntry(clean, last?.episodeId.orEmpty(), last?.episodeIndex ?: 0,
            last?.positionMs ?: 0, timestamp, last?.completed ?: false)
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
        val timestamp = maxOf(now.coerceAtLeast(0), current.history.firstOrNull()?.updatedAt ?: 0)
        val entry = LibraryEntry(clean, episode, episodeIndex.coerceAtLeast(0), positionMs.coerceAtLeast(0), timestamp)
        return normalize(current.copy(
            favorites = current.favorites.map {
                if (it.card.id != clean.id) it else entry.copy(updatedAt = it.updatedAt)
            },
            history = listOf(entry) + current.history.filterNot { it.card.id == clean.id },
        ))
    }

    /** Only the matching episode can advance the last actual watch time; favorites keep their collection times. */
    internal fun updatePosition(
        snapshot: LibrarySnapshot,
        card: DramaCard,
        episodeId: String,
        episodeIndex: Int,
        positionMs: Long,
        now: Long? = null,
        completed: Boolean = false,
    ): LibrarySnapshot {
        val current = normalize(snapshot)
        val cardId = id(card.id) ?: return current
        val episode = id(episodeId) ?: return current
        val index = episodeIndex.coerceAtLeast(0)
        fun update(entry: LibraryEntry, history: Boolean): LibraryEntry =
            if (entry.card.id == cardId && entry.episodeId == episode &&
                (entry.episodeIndex == index || entry.episodeIndex == 0 || index == 0)) {
                val position = positionMs.coerceAtLeast(0)
                if (entry.completed || (entry.positionMs == position && !completed)) entry
                else entry.copy(positionMs = position, completed = completed,
                    updatedAt = if (history && now != null) maxOf(entry.updatedAt, now) else entry.updatedAt)
            } else entry
        return normalize(current.copy(favorites = current.favorites.map { update(it, false) }, history = current.history.map { update(it, true) }))
    }

    internal fun deleteHistory(snapshot: LibrarySnapshot, cardId: String): LibrarySnapshot {
        val current = normalize(snapshot)
        val clean = id(cardId) ?: return current
        return current.copy(history = current.history.filterNot { it.card.id == clean })
    }

    internal fun clearHistory(snapshot: LibrarySnapshot): LibrarySnapshot = normalize(snapshot).copy(history = emptyList())

    internal fun hideHistory(snapshot: LibrarySnapshot, entry: LibraryEntry, now: Long): LibrarySnapshot {
        val current = deleteHistory(snapshot, entry.card.id)
        val cutoff = maxOf(now, entry.updatedAt, hiddenThrough(current, entry.card.id))
        return current.copy(historyHiddenThrough = current.historyHiddenThrough + (entry.card.id to cutoff))
    }

    internal fun hideAllHistory(snapshot: LibrarySnapshot, visible: List<LibraryEntry>, now: Long): LibrarySnapshot {
        val cutoff = maxOf(now, snapshot.historyClearedThrough ?: 0)
        // Rows from a device whose clock is ahead need their own cutoff.
        val hidden = snapshot.historyHiddenThrough.filterValues { it > cutoff }.toMutableMap()
        for (entry in visible) {
            if (entry.updatedAt > cutoff) hidden[entry.card.id] = maxOf(entry.updatedAt, hidden[entry.card.id] ?: 0)
        }
        return clearHistory(snapshot).copy(historyClearedThrough = cutoff, historyHiddenThrough = hidden)
    }
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
            nonNegativeLong(row["position_ms"]), nonNegativeLong(row["updated_at"]), row["completed"] == true)
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
                completed = episode.isNotEmpty() && raw.completed,
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
    ).apply { if (entry.completed) put("completed", true) }

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
