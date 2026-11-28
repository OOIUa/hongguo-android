package com.hongguo.tv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel
import com.hongguo.android.*
import com.hongguo.android.R

class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TvMode.redirectFromTvIfNeeded(this)) return
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        setContent {
            HongguoTheme(AppThemeStyle.IMMERSIVE, dark = true, applyToWindow = false) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Background)
                        .displayCutoutPadding()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    TelevisionApp(this@TvActivity)
                }
            }
        }
    }
}

private val Background = Color(0xFF161518)
private val Rail = Color(0xFF111013)
private val Selected = Color(0xFF59312C)
private val Focus = Color(0xFFFFEFE0)
internal val LocalRailFocused = compositionLocalOf { false }
private data class Destination(val tab: AppTab, val label: String, val icon: ImageVector)
private val sections = listOf(
    Destination(AppTab.HOME, "首页", Icons.Outlined.Home),
    Destination(AppTab.CATEGORY, "发现", Icons.Outlined.Explore),
    Destination(AppTab.SEARCH, "搜索", Icons.Outlined.Search),
    Destination(AppTab.HISTORY, "片单", Icons.Outlined.Bookmarks),
    Destination(AppTab.MINE, "我的", Icons.Outlined.PersonOutline),
)

@Composable
private fun TelevisionApp(activity: Activity) {
    val model: HongguoViewModel = viewModel()
    val account: AccountViewModel = viewModel()
    val updateModel: UpdateViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val accountState by account.state.collectAsStateWithLifecycle()
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    val signedIn = accountState.sessionStatus == AccountSessionStatus.LOGGED_IN
    LaunchedEffect(signedIn) { model.onAccountSession(signedIn) }
    val watching = state.selectedCard != null || state.playerOpen
    BackHandler(enabled = watching || state.tab != AppTab.HOME) {
        when {
            state.playerOpen -> model.closePlayer()
            state.selectedCard != null -> model.closeDetail()
            else -> when (state.tab) {
                AppTab.SEARCH -> model.closeSearch()
                AppTab.RANK -> model.closeRank()
                AppTab.ACCOUNT -> model.closeAccount()
                AppTab.SETTINGS -> model.closeSettings()
                AppTab.THEME_STYLE -> model.closeThemeStyle()
                AppTab.FAVORITES, AppTab.HISTORY -> model.closeLibrary()
                AppTab.PLAY_HISTORY, AppTab.LOCAL_FAVORITES -> model.closeMyLibrary()
                else -> model.selectTab(AppTab.HOME)
            }
        }
    }
    val pages = rememberSaveableStateHolder()
    if (watching) {
        TvWatch(state, model, account)
    } else {
        val tab = state.tab
        val active = when (tab) {
            AppTab.RANK -> AppTab.CATEGORY
            AppTab.FAVORITES -> AppTab.HISTORY
            AppTab.ACCOUNT, AppTab.SETTINGS, AppTab.THEME_STYLE,
            AppTab.PLAY_HISTORY, AppTab.LOCAL_FAVORITES -> AppTab.MINE
            else -> tab
        }
        val entry = remember(tab) { FocusRequester() }
        val contentGroup = remember(tab) { FocusRequester() }
        val railTargets = remember { sections.associate { it.tab to FocusRequester() } }
        var railFocused by remember { mutableStateOf(false) }
        fun enterContent(): Boolean = runCatching { entry.requestFocus() }.getOrDefault(false) ||
            contentGroup.requestFocus()
        Row(Modifier.fillMaxSize().background(Background)) {
            Column(
                Modifier.width(151.dp).fillMaxHeight().background(Rail)
                    .padding(start = 22.dp, end = 12.dp, top = 30.dp)
                    .onFocusChanged { railFocused = it.hasFocus }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(32.dp))
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text("红果剧场", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("电视版", color = Color(0xFFB6A8A4), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(13.dp))
                sections.forEach { dest ->
                    val open = {
                        if (tab != dest.tab) model.selectTab(dest.tab)
                        else enterContent()
                        Unit
                    }
                    var focused by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier.fillMaxWidth().height(40.dp)
                            .focusRequester(railTargets.getValue(dest.tab))
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent {
                                if (it.key != Key.DirectionRight) false
                                else {
                                    if (it.type == KeyEventType.KeyDown) open()
                                    true
                                }
                            }
                            .clip(shape).background(if (active == dest.tab) Selected else Rail)
                            .border(2.dp, if (focused) Focus else Color.Transparent, shape)
                            .clickable(role = Role.Button, onClick = open)
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ink = if (active == dest.tab) Color(0xFFFFC6B4) else Color(0xFFE1DCDD)
                        Icon(dest.icon, null, Modifier.size(18.dp), tint = ink)
                        Spacer(Modifier.width(8.dp))
                        Text(dest.label, color = ink, fontSize = 16.sp)
                    }
                }
            }
            pages.SaveableStateProvider(tab.name) {
                // Always attach the entry target, including during loading and error transitions.
                Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp)
                    .focusRequester(contentGroup).focusGroup()) {
                    CompositionLocalProvider(LocalRailFocused provides railFocused) {
                        when (tab) {
                            AppTab.HOME, AppTab.CATEGORY, AppTab.SEARCH, AppTab.RANK ->
                                TvBrowse(state, model, entry) { railTargets.getValue(active).requestFocus() }
                            else -> TvLibrary(
                                state = state,
                                model = model,
                                account = account,
                                updateState = updateState,
                                updateModel = updateModel,
                                onSwitchToPhone = { TvMode.switchToPhone(activity) },
                                entryFocus = entry,
                            )
                        }
                    }
                }
            }
            LaunchedEffect(tab) {
                withFrameNanos { }
                if (!enterContent()) railTargets.getValue(active).requestFocus()
            }
        }
    }
    TvUpdateDialog(updateState, updateModel, activity)
    ReservationResultDialog(accountState.reservationResult, account::consumeReservationResult)
}

@Composable
internal fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (selected) Selected else Color(0xFF302C2F))
            .border(2.dp, if (focused) Focus else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 14.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
