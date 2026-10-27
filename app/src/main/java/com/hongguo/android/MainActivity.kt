package com.hongguo.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
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
                if (state.playerOpen && state.detail != null) EpisodePlayerScreen(state, model, accountModel)
                else {
                    Scaffold(containerColor = Canvas,
                        snackbarHost = { SnackbarHost(snackbar, Modifier.testTag("app_snackbar")) }, bottomBar = {
                        if (state.selectedCard == null && state.tab !in setOf(AppTab.SEARCH, AppTab.ACCOUNT))
                            AppNavigation(state.tab, model::selectTab, layout)
                    }) { insets ->
                        pageStates.SaveableStateProvider(state.selectedCard?.let { "detail_${it.id}" } ?: "tab_${state.tab.name}") {
                        Column(Modifier.fillMaxSize().padding(insets).semantics { testTagsAsResourceId = true }) {
                            if (state.selectedCard != null) when (layout) {
                                AppThemeStyle.IMMERSIVE -> ImmersiveDetail(state, model, accountModel)
                                AppThemeStyle.CARDS -> CardsDetail(state, model, accountModel)
                                AppThemeStyle.WARM -> DetailScreen(state, model, accountModel)
                            }
                            else when (state.tab) {
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
                                    AppThemeStyle.IMMERSIVE -> ImmersiveLibrary(state, model, accountModel)
                                    AppThemeStyle.CARDS -> CardsLibrary(state, model, accountModel)
                                    AppThemeStyle.WARM -> LibraryScreen(state, model, accountModel)
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
        // The established typographic brand mark remains native, with no rasterized screen chrome.
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Orange), Alignment.Center) {
            Text("果", color = OnOrange, fontSize = 24.sp / LocalDensity.current.fontScale, fontWeight = FontWeight.Bold)
        }
        Text("红果剧场", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}
