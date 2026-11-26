package com.hongguo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountState
import com.hongguo.account.AccountViewModel

/** Tells the window whether the account cinema hero is still behind the transparent status bar. */
internal val LocalAccountHeroUnderBar = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal fun AccountScreen(
    accountModel: AccountViewModel,
    onBack: () -> Unit,
    onOpenLibrary: (LibraryEntry) -> Unit,
    onOpenHistory: () -> Unit,
    onAccountSettings: () -> Unit = {},
    accountPageVisit: Long = 0,
    latestHistory: LibraryEntry? = null,
    historyCount: Int = 0,
) {
    val state by accountModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ownerKey = accountModel.ownerKey
    val sessionKey = accountModel.sessionKey
    val sessionStatus = state.sessionStatus
    val signedIn = sessionStatus == AccountSessionStatus.LOGGED_IN && state.profile != null
    var logoutRequested by remember(sessionKey, ownerKey, state.profile?.id, sessionStatus) { mutableStateOf(false) }
    var removeTarget by remember(ownerKey, state.profile?.id, sessionStatus) { mutableStateOf<LibraryEntry?>(null) }
    var diagnosticCopied by remember(state.loginDiagnostic) { mutableStateOf(false) }
    var localResetRequired by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ownerKey, sessionStatus) {
        if (ownerKey != null && sessionStatus == AccountSessionStatus.LOGGED_IN && !state.busy) accountModel.refresh()
    }
    BackHandler(enabled = LocalPageActive.current) { accountModel.cancelLogin(); onBack() }
    DisposableEffect(accountModel) { onDispose { accountModel.cancelLogin() } }
    LaunchedEffect(state.message, state.sessionStatus, state.localClearFailed) {
        if (state.localClearFailed) {
            localResetRequired = true
        } else if (state.sessionStatus == AccountSessionStatus.LOGGED_IN ||
            state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY || state.message?.startsWith("已退出账号") == true) {
            localResetRequired = false
        } else if (state.sessionStatus == AccountSessionStatus.SIGNED_OUT &&
            state.message?.let { "本机账号资料无法读取" in it || "本机清除保存失败" in it || "账号资料保存失败" in it } == true) {
            localResetRequired = true
        }
    }

    val hasNotice = state.message != null || state.loginDiagnostic != null
    val hasPendingNotice = signedIn && state.pendingProgressCount > 0
    // A reservation shortcut starts at the shelf. Returning from a detail or settings restores the list position.
    val atReservations = accountPageVisit > 0 && signedIn && state.selectedSection == AccountSection.RESERVATIONS
    val listState = key(accountPageVisit, state.profile?.id) {
        val shelfStart = 2 + (if (hasNotice) 1 else 0) + (if (hasPendingNotice) 1 else 0)
        rememberLazyListState(initialFirstVisibleItemIndex = if (atReservations) shelfStart else 0)
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroCollapsePx = with(LocalDensity.current) { 116.dp.roundToPx() }
    val heroUnderBar by remember(listState, heroCollapsePx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 ||
                (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset < heroCollapsePx)
        }
    }
    val reportHeroUnderBar = LocalAccountHeroUnderBar.current
    val pageActive = LocalPageActive.current
    LaunchedEffect(heroUnderBar, pageActive) {
        if (pageActive) reportHeroUnderBar(heroUnderBar)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .testTag("account_screen"),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        stickyHeader(key = "account_title") {
            AccountCinemaTopBar(
                statusTop = statusTop,
                overHero = heroUnderBar,
                signedIn = signedIn,
                busy = state.busy,
                onBack = { accountModel.cancelLogin(); onBack() },
                onRefresh = accountModel::refresh,
                onAccountSettings = onAccountSettings,
                onLogout = { logoutRequested = true },
            )
        }

        if (signedIn) {
            item(key = "account_hero") {
                AccountSignedInHero(
                    state = state,
                    onAccountSettings = onAccountSettings,
                )
            }
            if (hasNotice) {
                item(key = "account_notice") {
                    AccountTopNoticeCard(
                        message = state.message,
                        diagnostic = state.loginDiagnostic,
                        diagnosticCopied = diagnosticCopied,
                        onCopyDiagnostic = { diagnostic ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("红果登录诊断", diagnostic))
                                diagnosticCopied = true
                            }
                        },
                        onConsume = accountModel::consumeMessage,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                }
            }
            if (hasPendingNotice) {
                item(key = "account_pending_progress_card") {
                    AccountPendingProgressCard(
                        count = state.pendingProgressCount,
                        onClick = onAccountSettings,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                }
            }
            item(key = "account_history_spotlight") {
                AccountHistorySpotlightCard(
                    latest = latestHistory,
                    historyCount = historyCount,
                    onClick = onOpenHistory,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                )
            }
            cloudLibraryItems(
                state = state,
                accountModel = accountModel,
                onOpenLibrary = onOpenLibrary,
                onRemove = { removeTarget = it },
                showHeaderRefresh = false,
                horizontalPadding = 16.dp,
            )
        } else if (sessionStatus == AccountSessionStatus.WEBSITE_ONLY) {
            item(key = "account_hero") {
                AccountBrandHero(title = "官网已登录，账号待连接")
            }
            if (hasNotice) {
                item(key = "account_notice") {
                    AccountTopNoticeCard(
                        message = state.message,
                        diagnostic = state.loginDiagnostic,
                        diagnosticCopied = diagnosticCopied,
                        onCopyDiagnostic = { diagnostic ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("红果登录诊断", diagnostic))
                                diagnosticCopied = true
                            }
                        },
                        onConsume = accountModel::consumeMessage,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            item(key = "account_website_only_card") {
                val shape = RoundedCornerShape(22.dp)
                Surface(
                    shape = shape,
                    color = CardSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                        .border(1.dp, Line, shape),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                            .testTag("account_website_only"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.websiteProfile?.takeIf { it.nickname != null || it.avatarUrl != null }?.let { website ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AccountAvatar(
                                    url = website.avatarUrl,
                                    size = 46.dp,
                                    initial = website.nickname?.trim()?.firstOrNull()?.toString(),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = website.nickname ?: "红果用户",
                                        color = Ink,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("account_website_name"),
                                    )
                                    Text("官网会话有效 · 手机云账号待连接", color = StatusWarn, fontSize = 12.sp)
                                }
                            }
                        }
                        Text(
                            text = "官网登录已加密保存在本机。可直接重试，无需重新获取验证码。连接后即可查看收藏、点赞与预约，也可以开启进度同步。",
                            color = Muted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                        Button(
                            onClick = accountModel::retryWebsiteAccount,
                            enabled = !state.busy,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("account_retry_website"),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OnOrange)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.busy) "正在连接账号…" else "重试连接账号", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { logoutRequested = true },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .testTag("account_website_logout"),
                        ) {
                            Text("退出已保存的官网登录", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        } else {
            item(key = "account_hero") {
                AccountBrandHero(
                    title = when (sessionStatus) {
                        AccountSessionStatus.NEEDS_VERIFICATION -> "账号需要重新验证"
                        AccountSessionStatus.RESTORING -> "正在确认已保存账号"
                        else -> "登录红果账号"
                    },
                )
            }
            if (hasNotice) {
                item(key = "account_notice") {
                    AccountTopNoticeCard(
                        message = state.message,
                        diagnostic = state.loginDiagnostic,
                        diagnosticCopied = diagnosticCopied,
                        onCopyDiagnostic = { diagnostic ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("红果登录诊断", diagnostic))
                                diagnosticCopied = true
                            }
                        },
                        onConsume = accountModel::consumeMessage,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            if (sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION || localResetRequired) {
                item(key = "account_recovery") {
                    val shape = RoundedCornerShape(20.dp)
                    Surface(
                        shape = shape,
                        color = CardSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                            .border(1.dp, AccentBorder, shape),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AccountAvatar(
                                    url = state.profile?.avatarUrl,
                                    size = 44.dp,
                                    initial = state.profile?.nickname?.trim()?.firstOrNull()?.toString(),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = if (localResetRequired) "恢复本机账号登录" else "已保存的账号等待验证",
                                        color = Ink,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                    )
                                    state.profile?.let { profile ->
                                        Text(
                                            text = "${profile.nickname.ifBlank { "红果用户" }} · 账号 ${profile.id}",
                                            color = StatusWarn,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION) {
                                    "恢复网络后可重新验证；也可以退出已保存的账号后重新登录。"
                                } else {
                                    "本机登录资料未能读取。清除已保存的登录态后可重新登录，本机收藏和观看记录继续保留。"
                                },
                                color = Muted,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION) {
                                    Button(
                                        onClick = accountModel::refresh,
                                        enabled = !state.busy,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
                                        modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("account_verify_saved"),
                                    ) {
                                        Text("重新验证已保存账号", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { logoutRequested = true },
                                    enabled = !state.busy,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .then(if (sessionStatus != AccountSessionStatus.NEEDS_VERIFICATION) Modifier.fillMaxWidth() else Modifier)
                                        .heightIn(min = 42.dp)
                                        .testTag("account_clear_saved"),
                                ) {
                                    Text(
                                        text = if (localResetRequired) "清除本机保存的登录态" else "退出已保存账号",
                                        color = AccentInk,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (sessionStatus == AccountSessionStatus.RESTORING) {
                item(key = "account_restoring") {
                    val shape = RoundedCornerShape(20.dp)
                    Surface(
                        shape = shape,
                        color = CardSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .border(1.dp, Line, shape),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AccentInk)
                            Text("正在确认账号…", color = Muted, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                item(key = "login_methods") {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        AccountLoginMethods(state, accountModel)
                    }
                }
            }
        }
    }

    AccountConfirmationDialogs(
        logoutRequested, { logoutRequested = false },
        {
            logoutRequested = false
            if (accountModel.sessionKey == sessionKey && accountModel.state.value.sessionStatus == sessionStatus) accountModel.logout()
        },
        removeTarget, { removeTarget = null },
        { entry -> removeTarget = null; accountModel.setFavorite(entry.card, false, ownerKey) },
        accountLabel = if (state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY)
            state.websiteProfile?.nickname?.let { "官网账号「$it」的本机登录会话" } ?: "本机保存的官网登录会话"
        else state.profile?.let { "${it.nickname.ifBlank { "红果用户" }}（账号 ${it.id}）" },
        localReset = localResetRequired,
    )
}

@Composable
private fun AccountCinemaTopBar(
    statusTop: Dp,
    overHero: Boolean,
    signedIn: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAccountSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    val barBg by animateColorAsState(if (overHero) AccountHeroStart else Canvas, tween(200), label = "account_bar_bg")
    val titleColor by animateColorAsState(if (overHero) Color.White else Ink, tween(200), label = "account_bar_fg")
    val buttonBg by animateColorAsState(
        if (overHero) Color.White.copy(alpha = 0.16f) else Cream,
        tween(200),
        label = "account_bar_btn_bg",
    )
    val buttonBorder by animateColorAsState(
        if (overHero) Color.White.copy(alpha = 0.22f) else Line,
        tween(200),
        label = "account_bar_btn_border",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().background(barBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusTop + 6.dp, bottom = 8.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(buttonBg)
                    .border(1.dp, buttonBorder, CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "返回" }
                    .testTag("account_back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(20.dp), tint = titleColor)
            }
            Text(
                text = "我的账号",
                color = titleColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (signedIn) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .border(1.dp, buttonBorder, CircleShape)
                        .clickable(enabled = !busy, role = Role.Button, onClick = onRefresh)
                        .semantics { contentDescription = "刷新账号片单" }
                        .testTag("account_refresh"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = titleColor)
                    } else {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp), tint = titleColor)
                    }
                }
                Box {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(buttonBg)
                            .border(1.dp, buttonBorder, CircleShape)
                            .clickable(role = Role.Button) { menuOpen = true }
                            .semantics { contentDescription = "账号操作" }
                            .testTag("account_menu"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.MoreVert, null, modifier = Modifier.size(18.dp), tint = titleColor)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("账号设置") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("account_open_settings"),
                            onClick = { menuOpen = false; onAccountSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("退出账号") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("account_logout"),
                            onClick = { menuOpen = false; onLogout() },
                        )
                    }
                }
            }
        }
        if (!overHero) {
            HorizontalDivider(color = Line.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun AccountSignedInHero(
    state: AccountState,
    onAccountSettings: () -> Unit,
) {
    val profile = state.profile ?: return
    val displayName = profile.nickname.ifBlank { "红果用户" }
    val stats = state.stats?.takeIf { it.follows != null || it.fans != null || it.likes != null }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to AccountHeroStart,
                    0.76f to AccountHeroEnd,
                    1.0f to Canvas,
                ),
            ),
    ) {
        // Warm glow & subtle brand watermark, kept below the top edge so stickyHeader blends seamlessly
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .offset(x = 24.dp, y = (-4).dp)
                    .size(164.dp)
                    .background(Brush.radialGradient(listOf(AccountHeroGlow, Color.Transparent))),
            )
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 16.dp, y = 8.dp)
                    .size(96.dp)
                    .alpha(0.08f),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(26.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Canvas))),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFF06C49), Color(0xFFD94126))))
                        .border(2.5.dp, Color.White.copy(alpha = 0.52f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName.trim().firstOrNull()?.toString() ?: "果",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    AccountAvatarImage(profile.avatarUrl)
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("account_profile"),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.22f),
                        ) {
                            Text(
                                text = "红果号 ${profile.id}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (state.syncEnabled) Color(0x5730D158) else Color.White.copy(alpha = 0.16f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .border(
                                    1.dp,
                                    if (state.syncEnabled) Color(0x7382F5A0) else Color.White.copy(alpha = 0.26f),
                                    CircleShape,
                                )
                                .clickable(role = Role.Button, onClick = onAccountSettings),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (state.syncEnabled) Color(0xFF6BF096) else Color.White.copy(alpha = 0.65f)),
                                )
                                Text(
                                    text = if (state.syncEnabled) "同步已开启" else "同步已关闭",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (stats != null) {
                val statsShape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(statsShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), statsShape)
                        .padding(vertical = 11.dp)
                        .testTag("account_stats"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val items = listOf("关注" to stats.follows, "粉丝" to stats.fans, "获赞" to stats.likes)
                    items.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(Color.White.copy(alpha = 0.22f)),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = value?.toString() ?: "–",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(label, color = Color.White.copy(alpha = 0.84f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountBrandHero(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to AccountHeroStart,
                    0.78f to AccountHeroEnd,
                    1.0f to Canvas,
                ),
            ),
    ) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .offset(x = 24.dp, y = (-4).dp)
                    .size(164.dp)
                    .background(Brush.radialGradient(listOf(AccountHeroGlow, Color.Transparent))),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(26.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Canvas))),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val badgeShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(12.dp, badgeShape, ambientColor = Color.Black.copy(alpha = 0.25f))
                    .clip(badgeShape)
                    .background(Color.White.copy(alpha = 0.96f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(44.dp))
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Cinema-styled watch history card replacing the plain TextButton, echoing LibraryScreen's TheaterResumeCard. */
@Composable
private fun AccountHistorySpotlightCard(
    latest: LibraryEntry?,
    historyCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF2B1814))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .testTag("account_open_history")
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (latest != null) {
            val ep = latest.episodeIndex.coerceAtLeast(1)
            val pct = if (latest.card.episodeCount > 0) {
                ((ep.toFloat() / latest.card.episodeCount).coerceIn(0.01f, 1f) * 100).toInt()
            } else null
            Cover(
                url = latest.card.cover,
                title = latest.card.title,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(DramaPosterRatio)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.13f),
                ) {
                    Text(
                        text = if (historyCount > 0) "观看历史 · 共 $historyCount 部" else "观看历史",
                        color = Color(0xFFFFCEB6),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = latest.card.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = latest.completedCaption ?: "第 $ep 集 · ${libraryPositionLabel(latest.positionMs)}",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WatchProgress(
                        entry = latest,
                        modifier = Modifier.weight(1f),
                        track = Color.White.copy(alpha = 0.24f),
                        fill = Color(0xFFFF896D),
                    )
                    if (pct != null) {
                        Text(
                            text = "$pct%",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Orange),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.History, null, Modifier.size(18.dp), tint = OnOrange)
                }
                Text("全部", color = Color.White.copy(alpha = 0.76f), fontSize = 10.sp)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.History, null, Modifier.size(20.dp), tint = Color(0xFFFFCEB6))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("观看历史", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("观看过的短剧会统一记录在这里", color = Color.White.copy(alpha = 0.74f), fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun AccountPendingProgressCard(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(StatusWarnSoft)
            .border(1.dp, StatusWarn.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp)
            .testTag("account_pending_progress"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.CloudSync, null, Modifier.size(17.dp), tint = StatusWarn)
        Text(
            text = "还有 $count 条观看进度待确认 · 前往账号设置查看",
            color = Ink,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = StatusWarn)
    }
}

@Composable
private fun AccountTopNoticeCard(
    message: String?,
    diagnostic: String?,
    diagnosticCopied: Boolean,
    onCopyDiagnostic: (String) -> Unit,
    onConsume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = Cream,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Line, shape),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            message?.let { text ->
                Text(text, color = Ink, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("account_message"))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                diagnostic?.let { info ->
                    TextButton(
                        onClick = { onCopyDiagnostic(info) },
                        modifier = Modifier.testTag("account_copy_diagnostic"),
                    ) { Text("复制诊断信息", fontSize = 12.sp) }
                    if (diagnosticCopied) {
                        Text(
                            "诊断信息已复制",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("account_diagnostic_copied"),
                        )
                    }
                }
                if (message != null) {
                    TextButton(onClick = onConsume) { Text("知道了", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun AccountAvatar(url: String?, size: Dp, initial: String? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (initial != null) SoftOrange else Cream)
            .border(1.dp, if (initial != null) AccentBorder else Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!initial.isNullOrBlank()) {
            Text(initial, color = AccentInk, fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(size * 0.56f), tint = Muted)
        }
        AccountAvatarImage(url)
    }
}

internal fun decodeAccountQr(bytes: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
    if (bytes == null || bytes.size > 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
}
