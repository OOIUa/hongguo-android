package com.hongguo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel
import com.hongguo.android.*

private val libraryMuted = Color(0xffc6bbbf)

@Composable
internal fun TvLibrary(
    state: HongguoUiState,
    model: HongguoViewModel,
    account: AccountViewModel,
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onSwitchToPhone: () -> Unit,
    entryFocus: FocusRequester,
) {
    // The shell can send focus from the rail to the first enabled action in each page or login state.
    Box(Modifier.fillMaxSize().focusRequester(entryFocus).focusGroup()) {
        when (state.tab) {
            AppTab.MINE -> MyPage(model, account, updateState, updateModel, onSwitchToPhone)
            AppTab.ACCOUNT -> TvAccountPage(model, account)
            AppTab.SETTINGS -> TvPlaybackSettings(state, model, account, updateState, updateModel, onSwitchToPhone)
            else -> ShelfPage(state, model, account)
        }
    }
}

@Composable
private fun Heading(title: String) {
    Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
}

private fun accountLabel(stage: AccountSessionStatus) = when (stage) {
    AccountSessionStatus.LOGGED_IN -> "已登录红果账号"
    AccountSessionStatus.RESTORING -> "正在验证已保存的账号"
    AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，云片单待连接"
    AccountSessionStatus.NEEDS_VERIFICATION -> "账号需要重新验证"
    AccountSessionStatus.SIGNED_OUT -> "尚未登录红果账号"
}

@Composable
private fun MyPage(
    model: HongguoViewModel,
    account: AccountViewModel,
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onSwitchToPhone: () -> Unit,
) {
    val user by account.state.collectAsStateWithLifecycle()
    val signed = user.sessionStatus == AccountSessionStatus.LOGGED_IN
    val update = updateState.update
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(23.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("我的")
        Text(if (signed) user.profile?.nickname.orEmpty().ifBlank { "红果用户" } else accountLabel(user.sessionStatus), color = Color.White, fontSize = 20.sp)
        if (!signed) Text("登录后可查看账号收藏、观看历史和预约。", color = libraryMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvButton(if (signed) "账号管理" else "登录红果账号", { model.selectTab(AppTab.ACCOUNT) }, Modifier.testTag("tv_my_login"), selected = true)
            TvButton("播放设置", { model.selectTab(AppTab.SETTINGS) }, Modifier.testTag("tv_my_settings"))
        }
        if (signed) {
            Text("红果账号", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((section, label) in listOf(AccountSection.FAVORITES to "账号收藏", AccountSection.RESERVATIONS to "账号预约")) {
                    TvButton(label, { account.selectSection(section); model.selectLibrarySource(true); model.selectTab(AppTab.FAVORITES) })
                }
            }
        }
        Text("我的片单", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvButton("观看历史", model::openPlayHistory)
            TvButton("本机收藏", { model.selectTab(AppTab.LOCAL_FAVORITES) })
        }
        Text("应用与更新", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TvButton(
                label = if (update != null) "发现新版 v${update.versionName}" else "检查更新",
                onClick = updateModel::checkForUpdates,
                modifier = Modifier.testTag("tv_my_update"),
                selected = update != null,
            )
            TvButton("切换为手机界面", onSwitchToPhone, Modifier.testTag("tv_switch_phone"))
        }
        Text("当前版本 v${BuildConfig.VERSION_NAME}", color = libraryMuted, fontSize = 13.sp)
    }
}

@Composable
private fun TvAccountPage(model: HongguoViewModel, account: AccountViewModel) {
    val user by account.state.collectAsStateWithLifecycle()
    var other by rememberSaveable { mutableStateOf(false) }
    if (other || user.localClearFailed || user.sessionStatus != AccountSessionStatus.SIGNED_OUT) {
        AccountScreen(account, onBack = {
            if (other && user.sessionStatus == AccountSessionStatus.SIGNED_OUT) other = false
            else { other = false; model.closeAccount() }
        }, onOpenLibrary = model::openLibrary, onOpenHistory = model::openPlayHistory)
        return
    }
    DisposableEffect(account) { onDispose { account.cancelLogin() } }
    LaunchedEffect(account) { if (!user.busy && user.qrBytes == null) account.startQrLogin() }
    val image = remember(user.qrBytes) { decodeAccountQr(user.qrBytes) }
    Column(Modifier.fillMaxSize().padding(23.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Heading("扫码登录")
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(175.dp).background(Color(0xff252227)), contentAlignment = Alignment.Center) {
                if (image != null) Image(image, "红果账号登录二维码", Modifier.fillMaxSize().alpha(if (user.qrRemainingSeconds > 0) 1f else .35f))
                else if (user.busy) CircularProgressIndicator()
                else Text("二维码暂不可用", color = libraryMuted, fontSize = 14.sp)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("用手机上的红果 App 扫码并确认", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    image != null && user.qrRemainingSeconds > 0 -> "二维码将在 ${user.qrRemainingSeconds} 秒后过期"
                    image != null -> "二维码已过期，请刷新"
                    user.busy -> "正在获取二维码…"
                    else -> user.message ?: "请重新获取，或使用其他登录方式。"
                }, color = libraryMuted, fontSize = 14.sp)
                TvButton(if (image != null) "刷新二维码" else "获取二维码", account::startQrLogin, enabled = !user.busy || image != null)
                TvButton("其他登录方式", { other = true }, Modifier.testTag("tv_login_other"))
                TvButton("返回", model::closeAccount)
            }
        }
    }
}

