package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("cloud_library"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.message?.let { message -> item { AccountNotice(message, "知道了", accountModel::consumeMessage, tag = "cloud_message") } }
        if (state.sessionStatus == AccountSessionStatus.LOGGED_IN && state.profile != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.profile?.nickname?.ifBlank { "红果账号" }.orEmpty(),
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSignIn) { Text("账号管理", fontSize = 14.sp) }
                }
            }
            cloudLibraryItems(state, accountModel, onOpenLibrary, { removeTarget = it }, showTitle = false)
        } else {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag("cloud_account_status"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.CloudQueue, null, Modifier.size(36.dp), tint = Muted)
                    Text(
                        text = when (state.sessionStatus) {
                            AccountSessionStatus.WEBSITE_ONLY -> "官网已登录，云片单待连接"
                            AccountSessionStatus.NEEDS_VERIFICATION -> "账号需要重新验证"
                            AccountSessionStatus.RESTORING -> "正在确认已保存账号"
                            else -> "连接红果账号，查看云片单"
                        },
                        color = Ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = when (state.sessionStatus) {
                            AccountSessionStatus.WEBSITE_ONLY -> "连接账号后可查看收藏、点赞和预约。"
                            AccountSessionStatus.NEEDS_VERIFICATION -> "已保留账号和上次读取的片单，验证完成后可继续使用。"
                            AccountSessionStatus.RESTORING -> "确认完成后会显示账号的收藏、点赞和预约。"
                            else -> "登录后可查看账号的收藏、点赞和预约。"
                        },
                        color = Muted,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )
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

private val cloudLibrarySections = listOf(
    AccountSection.FAVORITES to "收藏",
    AccountSection.LIKES to "点赞",
    AccountSection.RESERVATIONS to "预约",
)

/** Shared account library entries, also embedded in the account page without nested scrolling. */
internal fun LazyListScope.cloudLibraryItems(
    state: AccountState,
    accountModel: AccountViewModel,
    onOpenLibrary: (LibraryEntry) -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    showTitle: Boolean = true,
    showHeaderRefresh: Boolean = true,
    horizontalPadding: Dp = 0.dp,
) {
    val selectedIndex = cloudLibrarySections.indexOfFirst { it.first == state.selectedSection }.coerceAtLeast(0)
    val selectedSection = cloudLibrarySections[selectedIndex].first
    val counts = mapOf(
        AccountSection.FAVORITES to state.favorites.size,
        AccountSection.LIKES to state.likes.size,
        AccountSection.RESERVATIONS to state.reservations.size,
    )

    item(key = "cloud_shelf_tabs") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        ) {
            val tabs = rememberTabIndicator()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .slidingIndicator(tabs, selectedSection, Orange, width = 20.dp, height = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    cloudLibrarySections.forEach { (section, title) ->
                        val selected = selectedSection == section
                        val labelColor by animatedTabColor(selected, Ink, Muted)
                        val countColor by animatedTabColor(selected, AccentInk, Muted)
                        val count = counts[section] ?: 0
                        Row(
                            modifier = Modifier
                                .tabAnchor(tabs, section)
                                .quietSelectable(selected) { accountModel.selectSection(section) }
                                .heightIn(min = 42.dp)
                                .padding(bottom = 10.dp)
                                .testTag("account_section_${section.name.lowercase(Locale.ROOT)}"),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = title,
                                color = labelColor,
                                fontSize = animatedTabSize(selected, 18.sp, 15.sp),
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                            )
                            Text(
                                text = count.toString(),
                                color = countColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 1.dp),
                            )
                        }
                    }
                }
                if (showHeaderRefresh) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(16.dp).padding(bottom = 8.dp), strokeWidth = 2.dp)
                    TextButton(
                        onClick = accountModel::refresh,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("account_refresh"),
                    ) { Text("刷新", fontSize = 13.sp) }
                } else if (showTitle) {
                    Text(
                        text = "账号片单",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 11.dp),
                    )
                }
            }
            HorizontalDivider(color = Line)
            // The account page shows these in its hero header.
            if (!showTitle) {
                accountStatsLine(state)?.let {
                    Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).testTag("account_stats"))
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    val (entries, error, emptyMessage) = when (selectedSection) {
        AccountSection.LIKES -> Triple(state.likes, state.likesError, "暂时没有读取到点赞作品")
        AccountSection.RESERVATIONS -> Triple(state.reservations, state.reservationsError, "暂时没有读取到预约作品")
        else -> Triple(state.favorites, state.favoritesError, "暂时没有读取到收藏作品")
    }
    error?.let {
        item(key = "cloud_shelf_error") {
            Box(Modifier.padding(horizontal = horizontalPadding, vertical = 4.dp)) {
                AccountNotice(it, "重试", accountModel::refresh, !state.busy, "account_list_error")
            }
        }
    }
    if (entries.isEmpty() && error == null) {
        item(key = "cloud_shelf_empty") {
            Box(Modifier.padding(horizontal = horizontalPadding, vertical = 4.dp)) {
                AccountNotice(if (state.busy) "正在读取账号片单…" else emptyMessage, tag = "account_empty")
            }
        }
    }
    items(entries.chunked(3), key = { row -> selectedSection.name + ":" + row.first().card.id }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { entry ->
                AccountLibraryCell(
                    entry = entry,
                    section = selectedSection,
                    canOpen = selectedSection != AccountSection.RESERVATIONS || entry.card.id in state.reservationOnlineIds,
                    uncertain = entry.card.id in state.favoriteUncertainIds,
                    favoriteBusy = entry.card.id in state.favoriteBusyIds,
                    modifier = Modifier.weight(1f),
                    onOpen = { onOpenLibrary(entry) },
                    onRemove = { onRemove(entry) },
                    onResolve = { accountModel.resolveFavorite(entry.card.id) },
                    releaseText = state.reservationReleaseTexts[entry.card.id].orEmpty(),
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 红果号与平台计数；计数取不到时不显示。 */
internal fun accountStatsLine(state: AccountState): String? {
    val stats = state.stats
    return listOfNotNull(
        state.profile?.id?.takeIf { it.isNotBlank() }?.let { "红果号 $it" },
        stats?.follows?.let { "关注 $it" },
        stats?.fans?.let { "粉丝 $it" },
        stats?.likes?.let { "获赞 $it" },
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun AccountLibraryCell(
    entry: LibraryEntry,
    section: AccountSection,
    canOpen: Boolean,
    uncertain: Boolean,
    favoriteBusy: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onResolve: () -> Unit,
    releaseText: String = "",
) {
    val epBadge = if (entry.card.episodeCount > 0) "全 ${entry.card.episodeCount} 集" else null
    val reservationCaption = if (section == AccountSection.RESERVATIONS) {
        if (canOpen) "已上线" else releaseLabel(releaseText, entry.updatedAt).ifEmpty { "上线时间暂未公布" }
    } else null

    Column(
        modifier = modifier
            .clickable(enabled = canOpen, role = Role.Button, onClick = onOpen)
            .testTag("account_entry_${entry.card.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(DramaPosterRatio)
                .clip(RoundedCornerShape(15.dp))
                .background(Cream),
        ) {
            Cover(entry.card.cover, entry.card.title, Modifier.fillMaxSize())
            if (section != AccountSection.RESERVATIONS && epBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.66f)))),
                )
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                ) {
                    Text(
                        text = epBadge,
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = entry.card.title,
            color = Ink,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (reservationCaption != null) {
            Surface(
                shape = CircleShape,
                color = if (canOpen) StatusOkSoft else SoftOrange,
            ) {
                Text(
                    text = reservationCaption,
                    color = if (canOpen) StatusOk else AccentInk,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        if (section == AccountSection.FAVORITES) {
            if (favoriteBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentInk)
            } else if (uncertain) {
                Text(
                    text = "核对状态",
                    color = AccentInk,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onResolve)
                        .padding(vertical = 2.dp)
                        .testTag("account_favorite_resolve_${entry.card.id}"),
                )
            } else {
                Text(
                    text = "取消收藏",
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onRemove)
                        .padding(vertical = 2.dp)
                        .testTag("account_favorite_remove_${entry.card.id}"),
                )
            }
        }
    }
}

@Composable
internal fun AccountNotice(message: String, action: String? = null, onAction: () -> Unit = {}, enabled: Boolean = true, tag: String) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = CardSurface,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, shape),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(message, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
            if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action, fontSize = 14.sp) }
        }
    }
}
