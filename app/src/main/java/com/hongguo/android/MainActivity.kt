package com.hongguo.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeWindow(storedThemeStyle().palette())
        setContent {
            val model: HongguoViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            // The theme in effect picks both the colors and each page's layout.
            val layout = resolveThemeStyle(state.themeStyle, state.darkFollowsSystem)
            // The player owns the system bars while open; the theme re-applies them when it closes.
            HongguoTheme(layout, applyToWindow = !state.playerOpen) {
                val pageStates = rememberSaveableStateHolder()
                val accountModel: AccountViewModel = viewModel()
                val accountState by accountModel.state.collectAsStateWithLifecycle()
                val updateModel: UpdateViewModel = viewModel()
                val updateState by updateModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.playerOpen || state.selectedCard != null || state.tab != AppTab.HOME) {
                    when {
                        state.playerOpen -> model.closePlayer()
                        state.selectedCard != null -> model.backToSearch()
                        state.tab == AppTab.SEARCH -> model.closeSearch()
                        state.tab == AppTab.ACCOUNT -> model.closeAccount()
                        state.tab in setOf(AppTab.HISTORY, AppTab.FAVORITES) -> model.closeLibrary()
                        else -> model.selectTab(AppTab.HOME)
                    }
                }
                // The player rises over the detail page and fades away when closed.
                AnimatedContent(targetState = state.playerOpen && state.detail != null, label = "player_transition",
                    transitionSpec = {
                        if (targetState) (slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(220)))
                            .togetherWith(fadeOut(tween(180)))
                        else fadeIn(tween(220)).togetherWith(slideOutVertically(tween(260)) { it / 10 } + fadeOut(tween(200)))
                    }) { playerShown ->
                // False as soon as the player starts closing (or the page starts leaving), not when the fade ends.
                val playerActive = transition.targetState == EnterExitState.Visible
                if (playerShown) EpisodePlayerScreen(state, model, accountModel, active = playerActive)
                else {
                    val showNav = state.selectedCard == null && state.tab !in setOf(AppTab.SEARCH, AppTab.ACCOUNT)
                    // 悬浮玻璃: the bar floats as a liquid-glass capsule and the page runs underneath it.
                    val floatingNav = showNav && state.navBarStyle == NavBarStyle.GLASS
                    val backdrop = rememberGlassBackdrop()
                    val navClearance = if (floatingNav) floatingNavClearance() else 0.dp
                    Scaffold(containerColor = Canvas,
                        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("app_snackbar")
                            .padding(bottom = if (floatingNav) 76.dp else 0.dp)) }, bottomBar = {
                        if (showNav && !floatingNav) AppNavigation(state.tab, model::selectTab, style = layout)
                    }) { insets ->
                        val direction = LocalLayoutDirection.current
                        val pagePadding = if (!floatingNav) insets else PaddingValues(
                            start = insets.calculateStartPadding(direction), top = insets.calculateTopPadding(),
                            end = insets.calculateEndPadding(direction), bottom = 0.dp)
                        Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalNavClearance provides navClearance) {
                        // A detail page keeps drawing its last state while it slides out after 返回.
                        val lastDetail = remember { LastDetailState() }
                        SideEffect { state.selectedCard?.let { lastDetail.key = "detail_${it.id}"; lastDetail.state = state } }
                        val screenKey = state.selectedCard?.let { "detail_${it.id}" } ?: "tab_${state.tab.name}"
                        Column(Modifier.fillMaxSize()
                            .then(if (floatingNav) Modifier.glassBackdropSource(backdrop).background(Canvas) else Modifier)
                            .padding(pagePadding).semantics { testTagsAsResourceId = true }) {
                        AnimatedContent(targetState = screenKey, label = "page_transition", modifier = Modifier.fillMaxSize(),
                            transitionSpec = { pageTransition(initialState, targetState) }) { key ->
                        val pageActive = transition.targetState == EnterExitState.Visible
                        CompositionLocalProvider(LocalPageActive provides pageActive) {
                        pageStates.SaveableStateProvider(key) {
                        Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().background(Canvas)) {
                            // Each page draws its own state: a leaving detail page keeps its drama, never the next one.
                            val detailState = when {
                                !key.startsWith("detail_") -> null
                                state.selectedCard?.let { "detail_${it.id}" } == key -> state
                                lastDetail.key == key -> lastDetail.state
                                else -> null
                            }
                            if (detailState != null) when (layout) {
                                AppThemeStyle.IMMERSIVE -> ImmersiveDetail(detailState, model, accountModel)
                                AppThemeStyle.CARDS -> CardsDetail(detailState, model, accountModel)
                                AppThemeStyle.WARM -> DetailScreen(detailState, model, accountModel)
                            }
                            else if (key.startsWith("detail_")) Unit
                            else {
                            val keyTab = AppTab.valueOf(key.removePrefix("tab_"))
                            val tabState = if (state.tab == keyTab) state else state.copy(tab = keyTab)
                            when (keyTab) {
                                AppTab.HOME -> when (layout) {
                                    AppThemeStyle.IMMERSIVE -> ImmersiveHome(state, model)
                                    AppThemeStyle.CARDS -> CardsHome(state, model)
                                    AppThemeStyle.WARM -> {
                                        BrandHeader()
                                        HomeScreen(state, model)
                                    }
                                }
                                AppTab.CATEGORY -> when (layout) {
                                    AppThemeStyle.IMMERSIVE -> ImmersiveTheater(state, model)
                                    AppThemeStyle.CARDS -> CardsDiscover(state, model)
                                    AppThemeStyle.WARM -> CategoryScreen(state, model)
                                }
                                AppTab.SEARCH -> SearchScreen(state, model,
                                    trending = if (layout == AppThemeStyle.CARDS) state.home.items.take(10) else emptyList())
                                AppTab.FAVORITES, AppTab.HISTORY -> when (layout) {
                                    AppThemeStyle.IMMERSIVE -> ImmersiveLibrary(tabState, model, accountModel)
                                    AppThemeStyle.CARDS -> CardsLibrary(tabState, model, accountModel)
                                    AppThemeStyle.WARM -> LibraryScreen(tabState, model, accountModel)
                                }
                                AppTab.MINE -> MyScreen(state, model, updateState, updateModel,
                                    accountName = if (accountState.sessionStatus == AccountSessionStatus.WEBSITE_ONLY)
                                        "官网登录已保存" else accountState.profile?.nickname,
                                    accountStatus = when (accountState.sessionStatus) {
                                        AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，手机云账号未接通"
                                        AccountSessionStatus.LOGGED_IN -> "查看账号收藏、历史与预约"
                                        AccountSessionStatus.NEEDS_VERIFICATION -> "已保存账号，等待重新验证"
                                        else -> "登录后查看手机片单"
                                    }, style = layout)
                                AppTab.ACCOUNT -> AccountScreen(accountModel, onBack = model::closeAccount,
                                    onOpenLibrary = model::openLibrary)
                            }
                            }
                        }
                        // A leaving page still shows for a moment; it must not take taps meant for the new page.
                        if (!pageActive) Box(Modifier.matchParentSize().pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                        })
                        }
                        }
                        }
                        }
                        }
                        }
                        if (floatingNav) AppNavigation(state.tab, model::selectTab, Modifier.align(Alignment.BottomCenter),
                            style = layout, backdrop = backdrop)
                        }
                    }
                }
                }
                if (state.themeOnboardingPending) {
                    ThemeOnboardingDialog(current = state.themeStyle,
                        onConfirm = { choice -> model.completeThemeOnboarding(choice) },
                        onSkip = { model.completeThemeOnboarding(null) })
                }
                // The "change it in settings" hint waits for any update dialog, and survives rotation: it is only
                // cleared after it has been shown. A dialog appearing mid-way cancels it and it is shown again later.
                val themeHint = state.themeHint
                LaunchedEffect(themeHint, updateState.visible, state.themeOnboardingPending) {
                    if (themeHint == null || updateState.visible || state.themeOnboardingPending) return@LaunchedEffect
                    snackbar.showSnackbar(themeHint, withDismissAction = true, duration = SnackbarDuration.Long)
                    model.consumeThemeHint()
                }
                // A startup update prompt waits until the theme picker has been answered, so two dialogs never stack.
                UpdateDialogs(updateState, updateModel, this@MainActivity, deferred = state.themeOnboardingPending)
            }
        }
    }
}

