package com.hongguo.android

import com.hongguo.account.protocol.RankBoard
import com.hongguo.account.protocol.RankEntry
import com.hongguo.account.protocol.RelatedWork
import com.hongguo.account.protocol.RelatedWorks
import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountSessionStatus
import com.hongguo.android.comments.SeriesReviewApi
import com.hongguo.android.comments.SeriesReviewPage
import com.hongguo.android.danmaku.DanmakuPrefs
import com.hongguo.android.danmaku.DanmakuSettings

private val mediaCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal data class HistoryResume(val episodePosition: Int, val positionMs: Long, val seriesEnded: Boolean)

internal fun historyResume(episodes: List<Episode>, entry: LibraryEntry): HistoryResume? {
    val byId = episodes.indexOfFirst { it.id == entry.episodeId }
    val position = if (byId >= 0) byId else episodes.indexOfFirst { entry.episodeIndex > 0 && it.index == entry.episodeIndex }
    if (position < 0) return null
    if (!entry.completed) return HistoryResume(position, entry.positionMs, false)
    return HistoryResume((position + 1).coerceAtMost(episodes.lastIndex), 0, position == episodes.lastIndex)
}

internal enum class EpisodeWatchState {
    WATCHING, COMPLETED, PLAYING_AGAIN;

    fun onPlaying(): EpisodeWatchState = if (this == COMPLETED) PLAYING_AGAIN else this

    fun canResumeAt(positionMs: Long, durationMs: Long): Boolean =
        this == PLAYING_AGAIN && positionMs >= 0 && (durationMs <= 0 || positionMs < durationMs)
}

/** SETTINGS (软件设置), ABOUT (关于软件) and settings sub-pages have no bottom bar and return to their origin. */
enum class AppTab {
    HOME, CATEGORY, SEARCH, FAVORITES, MINE, HISTORY, ACCOUNT, SETTINGS, THEME_STYLE,
    PLAYBACK_SETTINGS, ACCOUNT_SETTINGS, STORAGE_SETTINGS, ABOUT, RANK, PLAY_HISTORY, LOCAL_FAVORITES,
}

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
    val accountPageVisit: Long = 0,
    val homeFeed: HomeFeed = HomeFeed.GUESS,
    val home: FeedState = FeedState(),
    val category: FeedState = FeedState(),
    val browseDraft: BrowseSelection = BrowseSelection(),
    val browseApplied: BrowseSelection = BrowseSelection(),
    val filterGroups: List<FilterGroup> = emptyList(),
    val filtersLoading: Boolean = false,
    val filtersError: String? = null,
    val favorites: List<LibraryEntry> = emptyList(),
    val chaseFavorites: List<ChaseFavorite> = favorites.map { ChaseFavorite(it, onDevice = true, inAccount = false) },
    val chaseFavoritesError: String? = null,
    val libraryError: String? = null,
    val history: List<LibraryEntry> = emptyList(),
    val historyOwnerId: String? = null,
    val historyError: String? = null,
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
    val reviewFirstPage: SeriesReviewPage? = null,
    /** Other seasons and same-IP works of the selected drama; empty when unavailable. */
    val related: List<RelatedWork> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val rankBoard: RankBoard = RankBoard.ALL,
    val rankEntries: List<RankEntry> = emptyList(),
    val actorRankSelected: Boolean = false,
    val actorRankBoard: ActorRankBoard = ActorRankBoard.DRAMA_STARS,
    val actorRankEntries: List<ActorRankEntry> = emptyList(),
    val actorRankCursor: ActorRankCursor? = null,
    val rankPage: Int = 0,
    val rankTotalPages: Int = 0,
    val rankLoading: Boolean = false,
    val rankError: String? = null,
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
    /** The detail page was opened from the player's title: back returns to the player, which kept its episode. */
    val detailReturnsToPlayer: Boolean = false,
    val autoPlayNext: Boolean = true,
    val preloadNext: Boolean = true,
    val autoPictureInPicture: Boolean = false,
    val playbackSpeed: Float = 1f,
    /** 弹幕 rendering preferences; the per-episode comments themselves live in the player layer. */
    val danmaku: DanmakuSettings = DanmakuSettings(),
    val themeStyle: AppThemeStyle = AppThemeStyle.IMMERSIVE,
    val themeMode: ThemeMode? = null,
    val navBarStyle: NavBarStyle = NavBarStyle.CLASSIC,
    val themeOnboardingPending: Boolean = false,
    /** Confirmation after the first-launch picker; cleared only once the snackbar has been shown. */
    val themeHint: String? = null,
) {
    val hasMoreRanks: Boolean
        get() = if (actorRankSelected) actorRankCursor != null else rankPage < rankTotalPages
    val reviewCount: Int? get() = reviewFirstPage?.total
}

/** A library click can outlive its account's list. Only currently visible positions can be resumed. */
internal fun HongguoUiState.libraryResumeFor(cardId: String): LibraryEntry? =
    history.firstOrNull { it.card.id == cardId } ?: favorites.firstOrNull { it.card.id == cardId }

class HongguoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DramaRepository()
    private val library = LibraryStore(application)
    private val accounts = (application as HongguoApplication).accountRepository
    private val preferences = application.getSharedPreferences(ThemePreferences.FILE, 0)
    private val mutableState = MutableStateFlow(HongguoUiState(query = preferences.getString("last_query", "").orEmpty(),
        autoPlayNext = preferences.getBoolean("auto_play_next", true), preloadNext = preferences.getBoolean("preload_next", true),
        autoPictureInPicture = preferences.getBoolean("auto_picture_in_picture", false),
        playbackSpeed = runCatching { preferences.getFloat("playback_speed", 1f) }.getOrDefault(1f)
            .takeIf { it in PlaybackSpeeds } ?: 1f,
        danmaku = DanmakuSettings(
            enabled = preferences.getBoolean("danmaku_enabled", true),
            // 屏占比现在按千分数落盘（200/250/333/500/1000），没存过时从旧的下标键换算。
            coverage = preferences.getInt("danmaku_coverage_permille", 0).let { permille ->
                if (permille > 0) DanmakuPrefs.coverageFromPermille(permille)
                else DanmakuPrefs.coverageFromLegacy(
                    preferences.getInt("danmaku_coverage", DanmakuPrefs.COVERAGE_LEGACY_DEFAULT))
            },
            font = preferences.getInt("danmaku_font", DanmakuPrefs.FONT_DEFAULT),
            opacity = preferences.getInt("danmaku_opacity", DanmakuPrefs.OPACITY_DEFAULT),
            effect = preferences.getInt("danmaku_effect", DanmakuPrefs.EFFECT_DEFAULT),
            speed = preferences.getInt("danmaku_speed", DanmakuPrefs.SPEED_DEFAULT),
            // 档位顺序改过（稀疏在前），旧键的值含义已不同，直接换新键从默认档开始。
            density = preferences.getInt("danmaku_density_v2", DanmakuPrefs.DENSITY_DEFAULT),
        ).normalized(),
        themeStyle = AppThemeStyle.fromKey(preferences.getString(ThemePreferences.STYLE, null)),
        themeMode = ThemeMode.stored(preferences.getString(ThemePreferences.MODE, null),
            preferences.getBoolean(ThemePreferences.DARK_FOLLOWS_SYSTEM, false)),
        navBarStyle = NavBarStyle.resolve(preferences.getString(ThemePreferences.NAV_BAR_STYLE, null), SystemRom.isHyperOs),
        themeOnboardingPending = !preferences.getBoolean(ThemePreferences.ONBOARDING_DONE, false) ||
            preferences.getInt(ThemePreferences.ONBOARDING_VERSION, 1) < ThemePreferences.CURRENT_ONBOARDING_VERSION))
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var mediaJob: Job? = null
    private var nextPreload: EpisodePreload? = null
    private var playerForeground = false
    private var resumePreparationOnForeground = false
    private var bufferReadyForPrefetch = false
    private var prefetchAttemptedFor = -1L
    private var homeJob: Job? = null
    private var guessPager: GuessFeedPager? = null
    private val recommendationHistory = RecommendationHistory(application.filesDir.resolve("recommendations"))
    private var relatedJob: Job? = null
    private var categoryJob: Job? = null
    private var filtersJob: Job? = null
    private var pendingLibrary: LibraryEntry? = null
    private var pendingAutoPlay = false
    private var detailHistoryOwner: String? = null
    private data class AccountPlayback(
        val entry: LibraryEntry,
        val historyOwner: String?,
        val store: LibraryStore,
        val owner: String?,
        var rendered: Boolean = false,
        var watchState: EpisodeWatchState = EpisodeWatchState.WATCHING,
        var durationMs: Long = 0,
    )
    private val accountPlaybacks = LinkedHashMap<Long, AccountPlayback>()
    private var lastRenderedRequest: Long? = null
    private data class AccountLibrary(
        val owner: String?, val history: List<LibraryEntry>, val favorites: List<LibraryEntry>,
        val historyError: String?, val favoritesError: String?,
    )
    private var lastLibraryRefreshOwner: String? = null
    private var lastLibraryRefreshAt = 0L
    @Volatile private var searchVersion = 0
    @Volatile private var detailVersion = 0
    @Volatile private var mediaVersion = 0
    private var homeVersion = 0
    private var categoryVersion = 0
    private var filtersVersion = 0
    private var searchOrigin = AppTab.HOME
    private var rankOrigin = AppTab.HOME
    private var librarySourceChosen = false
    private var rankJob: Job? = null
    private var rankVersion = 0L
    private var suggestionJob: Job? = null
    private var accountOrigin = AppTab.MINE
    private var libraryOrigin = AppTab.HOME
    private var myLibraryOrigin = AppTab.MINE
    private var settingsOrigin = AppTab.MINE
    private var themeOrigin = AppTab.SETTINGS
    private var playbackSettingsOrigin = AppTab.SETTINGS
    private var accountSettingsOrigin = AppTab.SETTINGS
    private var storageSettingsOrigin = AppTab.SETTINGS
    private var aboutOrigin = AppTab.MINE

    fun closeSearch() = navigate(searchOrigin, rememberOrigin = false)
    fun closeRank() = navigate(rankOrigin, rememberOrigin = false)

    fun openRank() {
        selectTab(AppTab.RANK)
        if (state.value.rankPage == 0 && !state.value.rankLoading) loadRank()
    }

    fun selectRankBoard(board: RankBoard) = changeRankBoard(board, actors = false)

    fun selectActorRank() = selectActorRankBoard(state.value.actorRankBoard)

    fun selectActorRankBoard(board: ActorRankBoard) = changeRankBoard(state.value.rankBoard, actors = true, actorBoard = board)

    private fun changeRankBoard(board: RankBoard, actors: Boolean, actorBoard: ActorRankBoard = state.value.actorRankBoard) {
        val current = state.value
        if (board == current.rankBoard && actors == current.actorRankSelected && actorBoard == current.actorRankBoard) return
        mutableState.update {
            it.copy(rankBoard = board, actorRankSelected = actors, actorRankBoard = actorBoard, rankEntries = emptyList(), actorRankEntries = emptyList(),
                actorRankCursor = null, rankPage = 0, rankTotalPages = 0, rankLoading = true, rankError = null)
        }
        loadRank()
    }

    fun loadMoreRank() {
        val current = state.value
        if (!current.rankLoading && current.rankError == null && current.hasMoreRanks) loadRank()
    }

    fun retryRank() {
        if (!state.value.rankLoading) loadRank()
    }

    fun searchRankActor(name: String) {
        selectTab(AppTab.SEARCH)
        pickSuggestion(name)
    }

    private fun loadRank() {
        val current = state.value
        rankJob?.cancel()
        val version = ++rankVersion
        mutableState.update { it.copy(rankLoading = true, rankError = null) }
        rankJob = viewModelScope.launch {
            try {
                if (current.actorRankSelected) {
                    val result = repository.actorRank(current.actorRankBoard, current.actorRankCursor)
                    if (version != rankVersion) return@launch
                    val entries = (current.actorRankEntries + result.entries).distinctBy(ActorRankEntry::id)
                    mutableState.update {
                        it.copy(rankLoading = false, rankPage = current.rankPage + 1, actorRankEntries = entries,
                            actorRankCursor = result.next.takeIf { entries.size > current.actorRankEntries.size })
                    }
                } else {
                    val result = repository.rank(current.rankBoard, current.rankPage + 1)
                    if (version != rankVersion) return@launch
                    mutableState.update {
                        it.copy(rankLoading = false, rankPage = result.page, rankTotalPages = result.totalPages,
                            rankEntries = (it.rankEntries + result.entries).distinctBy { row -> row.series.id })
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == rankVersion) mutableState.update { it.copy(rankLoading = false, rankError = readableFailure(error, "加载榜单")) }
            }
        }
    }
    fun closeAccount() = navigate(accountOrigin, rememberOrigin = false)
    fun closeLibrary() = navigate(libraryOrigin, rememberOrigin = false)
    fun openAccountSection(section: AccountSection) {
        accounts.selectSection(section)
        mutableState.update { it.copy(accountPageVisit = it.accountPageVisit + 1) }
        selectTab(AppTab.ACCOUNT)
    }
    /** 播放历史 is a page of where it was opened from, so back returns there; it is not the 追剧 tab. */
    fun openPlayHistory() = selectTab(AppTab.PLAY_HISTORY)
    fun closeMyLibrary() = navigate(myLibraryOrigin, rememberOrigin = false)
    fun closeSettings() = navigate(settingsOrigin, rememberOrigin = false)
    fun closeThemeStyle() = navigate(themeOrigin, rememberOrigin = false)
    fun closePlaybackSettings() = navigate(playbackSettingsOrigin, rememberOrigin = false)
    fun closeAccountSettings() = navigate(accountSettingsOrigin, rememberOrigin = false)
    fun closeStorageSettings() = navigate(storageSettingsOrigin, rememberOrigin = false)
    fun closeAbout() = navigate(aboutOrigin, rememberOrigin = false)
    /** TV offers separate device and cloud shelves. */
    fun selectLibrarySource(cloud: Boolean) {
        librarySourceChosen = true
        mutableState.update { it.copy(libraryCloud = cloud) }
    }

    /** A signed-in account's records come first, until the user picks a source themself. Signing out resets this. */
    fun onAccountSession(signedIn: Boolean) {
        if (!signedIn) librarySourceChosen = false
        if (!librarySourceChosen) mutableState.update { it.copy(libraryCloud = signedIn) }
    }

    init {
        viewModelScope.launch {
            accounts.state.map { it.profile?.id }.distinctUntilChanged().collectLatest { owner ->
                if (state.value.selectedCard != null && detailHistoryOwner != owner) backToSearch()
                val personal = owner?.let { library.forHistoryOwner(it).state } ?: flowOf(null)
                val accountLibrary = accounts.state.map {
                    AccountLibrary(it.profile?.id, it.history, it.favorites, it.historyError, it.favoritesError)
                }.distinctUntilChanged()
                combine(library.state, personal, accountLibrary) { device, scoped, account ->
                    if (account.owner == owner && accounts.state.value.profile?.id == owner) {
                        val history = LibraryCodec.mergedHistory(device, scoped, account.history)
                        val chaseFavorites = LibraryCodec.mergedFavorites(device.favorites, if (owner == null) emptyList() else account.favorites)
                        mutableState.update {
                            it.copy(favorites = device.favorites, chaseFavorites = chaseFavorites,
                                chaseFavoritesError = account.favoritesError.takeIf { owner != null }, history = history,
                                historyOwnerId = owner, historyError = account.historyError,
                                libraryError = device.storageError ?: scoped?.storageError)
                        }
                    }
                }.collect {}
            }
        }
        viewModelScope.launch {
            // Login, logout or switching account changes whose feed 猜你喜欢 shows.
            accounts.state.map { accounts.ownerKey }.distinctUntilChanged().collect { owner ->
                val pager = guessPager
                if (state.value.homeFeed == HomeFeed.GUESS && pager != null && pager.owner != owner) refreshHome()
            }
        }
        selectTab(AppTab.HOME)
    }

    fun selectTab(tab: AppTab) = navigate(tab, rememberOrigin = true)

    private fun navigate(tab: AppTab, rememberOrigin: Boolean) {
        if (rememberOrigin && tab == AppTab.SEARCH && state.value.tab != AppTab.SEARCH) searchOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.RANK && state.value.tab != AppTab.RANK) rankOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.ACCOUNT && state.value.tab != AppTab.ACCOUNT) {
            if (!(state.value.tab == AppTab.ACCOUNT_SETTINGS && accountSettingsOrigin == AppTab.ACCOUNT)) {
                accountOrigin = state.value.tab
            }
        }
        if (rememberOrigin && tab == AppTab.SETTINGS && state.value.tab != AppTab.SETTINGS) settingsOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.THEME_STYLE && state.value.tab != AppTab.THEME_STYLE) themeOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.PLAYBACK_SETTINGS && state.value.tab != AppTab.PLAYBACK_SETTINGS) playbackSettingsOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.ACCOUNT_SETTINGS && state.value.tab != AppTab.ACCOUNT_SETTINGS) {
            if (!(state.value.tab == AppTab.ACCOUNT && accountOrigin == AppTab.ACCOUNT_SETTINGS)) {
                accountSettingsOrigin = state.value.tab
            }
        }
        if (rememberOrigin && tab == AppTab.STORAGE_SETTINGS && state.value.tab != AppTab.STORAGE_SETTINGS) storageSettingsOrigin = state.value.tab
        if (rememberOrigin && tab == AppTab.ABOUT && state.value.tab != AppTab.ABOUT) aboutOrigin = state.value.tab
        if (rememberOrigin && tab in setOf(AppTab.PLAY_HISTORY, AppTab.LOCAL_FAVORITES) && state.value.tab !in setOf(AppTab.PLAY_HISTORY, AppTab.LOCAL_FAVORITES)) myLibraryOrigin = state.value.tab
        if (rememberOrigin && tab in setOf(AppTab.HISTORY, AppTab.FAVORITES) && state.value.tab !in setOf(AppTab.HISTORY, AppTab.FAVORITES, AppTab.ACCOUNT)) libraryOrigin = state.value.tab
        if (state.value.selectedCard != null) backToSearch()
        mutableState.update { it.copy(tab = tab) }
        when (tab) {
            AppTab.HOME -> if (!state.value.home.loaded && !state.value.home.loading) refreshHome()
            AppTab.PLAY_HISTORY, AppTab.HISTORY, AppTab.FAVORITES -> {
                val account = accounts.state.value
                val now = android.os.SystemClock.elapsedRealtime()
                val cloudPage = tab == AppTab.FAVORITES && state.value.libraryCloud
                if (!cloudPage && account.sessionStatus == AccountSessionStatus.LOGGED_IN && !account.busy &&
                    (account.profile?.id != lastLibraryRefreshOwner || now - lastLibraryRefreshAt >= 60_000)) {
                    lastLibraryRefreshOwner = account.profile?.id
                    lastLibraryRefreshAt = now
                    accounts.refresh()
                }
            }
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
        val guess = current.homeFeed == HomeFeed.GUESS
        val owner = if (guess) accounts.ownerKey else null
        if (append && (current.home.loading || current.home.loadingMore || !current.home.hasMore)) return
        val continuing = append && (!guess || guessPager?.owner == owner)
        val version = ++homeVersion
        homeJob?.cancel()
        val previous = if (continuing) current.home else FeedState()
        mutableState.update { it.copy(home = previous.copy(loading = !continuing, loadingMore = continuing, error = null)) }
        homeJob = viewModelScope.launch {
            val home = try {
                val page = if (guess) guessPage(continuing, owner)
                    else repository.discover(current.homeFeed, previous.nextOffset, previous.items.map { it.id })
                mergePage(previous, page, continuing)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                previous.copy(loading = false, loadingMore = false, loaded = true,
                    error = readableFailure(error, if (guess) "加载推荐" else "加载首页"))
            }
            if (version != homeVersion || !currentCoroutineContext().isActive) return@launch
            if (guess && owner != accounts.ownerKey) refreshHome()
            else mutableState.update { it.copy(home = home) }
        }
    }

    /** Signed in: the account's own feed; otherwise the anonymous one. A changed owner restarts the round. */
    private suspend fun guessPage(append: Boolean, owner: String?): DramaPage {
        val pager = guessPager?.takeIf { append && it.owner == owner }
            ?: GuessFeedPager(owner, accounts::recommendations, previous = guessPager, history = recommendationHistory,
                historyKey = accounts.recommendationHistoryKey(owner)).also { guessPager = it }
        val page = pager.next()
        // 重建分页仍只回到已看过的作品时直接说明，不把旧批次当新推荐重播。
        if (!append && page.items.isEmpty() && pager.rememberedCount > 0)
            throw NoNewRecommendationsException(pager.rememberedCount)
        return page
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

    /** Drops unapplied filter choices (e.g. a filter sheet dismissed without 查看结果). */
    fun discardBrowseDraft() {
        mutableState.update {
            if (it.browseDraft == it.browseApplied) it
            else it.copy(browseDraft = it.browseApplied.copy(options = it.browseApplied.options.toMap()))
        }
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
    fun removeHistory(id: String) {
        val view = state.value
        if (view.historyOwnerId != accounts.state.value.profile?.id) return
        val entry = view.history.firstOrNull { it.card.id == id } ?: return
        library.forHistoryOwner(view.historyOwnerId).deleteHistory(entry)
    }
    fun reloadLibrary() {
        library.reload()
        val account = accounts.state.value
        account.profile?.id?.let { library.forHistoryOwner(it).reload() }
        if (account.profile != null && !account.busy) accounts.refresh()
    }
    fun clearFavorites() = library.clearFavorites()
    fun removeLocalFavorite(id: String) = library.removeFavorite(id)
    fun clearHistory() {
        val view = state.value
        if (view.historyOwnerId != accounts.state.value.profile?.id) return
        library.forHistoryOwner(view.historyOwnerId).clearHistory(view.history)
    }
    fun openLibrary(entry: LibraryEntry) {
        val view = state.value
        if (view.historyOwnerId != accounts.state.value.profile?.id) return
        openDramaInternal(entry.card, view.libraryResumeFor(entry.card.id))
    }
    fun setAutoPlayNext(enabled: Boolean) {
        preferences.edit().putBoolean("auto_play_next", enabled).apply()
        mutableState.update { it.copy(autoPlayNext = enabled) }
    }
    fun setPreloadNext(enabled: Boolean) {
        preferences.edit().putBoolean("preload_next", enabled).apply()
        mutableState.update { it.copy(preloadNext = enabled) }
        if (enabled) { prefetchAttemptedFor = -1; prefetchNext() } else discardPrefetch()
    }
    fun setAutoPictureInPicture(enabled: Boolean) {
        preferences.edit { putBoolean("auto_picture_in_picture", enabled) }
        mutableState.update { it.copy(autoPictureInPicture = enabled) }
    }

    fun setThemeStyle(style: AppThemeStyle) {
        preferences.edit { putString(ThemePreferences.STYLE, style.key) }
        mutableState.update { it.copy(themeStyle = style) }
    }
    fun setNavBarStyle(style: NavBarStyle) {
        preferences.edit { putString(ThemePreferences.NAV_BAR_STYLE, style.key) }
        mutableState.update { it.copy(navBarStyle = style) }
    }
    fun setThemeMode(mode: ThemeMode) {
        preferences.edit { putString(ThemePreferences.MODE, mode.key) }
        mutableState.update { it.copy(themeMode = mode) }
    }
    /** Answers the first-launch picker. A null [choice] keeps the current style ("暂不选择"). Shown only once. */
    fun completeThemeOnboarding(choice: AppThemeStyle?) {
        preferences.edit {
            if (choice != null) putString(ThemePreferences.STYLE, choice.key)
            putBoolean(ThemePreferences.ONBOARDING_DONE, true)
            putInt(ThemePreferences.ONBOARDING_VERSION, ThemePreferences.CURRENT_ONBOARDING_VERSION)
        }
        mutableState.update {
            val style = choice ?: it.themeStyle
            it.copy(themeStyle = style, themeOnboardingPending = false,
                themeHint = themeAppliedMessage(style, chosen = choice != null))
        }
    }
    fun consumeThemeHint() = mutableState.update { it.copy(themeHint = null) }

    fun setPlaybackSpeed(speed: Float) {
        if (speed !in PlaybackSpeeds) return
        preferences.edit { putFloat("playback_speed", speed) }
        mutableState.update { it.copy(playbackSpeed = speed) }
    }

    /** 弹幕开关; the panel's own switch and the player's chip both land here. */
    fun setDanmakuEnabled(enabled: Boolean) = setDanmaku(state.value.danmaku.copy(enabled = enabled))

    /** Stores every danmaku preference at once; values are clamped before they reach disk. */
    fun setDanmaku(settings: DanmakuSettings) {
        val next = settings.normalized()
        preferences.edit {
            putBoolean("danmaku_enabled", next.enabled)
            putInt("danmaku_coverage_permille", DanmakuPrefs.coveragePermille(next.coverage))
            putInt("danmaku_font", next.font)
            putInt("danmaku_opacity", next.opacity)
            putInt("danmaku_effect", next.effect)
            putInt("danmaku_speed", next.speed)
            putInt("danmaku_density_v2", next.density)
        }
        mutableState.update { it.copy(danmaku = next) }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value) }
        suggestionJob?.cancel()
        val query = value.trim()
        if (query.isEmpty() || query == state.value.searchedQuery) { mutableState.update { it.copy(suggestions = emptyList()) }; return }
        suggestionJob = viewModelScope.launch {
            delay(250)
            // Optional help while typing: a failure simply shows no suggestions.
            val rows = try { repository.suggestions(query) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { emptyList() }
            if (state.value.query.trim() == query) mutableState.update { it.copy(suggestions = rows) }
        }
    }

    fun pickSuggestion(value: String) {
        suggestionJob?.cancel()
        mutableState.update { it.copy(query = value, suggestions = emptyList()) }
        search()
    }

    fun search() {
        val query = state.value.query.trim()
        if (query.isEmpty()) return
        suggestionJob?.cancel()
        mutableState.update { it.copy(suggestions = emptyList()) }
        cancelSearch()
        cancelDetailAndMedia()
        preferences.edit().putString("last_query", query).apply()
        val version = ++searchVersion
        mutableState.update { it.copy(tab = AppTab.SEARCH, query = query, searchedQuery = query,
            results = emptyList(), searching = true, loadingMore = false, hasMore = false, nextOffset = 0,
            searchError = null, selectedCard = null, detail = null, reviewFirstPage = null, video = null, preparing = false,
            playbackError = null, firstFrameRendered = false, playerOpen = false, detailReturnsToPlayer = false) }
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

    /** 沉浸暗场 preview: the episode list plus episode 1's progressive session. Writes no history or progress. */
    internal suspend fun previewFirstEpisode(card: DramaCard): Pair<DramaDetail, PreparedVideo> {
        val detail = repository.details(card)
        val first = detail.episodes.first()
        return detail to repository.prepare(first, detail.id, allowFallback = false) { _, _, _ -> }
    }

    /** Continues a feed preview in the full player at [positionMs] of [episode] instead of restarting it. */
    fun playFromPreview(card: DramaCard, episode: Episode, positionMs: Long) =
        openDramaInternal(card, LibraryEntry(card, episode.id, episode.index.coerceAtLeast(1), positionMs))

    fun openDrama(card: DramaCard) = openDramaInternal(card, null)
    /** Opens the drama and starts its last-watched (or first) episode as soon as the episode list arrives. */
    fun playDrama(card: DramaCard) = openDramaInternal(card, null, autoPlay = true)

    private fun openDramaInternal(card: DramaCard, resume: LibraryEntry?, autoPlay: Boolean = false) {
        val historyOwner = accounts.state.value.profile?.id
        detailHistoryOwner = historyOwner
        pendingLibrary = resume
        pendingAutoPlay = autoPlay
        cancelSearch()
        cancelDetailAndMedia()
        val version = ++detailVersion
        mutableState.update {
            it.copy(selectedCard = card, detail = null, loadingDetail = true, detailError = null, related = emptyList(), reviewFirstPage = null,
                video = null, preparing = false, preparationStage = "", playbackError = null,
                episodePosition = -1, playbackStatus = "", firstFrameRendered = false, resumePositionMs = 0, playerOpen = false,
                detailReturnsToPlayer = false)
        }
        relatedJob?.cancel()
        relatedJob = viewModelScope.launch {
            // Secondary content: a failure leaves the section out without affecting the drama itself.
            val works = try { repository.related(card.id) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { return@launch }
            if (version == detailVersion) mutableState.update { it.copy(related = works) }
            // The related plan carries no release date; look up the few upcoming works one by one.
            for (work in works.filter { it.upcoming }.take(3)) {
                val text = try { repository.upcomingRelease(work) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { "" }
                if (text.isEmpty() || version != detailVersion) continue
                mutableState.update { state -> state.copy(related = state.related.map {
                    if (it.series.id == work.series.id) it.copy(releaseText = text) else it }) }
            }
        }
        detailJob = viewModelScope.launch {
            try {
                val detail = repository.details(card)
                if (version != detailVersion || !currentCoroutineContext().isActive || accounts.state.value.profile?.id != historyOwner) return@launch
                val watched = resume ?: visibleHistoryFor(card.id, historyOwner)
                val checkpoint = watched?.let { historyResume(detail.episodes, it) }
                val key = resumeKey(historyOwner, card.id)
                val lastId = preferences.getString("$key.episode", null)
                val lastIndex = preferences.getInt("$key.index", -1)
                val selected = checkpoint?.episodePosition ?: detail.episodes.indexOfFirst { it.id == lastId }.takeIf { it >= 0 }
                    ?: detail.episodes.indexOfFirst { it.index == lastIndex }.takeIf { it >= 0 } ?: 0
                mutableState.update { it.copy(detail = detail, loadingDetail = false, episodePosition = selected) }
                library.refreshCard(cardFor(detail))
                if (historyOwner != null) library.forHistoryOwner(historyOwner).refreshCard(cardFor(detail))
                if (autoPlay || (resume != null && checkpoint != null && !checkpoint.seriesEnded)) {
                    playEpisode(selected, resumeOverrideMs = checkpoint?.positionMs)
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

    fun retryDetails() { state.value.selectedCard?.let { openDramaInternal(it, pendingLibrary, pendingAutoPlay) } }

    internal suspend fun preloadReviewSummary(seriesId: String) {
        if (state.value.detail?.id != seriesId || state.value.reviewFirstPage != null) return
        try {
            loadSeriesReviews(seriesId, 0)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The entry remains available. Opening it can retry and show any error in the panel.
        }
    }

    internal suspend fun loadSeriesReviews(seriesId: String, offset: Int): SeriesReviewPage {
        val version = detailVersion
        val current = state.value
        if (offset == 0 && current.detail?.id == seriesId) current.reviewFirstPage?.let { return it }
        val page = SeriesReviewApi.repository.page(seriesId, offset)
        if (offset == 0) mutableState.update {
            if (version == detailVersion && it.detail?.id == seriesId) it.copy(reviewFirstPage = page) else it
        }
        return page
    }

    private fun visibleHistoryFor(id: String, owner: String?): LibraryEntry? =
        state.value.takeIf { it.historyOwnerId == owner }?.history?.firstOrNull { it.card.id == id }

    // The unprefixed keys belong to guest playback saved by earlier versions.
    private fun resumeKey(owner: String?, seriesId: String): String =
        if (owner == null) seriesId else "account.${owner.length}.$owner.$seriesId"

    fun playEpisode(position: Int, restart: Boolean = false, resumeOverrideMs: Long? = null) {
        val current = state.value
        val detail = current.detail ?: return
        val episode = detail.episodes.getOrNull(position) ?: return
        val historyOwner = detailHistoryOwner
        if (historyOwner != accounts.state.value.profile?.id) return
        val uploadOwner = accounts.ownerKey
        if (historyOwner != accounts.state.value.profile?.id) return
        if (nextPreload?.episodeId != episode.id) discardPrefetch()
        val preload = nextPreload
        nextPreload = null
        val ready = preload?.takeReady()
        ++mediaVersion
        mediaJob?.cancel()
        resumePreparationOnForeground = false
        bufferReadyForPrefetch = false
        prefetchAttemptedFor = -1
        val version = mediaVersion
        accountPlaybacks[version.toLong()] = AccountPlayback(
            LibraryEntry(cardFor(detail), episode.id, episode.index), historyOwner, library.forHistoryOwner(historyOwner), uploadOwner)
        while (accountPlaybacks.size > 16) accountPlaybacks.remove(accountPlaybacks.keys.first())
        val checkpoint = visibleHistoryFor(detail.id, historyOwner)?.let { historyResume(detail.episodes, it) }
        val savedPosition = if (restart) 0 else resumeOverrideMs ?: checkpoint?.takeIf { it.episodePosition == position }?.positionMs
            ?: preferences.getLong("${resumeKey(historyOwner, detail.id)}.${episode.id}.position", 0)
        mutableState.update {
            it.copy(episodePosition = position, preparing = ready == null, video = ready?.copy(requestId = version.toLong()),
                preparationStage = "正在获取片源", preparationDone = 0, preparationTotal = 0,
                playbackError = null, playbackStatus = if (ready == null) "" else "正在载入播放器",
                firstFrameRendered = false, resumePositionMs = savedPosition, playerOpen = true,
                detailReturnsToPlayer = false)
        }
        releaseVideo(current.video)
        if (ready != null) return
        mediaJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val prepared = preload?.awaitVideo() ?: repository.prepare(episode, detail.id) { stage, done, total ->
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
        discardPrefetch()
        mutableState.update { it.copy(preparing = false, preparationStage = "", playbackStatus = "已取消，可重新选择剧集") }
    }

    fun retryPlayback() {
        val position = state.value.episodePosition
        if (position >= 0) playEpisode(position)
    }

    fun openPlayer() {
        val current = state.value
        // An episode still being prepared carries on; the player picks it up when it is ready.
        if (current.video != null || current.preparing) mutableState.update { it.copy(playerOpen = true, detailReturnsToPlayer = false) }
        else playEpisode(current.episodePosition.coerceAtLeast(0))
    }

    /**
     * The player's title opens the drama's detail page without ending the episode: its prepared media stays, and
     * back on that page (or 继续播放) reopens the player where it was. Anything else that leaves the page releases it.
     */
    fun showDetailFromPlayer() {
        val current = state.value
        if (current.selectedCard == null || current.detail == null) return closePlayer()
        current.video?.let { accounts.flushProgress(accountPlaybacks[it.requestId]?.owner) }
        discardPrefetch()
        mutableState.update { it.copy(playerOpen = false, detailReturnsToPlayer = true) }
    }

    /** 返回 on a detail page: back to the player it was opened from, otherwise back to the list behind it. */
    fun closeDetail() {
        if (state.value.detailReturnsToPlayer && state.value.detail != null) openPlayer() else backToSearch()
    }

    fun closePlayer() {
        state.value.video?.let { accounts.flushProgress(accountPlaybacks[it.requestId]?.owner) }
        val previous = state.value.video
        ++mediaVersion
        mediaJob?.cancel()
        discardPrefetch()
        resumePreparationOnForeground = false
        mutableState.update { it.copy(playerOpen = false, video = null, preparing = false,
            preparationStage = "", playbackError = null, playbackStatus = "", firstFrameRendered = false, detailReturnsToPlayer = false) }
        releaseVideo(previous)
    }

    fun onPlayerForeground(active: Boolean) {
        if (active && !playerForeground) prefetchAttemptedFor = -1
        playerForeground = active
        if (!active) {
            state.value.video?.let { accounts.flushProgress(accountPlaybacks[it.requestId]?.owner) }
            discardPrefetch()
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
        val detail = current.detail ?: return
        val episode = detail.episodes.getOrNull(current.episodePosition + 1) ?: return
        if (nextPreload?.episodeId == episode.id) return
        if (prefetchAttemptedFor == requestId) return
        prefetchAttemptedFor = requestId
        discardPrefetch()
        val preload = EpisodePreload(episode.id, ::releaseVideo)
        nextPreload = preload
        preload.start(viewModelScope,
            prepare = { repository.prepare(episode, detail.id, allowFallback = false) { _, _, _ -> } },
            warm = repository::warmNext)
    }

    private fun discardPrefetch() {
        nextPreload?.discard()
        nextPreload = null
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

    @JvmOverloads
    fun onPlaybackBuffered(episodeId: String, requestId: Long, bufferedAheadMs: Long, remainingMs: Long = 0L) {
        if (!isCurrentVideo(episodeId, requestId)) return
        // The player sends this observation only while it is actually playing.
        accountPlaybacks[requestId]?.let { it.watchState = it.watchState.onPlaying() }
        if (!hasBufferForNextEpisode(bufferedAheadMs, remainingMs)) return
        bufferReadyForPrefetch = true
        prefetchNext()
    }

    fun onPlaybackStatus(episodeId: String, requestId: Long, status: String) {
        if (isCurrentVideo(episodeId, requestId)) mutableState.update { it.copy(playbackStatus = status) }
    }

    fun onFirstFrame(episodeId: String, requestId: Long) {
        if (!isCurrentVideo(episodeId, requestId)) return
        val playback = accountPlaybacks[requestId] ?: return
        if (playback.historyOwner != accounts.state.value.profile?.id) return
        if (!playback.rendered) {
            playback.rendered = true
            lastRenderedRequest = requestId
            val entry = playback.entry
            playback.store.recordPlayback(entry.card, episodeId, entry.episodeIndex, state.value.resumePositionMs)
        }
        mutableState.update { it.copy(firstFrameRendered = true) }
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
        if (!isCurrentVideo(episodeId, requestId) || detailHistoryOwner != accounts.state.value.profile?.id) return
        savePosition(episodeId, durationMs, requestId, durationMs, force = true, completed = true)
        if (current.episodePosition < (current.detail?.episodes?.lastIndex ?: -1)) {
            if (current.autoPlayNext) nextEpisode() else onPlaybackStatus(episodeId, requestId, "本集已播完，上滑观看下一集")
        } else {
            val next = RelatedWorks.nextSeason(current.related)
            when {
                next == null -> onPlaybackStatus(episodeId, requestId, "已播完最后一集")
                next.upcoming -> onPlaybackStatus(episodeId, requestId, "已播完最后一集，${next.season}" +
                    next.releaseText.ifEmpty { "待上线" } + "，可在详情页预约")
                current.autoPlayNext -> playDrama(next.toCard())
                else -> onPlaybackStatus(episodeId, requestId, "已播完最后一集，${next.season}可在详情页观看")
            }
        }
    }

    fun savePosition(episodeId: String, positionMs: Long, requestId: Long? = null,
                     durationMs: Long = 0, force: Boolean = false, completed: Boolean = false) {
        val current = state.value
        val id = requestId ?: current.video?.requestId ?: return
        val playback = accountPlaybacks[id] ?: return
        val entry = playback.entry
        if (id != lastRenderedRequest || !playback.rendered || entry.episodeId != episodeId ||
            playback.historyOwner != accounts.state.value.profile?.id) return
        if (current.video?.episodeId == episodeId && current.video.requestId != id) return
        if (durationMs > 0) playback.durationMs = durationMs
        val position = if (completed && playback.durationMs > 0) playback.durationMs else positionMs.coerceAtLeast(0)
        // Pausing or releasing a completed player can report a position a little short of its duration.
        if (playback.watchState != EpisodeWatchState.WATCHING) {
            if (completed) {
                playback.watchState = EpisodeWatchState.COMPLETED
                return
            }
            if (!isCurrentVideo(episodeId, id) || !playback.watchState.canResumeAt(position, playback.durationMs)) return
            playback.store.recordPlayback(entry.card, episodeId, entry.episodeIndex, position)
        }
        playback.watchState = if (completed) EpisodeWatchState.COMPLETED else EpisodeWatchState.WATCHING
        val key = resumeKey(playback.historyOwner, entry.card.id)
        val editor = preferences.edit().putLong("$key.$episodeId.position", if (completed) 0 else position)
        val selected = current.detail?.episodes?.getOrNull(current.episodePosition)
        if (selected?.id == episodeId && current.detail?.id == entry.card.id) {
            editor.putString("$key.episode", episodeId).putInt("$key.index", entry.episodeIndex)
        }
        editor.apply()
        playback.store.updatePosition(entry.card, episodeId, entry.episodeIndex, position, completed)
        if (!completed || playback.durationMs > 0) accounts.reportProgress(
            entry.copy(positionMs = position, completed = completed, updatedAt = System.currentTimeMillis()),
            playback.durationMs, force, playback.owner)
        if (isCurrentVideo(episodeId, id)) mutableState.update { it.copy(resumePositionMs = if (completed) 0 else position) }
    }

    fun backToSearch() {
        pendingLibrary = null
        pendingAutoPlay = false
        cancelDetailAndMedia()
        mutableState.update { it.copy(selectedCard = null, detail = null, loadingDetail = false, detailError = null, reviewFirstPage = null,
            preparing = false, video = null, playbackError = null, episodePosition = -1, playbackStatus = "", firstFrameRendered = false, playerOpen = false,
            detailReturnsToPlayer = false) }
    }

    private fun cancelDetailAndMedia() {
        ++detailVersion
        ++mediaVersion
        detailJob?.cancel()
        mediaJob?.cancel()
        discardPrefetch()
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
