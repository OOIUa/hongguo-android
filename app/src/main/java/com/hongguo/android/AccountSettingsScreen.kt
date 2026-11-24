package com.hongguo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountState
import com.hongguo.account.AccountViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun accountSettingsStatusPill(state: AccountState): String = when (state.sessionStatus) {
    AccountSessionStatus.LOGGED_IN -> if (state.syncEnabled) "同步已开" else "已登录"
    AccountSessionStatus.WEBSITE_ONLY -> "待连接"
    AccountSessionStatus.NEEDS_VERIFICATION -> "待验证"
    AccountSessionStatus.RESTORING -> "确认中"
    AccountSessionStatus.SIGNED_OUT -> if (state.localClearFailed) "需清理" else "未登录"
}

internal fun formatAccountSyncTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}

/**
 * 软件设置 › 账号设置:
 * 账号状态概览、同步此后观看进度开关、待上报进度与手动刷新、云片单入口及退出账号管理。
 */
@Composable
internal fun AccountSettingsScreen(
    accountModel: AccountViewModel,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit,
) {
    val state by accountModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sessionKey = accountModel.sessionKey
    val ownerKey = accountModel.ownerKey
    val sessionStatus = state.sessionStatus
    val signedIn = sessionStatus == AccountSessionStatus.LOGGED_IN && state.profile != null
    val hasSavedSession = signedIn ||
        sessionStatus == AccountSessionStatus.WEBSITE_ONLY ||
        sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION ||
        state.localClearFailed

    var logoutRequested by remember(sessionKey, ownerKey, state.profile?.id, sessionStatus) { mutableStateOf(false) }
    var diagnosticCopied by remember(state.loginDiagnostic) { mutableStateOf(false) }
    var localResetRequired by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message, state.sessionStatus, state.localClearFailed) {
        if (state.localClearFailed) {
            localResetRequired = true
        } else if (state.sessionStatus == AccountSessionStatus.LOGGED_IN ||
            state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY ||
            state.message?.startsWith("已退出账号") == true
        ) {
            localResetRequired = false
        } else if (state.sessionStatus == AccountSessionStatus.SIGNED_OUT &&
            state.message?.let { "本机账号资料无法读取" in it || "本机清除保存失败" in it || "账号资料保存失败" in it } == true
        ) {
            localResetRequired = true
        }
    }

    val cardShape = RoundedCornerShape(22.dp)
    val navClearance = LocalNavClearance.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("account_settings_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("账号设置", "返回软件设置", "account_settings_back", onBack)

        if (state.message != null || state.loginDiagnostic != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Cream,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Line, RoundedCornerShape(18.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.message?.let { message ->
                        Text(
                            text = message,
                            color = Ink,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.testTag("account_settings_message"),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.loginDiagnostic?.let { diagnostic ->
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("红果登录诊断", diagnostic))
                                        diagnosticCopied = true
                                    }
                                },
                                modifier = Modifier.testTag("account_settings_copy_diagnostic"),
                            ) {
                                Text(if (diagnosticCopied) "诊断信息已复制" else "复制诊断信息", fontSize = 12.sp)
                            }
                        }
                        if (state.message != null) {
                            TextButton(onClick = accountModel::consumeMessage) {
                                Text("知道了", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 1. Account Identity & Status Card
        AccountSettingsIdentityCard(
            state = state,
            signedIn = signedIn,
            onOpenAccount = onOpenAccount,
            onOpenSection = { section ->
                accountModel.selectSection(section)
                onOpenAccount()
            },
            onRetryWebsite = accountModel::retryWebsiteAccount,
            onVerifySaved = accountModel::refresh,
        )

        // 2. Watch Progress Sync & Cloud Sync Card
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape)
                .testTag("account_settings_sync_card"),
        ) {
            Column {
                SettingSwitch(
                    title = "同步此后观看进度",
                    subtitle = when {
                        signedIn && state.syncEnabled -> "已开启：此后实际播放的剧集与进度将自动同步至红果账号"
                        signedIn -> "默认关闭：开启后仅同步此后实际播放的进度，不上传本机旧记录"
                        sessionStatus == AccountSessionStatus.WEBSITE_ONLY -> "完成云账号连接后，才可开启观看进度同步"
                        sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION -> "重新验证账号后，可管理观看进度同步"
                        sessionStatus == AccountSessionStatus.RESTORING -> "正在确认账号状态…"
                        else -> "登录红果账号后可开启，开启后不会上传本机旧记录"
                    },
                    checked = signedIn && state.syncEnabled,
                    enabled = signedIn && !state.busy,
                    tag = "account_sync_switch",
                    onChange = accountModel::setSyncEnabled,
                )

                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // Sync status & pending progress queue
                val canFlush = signedIn && state.syncEnabled && state.pendingProgressCount > 0 && !state.busy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account_settings_sync_status")
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (signedIn && state.syncEnabled) StatusOkSoft else Cream),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (signedIn && state.syncEnabled) StatusOk else Muted,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("进度上报状态", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                !signedIn -> "未连接云账号，播放进度仅保存在当前设备"
                                state.pendingProgressCount > 0 -> "还有 ${state.pendingProgressCount} 条观看进度待确认上报"
                                state.lastSyncAt > 0L -> "最近云端同步：${formatAccountSyncTime(state.lastSyncAt)}"
                                state.syncEnabled -> "等待新的播放记录触发自动上报"
                                else -> "已暂停上报，本机观看记录不受影响"
                            },
                            color = if (signedIn && state.pendingProgressCount > 0) StatusWarn else Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            modifier = if (state.pendingProgressCount > 0) Modifier.testTag("account_pending_progress") else Modifier,
                        )
                    }
                    if (canFlush) {
                        Surface(
                            shape = CircleShape,
                            color = SoftOrange,
                            modifier = Modifier
                                .clip(CircleShape)
                                .testTag("account_settings_flush")
                                .clickable { accountModel.flushProgress(ownerKey) },
                        ) {
                            Text(
                                text = "立即上报",
                                color = AccentInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                !signedIn -> Cream
                                state.syncEnabled -> StatusOkSoft
                                else -> Cream
                            },
                        ) {
                            Text(
                                text = when {
                                    !signedIn -> "仅本机"
                                    state.syncEnabled -> "自动上报"
                                    else -> "已关闭"
                                },
                                color = if (signedIn && state.syncEnabled) StatusOk else Muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                // Manual cloud library refresh
                val canRefresh = (signedIn || sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION) && !state.busy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account_settings_refresh")
                        .clickable(enabled = canRefresh, onClick = accountModel::refresh)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (canRefresh) StatusBlueSoft else Cream),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = StatusBlue)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                                tint = if (canRefresh) StatusBlue else Muted,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = if (sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION) "重新验证并刷新账号" else "立即刷新云端片单",
                            color = if (canRefresh || state.busy) Ink else Ink.copy(alpha = 0.55f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                state.busy -> "正在与红果云端同步数据…"
                                signedIn -> "手动拉取最新云端历史、收藏、点赞与预约"
                                sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION -> "恢复网络后重新验证登录态并刷新片单"
                                else -> "登录红果账号后可手动刷新云端片单"
                            },
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (canRefresh) Cream else Color.Transparent,
                    ) {
                        Text(
                            text = if (state.busy) "同步中" else "刷新",
                            color = if (canRefresh) Ink else Muted.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 3. Account & Session Management Card
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account_settings_open_account")
                        .clickable(onClick = onOpenAccount)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Cream),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(19.dp), tint = Ink)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = if (signedIn) "查看云片单与账号主页" else "登录与连接方式",
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (signedIn) "管理云端历史、收藏、点赞与预约作品" else "支持手机验证码、官网登录、扫码与导入登录态",
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                }

                if (hasSavedSession || localResetRequired) {
                    HorizontalDivider(
                        color = Line.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("account_settings_logout")
                            .clickable(enabled = !state.busy) { logoutRequested = true }
                            .padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SoftOrange),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(19.dp), tint = AccentInk)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = when {
                                    localResetRequired -> "清除本机保存的登录态"
                                    sessionStatus == AccountSessionStatus.WEBSITE_ONLY -> "退出已保存的官网登录"
                                    else -> "退出红果账号"
                                },
                                color = AccentInk,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "退出后停止账号同步，本机收藏与观看记录保留",
                                color = Muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                    }
                }
            }
        }

        // 4. Data Isolation & Privacy Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, RoundedCornerShape(18.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp),
                    tint = StatusOk,
                )
                Text(
                    text = "本机收藏与观看记录同账号云片单相互独立；账号凭据仅加密保存在当前设备。开启观看进度同步后，只会上报此后实际播放的进度，不会上传本机旧记录。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }

    AccountConfirmationDialogs(
        logoutRequested = logoutRequested,
        onDismissLogout = { logoutRequested = false },
        onLogout = {
            logoutRequested = false
            if (accountModel.sessionKey == sessionKey && accountModel.state.value.sessionStatus == sessionStatus) {
                accountModel.logout()
            }
        },
        removeTarget = null,
        onDismissRemove = {},
        onRemove = {},
        accountLabel = if (state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY) {
            state.websiteProfile?.nickname?.let { "官网账号「$it」的本机登录会话" } ?: "本机保存的官网登录会话"
        } else {
            state.profile?.let { "${it.nickname.ifBlank { "红果用户" }}（账号 ${it.id}）" }
        },
        localReset = localResetRequired,
    )
}