@Composable
private fun TvPlaybackSettings(
    state: HongguoUiState,
    model: HongguoViewModel,
    account: AccountViewModel,
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onSwitchToPhone: () -> Unit,
) {
    val user by account.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(23.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("播放设置")
        TvButton("自动连播下一集　${if (state.autoPlayNext) "开" else "关"}", { model.setAutoPlayNext(!state.autoPlayNext) })
        TvButton("下一集预加载　${if (state.preloadNext) "开" else "关"}", { model.setPreloadNext(!state.preloadNext) })
        TvButton("播放倍速　${speedLabel(state.playbackSpeed)}", {
            val index = tvSpeeds.indexOf(state.playbackSpeed)
            model.setPlaybackSpeed(tvSpeeds[(index + 1) % tvSpeeds.size])
        })
        val signed = user.sessionStatus == AccountSessionStatus.LOGGED_IN
        TvButton("同步此后的观看进度　${if (!signed) "需登录" else if (user.syncEnabled) "开" else "关"}", { account.setSyncEnabled(!user.syncEnabled) }, enabled = signed)
        TvButton("启动时检查更新　${if (updateState.automaticChecksEnabled) "开" else "关"}", { updateModel.setAutomaticChecksEnabled(!updateState.automaticChecksEnabled) })
        TvButton("切换为手机界面", onSwitchToPhone)
        TvButton("返回", model::closeSettings)
    }
}

@Composable
private fun ShelfPage(state: HongguoUiState, model: HongguoViewModel, account: AccountViewModel) {
    val personal = state.tab == AppTab.LOCAL_FAVORITES || state.tab == AppTab.PLAY_HISTORY
    val history = state.tab == AppTab.HISTORY || state.tab == AppTab.PLAY_HISTORY
    val cloud = !personal && !history && state.libraryCloud
    val user by account.state.collectAsStateWithLifecycle()
    var managing by rememberSaveable(state.tab, cloud) { mutableStateOf(false) }
    var clear by rememberSaveable(state.tab, cloud) { mutableStateOf(false) }
    val entries = if (history) state.history else state.favorites
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) managing = false }
    BackHandler(managing && !clear) { managing = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Heading(if (personal) (if (history) "观看历史" else "本机收藏") else "我的片单") }
            if (personal) TvButton("返回", model::closeMyLibrary)
            if (!personal) {
                TvButton("本机收藏", { model.selectLibrarySource(false); model.selectTab(AppTab.FAVORITES) }, selected = !cloud && !history)
                TvButton("红果账号", { model.selectLibrarySource(true); model.selectTab(AppTab.FAVORITES) }, selected = cloud)
                TvButton("观看历史", { model.selectTab(AppTab.HISTORY) }, selected = history)
            }
        }
        if (cloud) {
            if (user.sessionStatus != AccountSessionStatus.LOGGED_IN) {
                Text(accountLabel(user.sessionStatus), color = Color.White, fontSize = 18.sp)
                TvButton("登录红果账号", { model.selectTab(AppTab.ACCOUNT) })
            } else CloudLibraryContent(account, model::openLibrary) { model.selectTab(AppTab.ACCOUNT) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.isNotEmpty() && state.libraryError == null) {
                TvButton(if (managing) "完成" else "管理", { managing = !managing })
                if (managing) TvButton("清空", { clear = true })
            }
        }
        val error = state.libraryError
        if (error != null) { Text(error, color = libraryMuted); TvButton("重试", model::reloadLibrary) }
        if (error == null && entries.isEmpty()) {
            Text(if (history) "开始播放后，这里会记住集数和进度。" else "收藏的剧集会出现在这里。", color = libraryMuted)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(122.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
            items(entries, key = { it.card.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cover(entry.card.cover, entry.card.title, Modifier.fillMaxWidth().height(105.dp))
                    TvButton(if (managing) "移除 · ${entry.card.title}" else entry.card.title, {
                        if (!managing) model.openLibrary(entry)
                        else if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card)
                    }, Modifier.fillMaxWidth())
                    val progress = entry.completedCaption ?: if (entry.episodeIndex > 0)
                        "第 ${entry.episodeIndex} 集 · ${libraryPositionLabel(entry.positionMs)}" else "全 ${entry.card.episodeCount} 集"
                    Text(progress, color = libraryMuted, fontSize = 13.sp)
                }
            }
        }
    }
    if (clear) {
        val cancel = remember { FocusRequester() }
        val kind = if (history) "观看历史" else "本机收藏"
        AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空$kind？") },
            text = { Text("将移除这台电视上的 ${entries.size} 条记录。") },
            confirmButton = { TvButton("确认清空", {
                clear = false; managing = false
                if (history) model.clearHistory() else model.clearFavorites()
            }) },
            dismissButton = {
                TvButton("取消", { clear = false }, Modifier.focusRequester(cancel))
                LaunchedEffect(Unit) { cancel.requestFocus() }
            })
    }
}
