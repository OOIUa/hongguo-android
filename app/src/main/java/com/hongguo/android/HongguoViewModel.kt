package com.hongguo.android

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import com.hongguo.api.service.ProgressiveMedia

private val mediaCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

enum class AppTab { HOME, CATEGORY, SEARCH, FAVORITES, MINE, HISTORY, ACCOUNT }

data class FeedState(
    val items: List<DramaCard> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val error: String? = null,
    val loaded: Boolean = false,
)

data class HongguoUiState(
    val tab: AppTab = AppTab.HOME,
    val homeFeed: HomeFeed = HomeFeed.RECOMMEND,
    val home: FeedState = FeedState(),
    val category: FeedState = FeedState(),
    val browseDraft: BrowseSelection = BrowseSelection(),
    val browseApplied: BrowseSelection = BrowseSelection(),
    val filterGroups: List<FilterGroup> = emptyList(),
    val filtersLoading: Boolean = false,
    val filtersError: String? = null,
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
    val libraryCloud: Boolean = false,
    val query: String = "",
    val searchedQuery: String = "",
    val results: List<DramaCard> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val searchError: String? = null,
    val selectedCard: DramaCard? = null,
    val detail: DramaDetail? = null,
    val loadingDetail: Boolean = false,
    val detailError: String? = null,
    val episodePosition: Int = -1,
    val preparing: Boolean = false,
    val preparationStage: String = "",
    val preparationDone: Long = 0,
    val preparationTotal: Long = 0,
    val video: PreparedVideo? = null,
    val playbackError: String? = null,
    val playbackStatus: String = "",
    val firstFrameRendered: Boolean = false,
    val resumePositionMs: Long = 0,
    val playerOpen: Boolean = false,
    val autoPlayNext: Boolean = true,
    val preloadNext: Boolean = true,
    val playbackSpeed: Float = 1f,
)

class HongguoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DramaRepository()
    private val library = LibraryStore(application)
    private val accounts = (application as HongguoApplication).accountRepository
    private val preferences = application.getSharedPreferences("hongguo_local", 0)
    private val mutableState = MutableStateFlow(HongguoUiState(query = preferences.getString("last_query", "").orEmpty(),
        autoPlayNext = preferences.getBoolean("auto_play_next", true), preloadNext = preferences.getBoolean("preload_next", true),
        playbackSpeed = runCatching { preferences.getFloat("playback_speed", 1f) }.getOrDefault(1f)
            .takeIf { it in PlaybackSpeeds } ?: 1f))
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var mediaJob: Job? = null
    private var prefetchJob: Job? = null
    private class WarmSlot(val episodeId: String) {
        var video: PreparedVideo? = null
        @Volatile var taken = false
        @Volatile var reader: ProgressiveMedia.Reader? = null
    }
    @Volatile private var warmSlot: WarmSlot? = null
    private var playerForeground = false
    private var resumePreparationOnForeground = false
    private var bufferReadyForPrefetch = false
    private var prefetchAttemptedFor = -1L
    private var homeJob: Job? = null
    private var categoryJob: Job? = null
    private var filtersJob: Job? = null
    private var pendingLibrary: LibraryEntry? = null
    private data class ResumeTarget(val card: DramaCard, val index: Int)
    private val resumeTargets = ConcurrentHashMap<String, ResumeTarget>()
    private data class AccountPlayback(
        val entry: LibraryEntry,
        val owner: String?,
        var rendered: Boolean = false,
        var durationMs: Long = 0,
    )
    private val accountPlaybacks = LinkedHashMap<Long, AccountPlayback>()
    @Volatile private var searchVersion = 0
    @Volatile private var detailVersion = 0
    @Volatile private var mediaVersion = 0
    private var homeVersion = 0
    private var categoryVersion = 0
    private var filtersVersion = 0
    private var searchOrigin = AppTab.HOME
    private var accountOrigin = AppTab.MINE
    private var libraryOrigin = AppTab.HOME

    fun closeSearch() = navigate(searchOrigin, rememberOrigin = false)
    fun closeAccount() = navigate(accountOrigin, rememberOrigin = false)
    fun closeLibrary() = navigate(libraryOrigin, rememberOrigin = false)
    fun selectLibrarySource(cloud: Boolean) { mutableState.update { it.copy(libraryCloud = cloud) } }

    init {
        mutableState.update { it.copy(favorites = library.state.value.favorites, history = library.state.value.history) }
        viewModelScope.launch {
            library.state.collect { snapshot -> mutableState.update {
                it.copy(favorites = snapshot.favorites, history = snapshot.history)
            } }
        }
        selectTab(AppTab.HOME)
    }

    fun selectTab(tab: AppTab) = navigate(tab, rememberOrigin = true)

    private fun navigate(tab: AppTab, rememberOrigin: Boolean) {
        if (rememberOrigin && tab == AppTab.SEARCH && state.value.tab != AppTab.SEARCH) searchOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.ACCOUNT && state.value.tab != AppTab.ACCOUNT) accountOrigin = state.value.tab
        if (rememberOrigin && tab in setOf(AppTab.HISTORY, AppTab.FAVORITES) && state.value.tab !in setOf(AppTab.HISTORY, AppTab.FAVORITES, AppTab.ACCOUNT)) {
            libraryOrigin = state.value.tab
            mutableState.update { it.copy(libraryCloud = false) }
        }
        if (state.value.selectedCard != null) backToSearch()
        mutableState.update { it.copy(tab = tab) }
        when (tab) {
            AppTab.HOME -> if (!state.value.home.loaded && !state.value.home.loading) refreshHome()
            AppTab.CATEGORY -> if (!state.value.category.loaded && !state.value.category.loading) {
                reloadFilters()
                applyBrowse()
            }
            else -> Unit
        }
    }

    fun selectHomeFeed(feed: HomeFeed) {
        if (state.value.homeFeed == feed && state.value.home.loaded) return
        mutableState.update { it.copy(homeFeed = feed) }
        refreshHome()
    }

    fun refreshHome() = requestHome(false)
    fun loadMoreHome() = requestHome(true)

    private fun requestHome(append: Boolean) {
        val current = state.value
        if (append && (current.home.loading || current.home.loadingMore || !current.home.hasMore)) return
        val version = ++homeVersion
        homeJob?.cancel()
        val previous = if (append) current.home else FeedState()
        mutableState.update { it.copy(home = previous.copy(loading = !append, loadingMore = append, error = null)) }
        homeJob = viewModelScope.launch {
            try {
                val page = repository.discover(current.homeFeed, if (append) previous.nextOffset else 0, previous.items.map { it.id })
                if (version != homeVersion || !currentCoroutineContext().isActive) return@launch
                mutableState.update { it.copy(home = mergePage(previous, page, append)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == homeVersion) mutableState.update { it.copy(home = previous.copy(
                    loading = false, loadingMore = false, loaded = true, error = readableFailure(error, "加载推荐"))) }
            }
        }
    }

    fun changeGenre(genre: String) {
        if (genre !in setOf("short_play", "comic_series", "ai_series")) return
        if (genre == state.value.browseDraft.genre) return
        mutableState.update { it.copy(browseDraft = BrowseSelection(genre), filterGroups = emptyList(), filtersError = null) }
        reloadFilters()
        applyBrowse()
    }

    fun selectFilter(key: String, value: String) {
        if (key !in setOf("theme", "setting", "background", "sort", "gender", "status", "days")) return
        mutableState.update {
            val options = it.browseDraft.options.toMutableMap()
            if (value.isBlank() || options[key] == value) options.remove(key) else options[key] = value
            it.copy(browseDraft = it.browseDraft.copy(options = options.toMap()))
        }
    }

    fun reloadFilters() {
        val genre = state.value.browseDraft.genre
        val version = ++filtersVersion
        filtersJob?.cancel()
        mutableState.update { it.copy(filtersLoading = true, filtersError = null) }
        filtersJob = viewModelScope.launch {
            try {
                val groups = repository.filters(genre)
                if (version != filtersVersion || !currentCoroutineContext().isActive) return@launch
                mutableState.update { it.copy(filterGroups = groups, filtersLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == filtersVersion) mutableState.update { it.copy(filtersLoading = false,
                    filtersError = readableFailure(error, "获取筛选条件")) }
            }
        }
    }

    fun applyBrowse() {
        mutableState.update { it.copy(browseApplied = it.browseDraft.copy(options = it.browseDraft.options.toMap())) }
        requestBrowse(false)
    }

    fun resetBrowse() {
        mutableState.update { it.copy(browseDraft = BrowseSelection(it.browseDraft.genre)) }
        applyBrowse()
    }

    fun loadMoreBrowse() {
        if (state.value.browseDraft != state.value.browseApplied) return
        requestBrowse(true)
    }

    private fun requestBrowse(append: Boolean) {
        val current = state.value
        if (append && (current.category.loading || current.category.loadingMore || !current.category.hasMore)) return
        val version = ++categoryVersion
        categoryJob?.cancel()
        val previous = if (append) current.category else FeedState()
        val selection = current.browseApplied
        mutableState.update { it.copy(category = previous.copy(loading = !append, loadingMore = append, error = null)) }
        categoryJob = viewModelScope.launch {
            try {
                val page = repository.browse(selection, if (append) previous.nextOffset else 0, previous.items.map { it.id })
                if (version != categoryVersion || !currentCoroutineContext().isActive) return@launch
                mutableState.update { it.copy(category = mergePage(previous, page, append)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == categoryVersion) mutableState.update { it.copy(category = previous.copy(
                    loading = false, loadingMore = false, loaded = true, error = readableFailure(error, "加载分类"))) }
            }
        }
    }

    private fun mergePage(previous: FeedState, page: DramaPage, append: Boolean): FeedState {
        val items = ((if (append) previous.items else emptyList()) + page.items).distinctBy { it.id }
        val advances = page.nextOffset > (if (append) previous.nextOffset else -1)
        val added = !append || items.size > previous.items.size
        return FeedState(items = items, hasMore = page.hasMore && advances && added,
            nextOffset = page.nextOffset, loaded = true)
    }

    fun toggleFavorite(card: DramaCard) {
        val detail = state.value.detail?.takeIf { it.id == card.id }
        library.toggleFavorite(if (detail == null) card else cardFor(detail))
    }
    fun removeHistory(id: String) = library.deleteHistory(id)
    fun clearFavorites() = library.clearFavorites()
    fun clearHistory() = library.clearHistory()
    fun openLibrary(entry: LibraryEntry) = openDramaInternal(entry.card, entry)
    fun setAutoPlayNext(enabled: Boolean) {
        preferences.edit().putBoolean("auto_play_next", enabled).apply()
        mutableState.update { it.copy(autoPlayNext = enabled) }
    }
    fun setPreloadNext(enabled: Boolean) {
        preferences.edit().putBoolean("preload_next", enabled).apply()
        mutableState.update { it.copy(preloadNext = enabled) }
        if (enabled) { prefetchAttemptedFor = -1; prefetchNext() } else takePrefetch(null)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed !in PlaybackSpeeds) return
        preferences.edit { putFloat("playback_speed", speed) }
        mutableState.update { it.copy(playbackSpeed = speed) }
    }

    fun updateQuery(value: String) { mutableState.update { it.copy(query = value) } }

    fun search() {
        val query = state.value.query.trim()
        if (query.isEmpty()) return
        cancelSearch()
        cancelDetailAndMedia()
        preferences.edit().putString("last_query", query).apply()
        val version = ++searchVersion
        mutableState.update { it.copy(tab = AppTab.SEARCH, query = query, searchedQuery = query,
            results = emptyList(), searching = true, loadingMore = false, hasMore = false, nextOffset = 0,
            searchError = null, selectedCard = null, detail = null, video = null, preparing = false,
            playbackError = null, firstFrameRendered = false, playerOpen = false) }
        searchJob = viewModelScope.launch {
            try {
                val page = repository.search(query, 0)
                if (version != searchVersion || !currentCoroutineContext().isActive) return@launch
                mutableState.update {
                    it.copy(results = page.items.distinctBy(DramaCard::id), searching = false,
                        hasMore = page.hasMore, nextOffset = page.nextOffset)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == searchVersion) mutableState.update {
                    it.copy(searching = false, searchError = readableFailure(error, "搜索"))
                }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        if (current.searching || current.loadingMore || !current.hasMore || current.selectedCard != null) return
        val version = ++searchVersion
        mutableState.update { it.copy(loadingMore = true, searchError = null) }
        searchJob = viewModelScope.launch {
            try {
                val page = repository.search(current.searchedQuery, current.nextOffset)
                if (version != searchVersion || !currentCoroutineContext().isActive) return@launch
                mutableState.update {
                    val merged = (it.results + page.items).distinctBy(DramaCard::id)
                    it.copy(results = merged, loadingMore = false, nextOffset = page.nextOffset,
                        hasMore = page.hasMore && page.nextOffset > current.nextOffset && merged.size > it.results.size)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == searchVersion) mutableState.update {
                    it.copy(loadingMore = false, searchError = readableFailure(error, "加载更多"))
                }
            }
        }
    }

    fun cancelSearch() {
        ++searchVersion
        searchJob?.cancel()
        mutableState.update { it.copy(searching = false, loadingMore = false) }
    }

    fun openDrama(card: DramaCard) = openDramaInternal(card, null)

    private fun openDramaInternal(card: DramaCard, resume: LibraryEntry?) {
        pendingLibrary = resume
        cancelSearch()
        cancelDetailAndMedia()
        val version = ++detailVersion
        mutableState.update {
            it.copy(selectedCard = card, detail = null, loadingDetail = true, detailError = null,
                video = null, preparing = false, preparationStage = "", playbackError = null,
                episodePosition = -1, playbackStatus = "", firstFrameRendered = false, resumePositionMs = 0, playerOpen = false)
        }
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.details(card)
                if (version != detailVersion || !currentCoroutineContext().isActive) return@launch
                val lastId = resume?.episodeId?.takeIf { it.isNotBlank() } ?: preferences.getString("${card.id}.episode", "")
                val lastIndex = resume?.episodeIndex?.takeIf { it > 0 } ?: preferences.getInt("${card.id}.index", -1)
                val selected = detail.episodes.indexOfFirst { it.id == lastId }.takeIf { it >= 0 }
                    ?: detail.episodes.indexOfFirst { it.index == lastIndex }.takeIf { it >= 0 } ?: 0
                mutableState.update { it.copy(detail = detail, loadingDetail = false, episodePosition = selected) }
                library.refreshCard(cardFor(detail))
                if (resume != null && resume.episodeIndex > 0) {
                    val target = detail.episodes[selected]
                    val position = if (target.id == resume.episodeId || target.index == resume.episodeIndex) resume.positionMs else 0
                    playEpisode(selected, resumeOverrideMs = position)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == detailVersion) mutableState.update {
                    it.copy(loadingDetail = false, detailError = readableFailure(error, "获取剧集"))
                }
            }
        }
    }

    fun retryDetails() { state.value.selectedCard?.let { openDramaInternal(it, pendingLibrary) } }

    fun playEpisode(position: Int, restart: Boolean = false, resumeOverrideMs: Long? = null) {
        val current = state.value
        val detail = current.detail ?: return
        val episode = detail.episodes.getOrNull(position) ?: return
        val warmed = takePrefetch(episode.id)
        ++mediaVersion
        mediaJob?.cancel()
        resumePreparationOnForeground = false
        bufferReadyForPrefetch = false
        prefetchAttemptedFor = -1
        val version = mediaVersion
        accountPlaybacks[version.toLong()] = AccountPlayback(
            LibraryEntry(cardFor(detail), episode.id, episode.index), accounts.ownerKey)
        while (accountPlaybacks.size > 16) accountPlaybacks.remove(accountPlaybacks.keys.first())
        resumeTargets[episode.id] = ResumeTarget(cardFor(detail), episode.index)
        val savedPosition = if (restart) 0 else resumeOverrideMs ?: preferences.getLong("${detail.id}.${episode.id}.position", 0)
        mutableState.update {
            it.copy(episodePosition = position, preparing = true, video = null,
                preparationStage = "正在获取片源", preparationDone = 0, preparationTotal = 0,
                playbackError = null, playbackStatus = "", firstFrameRendered = false, resumePositionMs = savedPosition, playerOpen = true)
        }
        releaseVideo(current.video)
        mediaJob = viewModelScope.launch {
            try {
                val prepared = warmed ?: repository.prepare(episode) { stage, done, total ->
                    if (version == mediaVersion) mutableState.update {
                        it.copy(preparationStage = stage, preparationDone = done, preparationTotal = total)
                    }
                }
                if (version != mediaVersion || !currentCoroutineContext().isActive) {
                    releaseVideo(prepared)
                    return@launch
                }
                mutableState.update { it.copy(video = prepared.copy(requestId = version.toLong()), preparing = false, playbackStatus = "正在载入播放器") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == mediaVersion) mutableState.update {
                    it.copy(preparing = false, playbackError = readableFailure(error, "准备播放"), playbackStatus = "")
                }
            }
        }
    }

    fun cancelPreparation() {
        ++mediaVersion
        mediaJob?.cancel()
        resumePreparationOnForeground = false
        takePrefetch(null)
        mutableState.update { it.copy(preparing = false, preparationStage = "", playbackStatus = "已取消，可重新选择剧集") }
    }

    fun retryPlayback() {
        val position = state.value.episodePosition
        if (position >= 0) playEpisode(position)
    }

    fun openPlayer() {
        val current = state.value
        if (current.video != null) mutableState.update { it.copy(playerOpen = true) }
        else playEpisode(current.episodePosition.coerceAtLeast(0))
    }

    fun closePlayer() {
        state.value.video?.let { accounts.flushProgress(accountPlaybacks[it.requestId]?.owner) }
        val previous = state.value.video
        ++mediaVersion
        mediaJob?.cancel()
        takePrefetch(null)
        resumePreparationOnForeground = false
        mutableState.update { it.copy(playerOpen = false, video = null, preparing = false,
            preparationStage = "", playbackError = null, playbackStatus = "", firstFrameRendered = false) }
        releaseVideo(previous)
    }

    fun onPlayerForeground(active: Boolean) {
        if (active && !playerForeground) prefetchAttemptedFor = -1
        playerForeground = active
        if (!active) {
            state.value.video?.let { accounts.flushProgress(accountPlaybacks[it.requestId]?.owner) }
            takePrefetch(null)
            if (state.value.playerOpen && state.value.preparing) {
                ++mediaVersion
                mediaJob?.cancel()
                resumePreparationOnForeground = true
                mutableState.update { it.copy(preparing = false, preparationStage = "", playbackStatus = "返回后继续播放") }
            }
        } else if (resumePreparationOnForeground && state.value.playerOpen && state.value.video == null) {
            playEpisode(state.value.episodePosition)
        } else if (state.value.firstFrameRendered) prefetchNext()
    }

    private fun prefetchNext() {
        val current = state.value
        if (!playerForeground || !bufferReadyForPrefetch || !current.playerOpen || !current.preloadNext || !current.firstFrameRendered || current.playbackError != null) return
        val requestId = current.video?.requestId ?: return
        val episode = current.detail?.episodes?.getOrNull(current.episodePosition + 1) ?: return
        if (warmSlot?.episodeId == episode.id) return
        if (prefetchAttemptedFor == requestId) return
        prefetchAttemptedFor = requestId
        takePrefetch(null)
        val slot = WarmSlot(episode.id)
        warmSlot = slot
        prefetchJob = viewModelScope.launch {
            var ready = false
            try {
                val prepared = repository.prepare(episode, allowFallback = false) { _, _, _ -> }
                slot.video = prepared
                if (warmSlot !== slot || !playerForeground) return@launch
                repository.warmNext(prepared) { reader ->
                    slot.reader = reader
                    if (reader != null && (slot.taken || warmSlot !== slot)) reader.close()
                }
                ready = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Speculative work must never interrupt the current episode. */ }
            finally {
                if (!slot.taken && (!ready || warmSlot !== slot)) {
                    releaseVideo(slot.video)
                    if (warmSlot === slot) warmSlot = null
                }
            }
        }
    }

    private fun takePrefetch(episodeId: String?): PreparedVideo? {
        val slot = warmSlot
        warmSlot = null
        if (slot != null) slot.taken = episodeId != null && slot.episodeId == episodeId && slot.video != null
        slot?.reader?.close()
        slot?.reader = null
        prefetchJob?.cancel()
        prefetchJob = null
        if (slot?.taken == true) return slot.video
        releaseVideo(slot?.video)
        return null
    }

    private fun releaseVideo(video: PreparedVideo?) {
        val session = video?.session ?: return
        mediaCleanup.launch { runCatching { session.close() } }
    }

    fun nextEpisode() {
        val current = state.value
        if (current.episodePosition < (current.detail?.episodes?.lastIndex ?: -1)) playEpisode(current.episodePosition + 1)
    }

    fun previousEpisode() {
        if (state.value.episodePosition > 0) playEpisode(state.value.episodePosition - 1)
    }

    private fun isCurrentVideo(episodeId: String, requestId: Long): Boolean =
        state.value.video?.let { it.episodeId == episodeId && it.requestId == requestId } == true

    fun onPlaybackBuffered(episodeId: String, requestId: Long, bufferedAheadMs: Long) {
        if (!isCurrentVideo(episodeId, requestId) || bufferedAheadMs < 5_000) return
        bufferReadyForPrefetch = true
        prefetchNext()
    }

    fun onPlaybackStatus(episodeId: String, requestId: Long, status: String) {
        if (isCurrentVideo(episodeId, requestId)) mutableState.update { it.copy(playbackStatus = status) }
    }

    fun onFirstFrame(episodeId: String, requestId: Long) {
        if (!isCurrentVideo(episodeId, requestId)) return
        val alreadyRendered = state.value.firstFrameRendered
        mutableState.update { it.copy(firstFrameRendered = true) }
        accountPlaybacks[requestId]?.takeIf { it.entry.episodeId == episodeId }?.rendered = true
        if (!alreadyRendered) resumeTargets[episodeId]?.let { target ->
            library.recordPlayback(target.card, episodeId, target.index, state.value.resumePositionMs)
        }
        prefetchNext()
    }

    fun onPlaybackError(episodeId: String, requestId: Long, unsupported: Boolean) {
        if (isCurrentVideo(episodeId, requestId)) mutableState.update {
            it.copy(playbackError = if (unsupported) "当前设备无法解码这集视频，可重试或选择其他集。" else "视频播放失败，可重试或选择其他集。",
                playbackStatus = "播放失败")
        }
    }

    fun onPlaybackEnded(episodeId: String, requestId: Long, durationMs: Long = 0) {
        val current = state.value
        if (!isCurrentVideo(episodeId, requestId)) return
        val playback = accountPlaybacks[requestId]
        if (durationMs > 0 && playback != null) playback.durationMs = durationMs
        if (playback?.rendered == true && playback.durationMs > 0) {
            accounts.reportProgress(playback.entry.copy(positionMs = playback.durationMs,
                updatedAt = System.currentTimeMillis()), playback.durationMs, true, playback.owner)
        }
        // Local replay starts at the beginning; the cloud retains the completed episode duration.
        savePosition(episodeId, 0, requestId, completed = true)
        if (current.episodePosition < (current.detail?.episodes?.lastIndex ?: -1)) {
            if (current.autoPlayNext) nextEpisode() else onPlaybackStatus(episodeId, requestId, "本集已播完，上滑观看下一集")
        } else onPlaybackStatus(episodeId, requestId, "已播完最后一集")
    }

    fun savePosition(episodeId: String, positionMs: Long, requestId: Long? = null,
                     durationMs: Long = 0, force: Boolean = false, completed: Boolean = false) {
        val current = state.value
        if (requestId != null && current.video?.episodeId == episodeId && current.video.requestId != requestId) return
        val target = resumeTargets[episodeId] ?: return
        val editor = preferences.edit().putLong("${target.card.id}.$episodeId.position", positionMs.coerceAtLeast(0))
        // The old player's disposal may follow a new selection; it must not replace that selection.
        val selected = current.detail?.episodes?.getOrNull(current.episodePosition)
        if (selected?.id == episodeId && current.detail?.id == target.card.id) {
            editor.putString("${target.card.id}.episode", episodeId).putInt("${target.card.id}.index", target.index)
        }
        editor.apply()
        library.updatePosition(target.card, episodeId, target.index, positionMs.coerceAtLeast(0))
        val playback = requestId?.let { accountPlaybacks[it] }
        if (playback != null && playback.entry.episodeId == episodeId) {
            if (durationMs > 0) playback.durationMs = durationMs
            if (playback.rendered && !completed) accounts.reportProgress(
                playback.entry.copy(positionMs = positionMs.coerceAtLeast(0), updatedAt = System.currentTimeMillis()),
                playback.durationMs, force, playback.owner)
        }
        if (current.video?.episodeId == episodeId && (requestId == null || current.video.requestId == requestId))
            mutableState.update { it.copy(resumePositionMs = positionMs.coerceAtLeast(0)) }
    }

    fun backToSearch() {
        pendingLibrary = null
        cancelDetailAndMedia()
        mutableState.update { it.copy(selectedCard = null, detail = null, loadingDetail = false, detailError = null,
            preparing = false, video = null, playbackError = null, episodePosition = -1, playbackStatus = "", firstFrameRendered = false, playerOpen = false) }
    }

    private fun cancelDetailAndMedia() {
        ++detailVersion
        ++mediaVersion
        detailJob?.cancel()
        mediaJob?.cancel()
        takePrefetch(null)
        resumePreparationOnForeground = false
        releaseVideo(state.value.video)
    }

    private fun cardFor(detail: DramaDetail) = DramaCard(detail.id, detail.title, detail.cover,
        detail.episodes.size, detail.summary)

    override fun onCleared() {
        cancelSearch()
        cancelDetailAndMedia()
        homeJob?.cancel()
        categoryJob?.cancel()
        filtersJob?.cancel()
        super.onCleared()
    }
}
