package com.hongguo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.util.Locale

/** Cloud panel owns its scroll container; it never upgrades a website-only session to a cloud owner. */
@Composable
internal fun CloudLibraryContent(
    accountModel: AccountViewModel,
    onOpenLibrary: (LibraryEntry) -> Unit,
    onSignIn: () -> Unit,
) {
    val state by accountModel.state.collectAsStateWithLifecycle()
    val owner = accountModel.ownerKey
    LaunchedEffect(owner, state.sessionStatus) {
        if (owner != null && state.sessionStatus == AccountSessionStatus.LOGGED_IN && !state.busy) accountModel.refresh()
    }
    var removeTarget by remember(owner, state.sessionStatus) { mutableStateOf<LibraryEntry?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("cloud_library"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.message?.let { message -> item { AccountNotice(message, "知道了", accountModel::consumeMessage, tag = "cloud_message") } }
        if (state.sessionStatus == AccountSessionStatus.LOGGED_IN && state.profile != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.profile?.nickname?.ifBlank { "红果账号" }.orEmpty(), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSignIn) { Text("账号管理", fontSize = 14.sp) }
                }
            }
            cloudLibraryItems(state, accountModel, onOpenLibrary, { removeTarget = it }, showTitle = false)
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag("cloud_account_status"),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudQueue, null, Modifier.size(36.dp), tint = Muted)
                    Text(when (state.sessionStatus) {
                        AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，云片单待连接"
                        AccountSessionStatus.NEEDS_VERIFICATION -> "账号需要重新验证"
                        AccountSessionStatus.RESTORING -> "正在确认已保存账号"
                        else -> "连接红果账号，查看云片单"
                    }, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(when (state.sessionStatus) {
                        AccountSessionStatus.WEBSITE_ONLY -> "官网登录已安全保存在本机，连接账号后可查看收藏、历史与预约。"
                        AccountSessionStatus.NEEDS_VERIFICATION -> "已保留账号和上次读取的片单，验证完成后可继续使用。"
                        AccountSessionStatus.RESTORING -> "确认完成后，会显示账号收藏、观看历史与预约。"
                        else -> "本机记录单独保留，登录不会上传本机旧记录。"
                    }, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                    if (state.sessionStatus == AccountSessionStatus.RESTORING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Button(onClick = onSignIn, modifier = Modifier.testTag("cloud_open_account")) {
                        Text(if (state.sessionStatus == AccountSessionStatus.SIGNED_OUT) "登录红果账号" else "打开账号管理", fontSize = 14.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
    AccountConfirmationDialogs(false, {}, {}, removeTarget, { removeTarget = null }, { entry ->
        removeTarget = null
        accountModel.setFavorite(entry.card, false, owner)
    })
}

/** The account's watch history alone, for the 播放历史 page. */
@Composable
internal fun AccountHistoryList(accountModel: AccountViewModel, onOpenLibrary: (LibraryEntry) -> Unit, modifier: Modifier) {
    val state by accountModel.state.collectAsStateWithLifecycle()
    val owner = accountModel.ownerKey
    LaunchedEffect(owner, state.sessionStatus) {
        if (owner != null && state.sessionStatus == AccountSessionStatus.LOGGED_IN && !state.busy) accountModel.refresh()
    }
    LazyColumn(modifier.testTag("play_history_account"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.historyError?.let { item { AccountNotice(it, "重试", accountModel::refresh, !state.busy, "account_list_error") } }
        if (state.history.isEmpty() && state.historyError == null) item {
            AccountNotice(if (state.busy) "正在读取观看记录…" else "暂时没有读取到观看记录", tag = "account_empty")
        }
        items(state.history.chunked(3), key = { row -> row.first().card.id }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry ->
                    AccountLibraryCell(entry, AccountSection.HISTORY, true, false, false, Modifier.weight(1f), { onOpenLibrary(entry) }, {}, {})
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Shared account library entries, also embedded in the account page without nested scrolling. */
internal fun LazyListScope.cloudLibraryItems(
    state: AccountState,
    accountModel: AccountViewModel,
    onOpenLibrary: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    showTitle: Boolean = true,
) {
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (showTitle) "账号片单" else "红果账号记录", color = Ink, fontWeight = FontWeight.SemiBold,
                fontSize = if (showTitle) 18.sp else 14.sp, modifier = Modifier.weight(1f))
            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton(onClick = accountModel::refresh, enabled = !state.busy, modifier = Modifier.testTag("account_refresh")) {
                Text("刷新", fontSize = 14.sp)
            }
        }
        TabRow(selectedTabIndex = state.selectedSection.ordinal, containerColor = Canvas, contentColor = AccentInk,
            divider = { HorizontalDivider(color = Cream) }) {
            listOf(AccountSection.HISTORY to "历史", AccountSection.FAVORITES to "收藏", AccountSection.LIKES to "点赞", AccountSection.RESERVATIONS to "预约").forEach { (section, title) ->
                Tab(selected = state.selectedSection == section, onClick = { accountModel.selectSection(section) },
                    modifier = Modifier.testTag("account_section_${section.name.lowercase(Locale.ROOT)}"),
                    selectedContentColor = AccentInk, unselectedContentColor = Muted,
                    text = { Text(title, fontSize = 16.sp, fontWeight = if (state.selectedSection == section) FontWeight.SemiBold else FontWeight.Normal) })
            }
        }
        // The account page shows these in its header.
        if (!showTitle) accountStatsLine(state)?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp).testTag("account_stats")) }
    }
    val entries = when (state.selectedSection) {
        AccountSection.HISTORY -> state.history
        AccountSection.FAVORITES -> state.favorites
        AccountSection.LIKES -> state.likes
        AccountSection.RESERVATIONS -> state.reservations
    }
    val error = when (state.selectedSection) {
        AccountSection.HISTORY -> state.historyError
        AccountSection.FAVORITES -> state.favoritesError
        AccountSection.LIKES -> state.likesError
        AccountSection.RESERVATIONS -> state.reservationsError
    }
    error?.let { item { AccountNotice(it, "重试", accountModel::refresh, !state.busy, "account_list_error") } }
    if (entries.isEmpty() && error == null) item {
        AccountNotice(if (state.busy) "正在读取账号片单…" else when (state.selectedSection) {
            AccountSection.HISTORY -> "暂时没有读取到观看记录"
            AccountSection.FAVORITES -> "暂时没有读取到收藏作品"
            AccountSection.LIKES -> "暂时没有读取到点赞作品"
            AccountSection.RESERVATIONS -> "暂时没有读取到预约作品"
        }, tag = "account_empty")
    }
    items(entries.chunked(3), key = { row -> state.selectedSection.name + ":" + row.first().card.id }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { entry ->
                AccountLibraryCell(entry, state.selectedSection, state.selectedSection != AccountSection.RESERVATIONS || entry.card.id in state.reservationOnlineIds,
                    entry.card.id in state.favoriteUncertainIds, entry.card.id in state.favoriteBusyIds, Modifier.weight(1f),
                    { onOpenLibrary(entry) }, { onRemove(entry) }, { accountModel.resolveFavorite(entry.card.id) },
                    releaseText = state.reservationReleaseTexts[entry.card.id].orEmpty())
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    item {
        Text("历史每次读取最近50条；预约按上线状态各读取20条。收藏与历史各缓存最多200部。与本机片单分别保存。" +
            if (showTitle) "“同步此后观看进度”默认关闭，只同步此后实际播放的进度，不上传本机旧记录。" else "",
            color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 12.dp))
    }
}

/** 红果号与平台计数；计数取不到时不显示。 */
internal fun accountStatsLine(state: AccountState): String? {
    val stats = state.stats
    return listOfNotNull(state.profile?.id?.takeIf { it.isNotBlank() }?.let { "红果号 $it" },
        stats?.follows?.let { "关注 $it" }, stats?.fans?.let { "粉丝 $it" }, stats?.likes?.let { "获赞 $it" })
        .joinToString(" · ").ifEmpty { null }
}

@Composable
private fun AccountLibraryCell(entry: LibraryEntry, section: AccountSection, canOpen: Boolean, uncertain: Boolean, favoriteBusy: Boolean,
                               modifier: Modifier, onOpen: () -> Unit, onRemove: () -> Unit, onResolve: () -> Unit,
                               releaseText: String = "") {
    val count = if (entry.card.episodeCount > 0) "${entry.card.episodeCount} 集" else "查看剧集"
    LibraryPosterCell(entry, when (section) {
        AccountSection.HISTORY -> "第 ${entry.episodeIndex.coerceAtLeast(1)} 集 · ${accountPosition(entry.positionMs)}"
        AccountSection.FAVORITES, AccountSection.LIKES -> count
        AccountSection.RESERVATIONS -> if (canOpen) "已上线" else releaseLabel(releaseText, entry.updatedAt).ifEmpty { "上线时间暂未公布" }
    }, "account_entry_${entry.card.id}", modifier, enabled = canOpen, showProgress = section == AccountSection.HISTORY, onOpen = onOpen, footer = {
        if (section == AccountSection.FAVORITES) {
            if (favoriteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else if (uncertain) TextButton(onClick = onResolve, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("account_favorite_resolve_${entry.card.id}")) { Text("核对状态", fontSize = 12.sp, maxLines = 1) }
            else TextButton(onClick = onRemove, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("account_favorite_remove_${entry.card.id}")) { Text("取消收藏", fontSize = 12.sp, maxLines = 1) }
        }
    })
}

@Composable
internal fun AccountNotice(message: String, action: String? = null, onAction: () -> Unit = {}, enabled: Boolean = true, tag: String) {
    Surface(color = Cream, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
            if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action, fontSize = 14.sp) }
        }
    }
}

private fun accountPosition(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