@Composable
private fun AccountSettingsIdentityCard(
    state: AccountState,
    signedIn: Boolean,
    onOpenAccount: () -> Unit,
    onOpenSection: (AccountSection) -> Unit,
    onRetryWebsite: () -> Unit,
    onVerifySaved: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val status = state.sessionStatus
    val avatarUrl = if (status == AccountSessionStatus.WEBSITE_ONLY) state.websiteProfile?.avatarUrl else state.profile?.avatarUrl
    val displayTitle = when (status) {
        AccountSessionStatus.LOGGED_IN -> state.profile?.nickname?.ifBlank { "红果用户" } ?: "红果用户"
        AccountSessionStatus.WEBSITE_ONLY -> state.websiteProfile?.nickname ?: "官网登录已保存"
        AccountSessionStatus.NEEDS_VERIFICATION -> state.profile?.nickname?.ifBlank { "已保存账号" } ?: "已保存账号"
        AccountSessionStatus.RESTORING -> "正在确认已保存账号"
        AccountSessionStatus.SIGNED_OUT -> "未登录红果账号"
    }
    val statusSubtitle = when (status) {
        AccountSessionStatus.LOGGED_IN -> "红果号 ${state.profile?.id.orEmpty()} · 云片单已连通"
        AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，手机云账号待连接"
        AccountSessionStatus.NEEDS_VERIFICATION -> "登录态需要重新验证后继续同步"
        AccountSessionStatus.RESTORING -> "正在读取本机加密登录态…"
        AccountSessionStatus.SIGNED_OUT -> "登录后可查看云片单并开启进度同步"
    }
    val dotColor = when (status) {
        AccountSessionStatus.LOGGED_IN -> StatusOk
        AccountSessionStatus.WEBSITE_ONLY, AccountSessionStatus.NEEDS_VERIFICATION -> StatusWarn
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(AccountCardStart, AccountCardEnd)))
            .border(1.dp, AccountCardBorder, shape)
            .testTag("account_settings_identity"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAccount)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (signedIn) SoftOrange else Cream)
                        .border(if (signedIn) 1.5.dp else 1.dp, if (signedIn) AccentBorder else Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (signedIn) {
                        val initial = displayTitle.trim().firstOrNull()?.toString() ?: "果"
                        Text(initial, color = AccentInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(24.dp), tint = Muted)
                    }
                    AccountAvatarImage(avatarUrl)
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = displayTitle,
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        dotColor?.let { color ->
                            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                        }
                        Text(
                            text = statusSubtitle,
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = if (signedIn) Cream else SoftOrange,
                ) {
                    Text(
                        text = when (status) {
                            AccountSessionStatus.LOGGED_IN -> "云片单"
                            AccountSessionStatus.SIGNED_OUT -> "去登录"
                            else -> "管理"
                        },
                        color = if (signedIn) Ink else AccentInk,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            if (signedIn) {
                HorizontalDivider(
                    color = Line,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 11.dp),
                ) {
                    AccountQuickStat(
                        value = state.history.size.toString(),
                        label = "云历史",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSection(AccountSection.HISTORY) },
                    )
                    AccountQuickStat(
                        value = state.favorites.size.toString(),
                        label = "云收藏",
                        valueColor = AccentInk,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSection(AccountSection.FAVORITES) },
                    )
                    AccountQuickStat(
                        value = state.likes.size.toString(),
                        label = "点赞",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSection(AccountSection.LIKES) },
                    )
                    AccountQuickStat(
                        value = state.reservations.size.toString(),
                        label = "预约",
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSection(AccountSection.RESERVATIONS) },
                    )
                }
            } else if (status == AccountSessionStatus.WEBSITE_ONLY || status == AccountSessionStatus.NEEDS_VERIFICATION) {
                HorizontalDivider(
                    color = Line,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val isWebsiteOnly = status == AccountSessionStatus.WEBSITE_ONLY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (!state.busy) Orange else Cream,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .testTag(if (isWebsiteOnly) "account_settings_retry_website" else "account_settings_verify")
                            .clickable(enabled = !state.busy) {
                                if (isWebsiteOnly) onRetryWebsite() else onVerifySaved()
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Muted)
                            }
                            Text(
                                text = when {
                                    state.busy && isWebsiteOnly -> "正在连接云账号…"
                                    state.busy -> "正在验证账号…"
                                    isWebsiteOnly -> "重试连接云账号"
                                    else -> "重新验证已保存账号"
                                },
                                color = if (!state.busy) OnOrange else Muted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