/**
 * The saved theme, read before composition so the window and system bars match it from the first Compose frame.
 * The system's starting window (themes.xml) is drawn earlier and stays light; a dark splash would need a
 * separate starting theme.
 */
private fun ComponentActivity.storedThemeStyle(): AppThemeStyle {
    val preferences = getSharedPreferences(ThemePreferences.FILE, android.content.Context.MODE_PRIVATE)
    val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return resolveThemeStyle(AppThemeStyle.fromKey(preferences.getString(ThemePreferences.STYLE, null)),
        preferences.getBoolean(ThemePreferences.DARK_FOLLOWS_SYSTEM, false), systemDark)
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Minimal red-fruit brand mark, shared with the launcher icon (res/drawable/ic_logo.xml, vector).
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(34.dp))
        Text("红果剧场", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

/** Pages opened on top (detail, 搜索, 账号) slide in from the right and back out; switching tabs cross-fades. */
private fun AnimatedContentTransitionScope<String>.pageTransition(from: String, to: String): ContentTransform {
    fun depth(key: String) = when {
        key.startsWith("detail_") -> 2
        key == "tab_${AppTab.SEARCH.name}" || key == "tab_${AppTab.ACCOUNT.name}" -> 1
        else -> 0
    }
    val forward = depth(to) > depth(from)
    val back = depth(to) < depth(from)
    val slide = tween<IntOffset>(280, easing = FastOutSlowInEasing)
    return when {
        forward -> (slideInHorizontally(slide) { it / 3 } + fadeIn(tween(220)))
            .togetherWith(fadeOut(tween(160)))
        back -> fadeIn(tween(200))
            .togetherWith(slideOutHorizontally(slide) { it / 3 } + fadeOut(tween(200)))
            .apply { targetContentZIndex = -1f }
        else -> fadeIn(tween(180, delayMillis = 60)).togetherWith(fadeOut(tween(120)))
    }
}

/** False while a page is animating out, so its back handlers and taps stand down. */
internal val LocalPageActive = compositionLocalOf { true }

/** The detail page last shown, so it can keep drawing its drama while it slides out. Not observable state. */
private class LastDetailState {
    var key: String? = null
    var state: HongguoUiState? = null
}
