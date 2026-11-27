package com.hongguo.android

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountState
import kotlinx.coroutines.launch

@Composable
internal fun MyScreen(
    state: HongguoUiState,
    model: HongguoViewModel,
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onOpenAccountSection: (AccountSection) -> Unit,
    accountName: String? = null,
    accountStatus: String = "登录后查看收藏、历史与预约",
    accountAvatar: String? = null,
    accountSignedIn: Boolean = false,
) {
    val openSettings = { model.selectTab(AppTab.SETTINGS) }
    val openAbout = { model.selectTab(AppTab.ABOUT) }
    val openLocalFavorites = { model.selectTab(AppTab.LOCAL_FAVORITES) }

    val navClearance = LocalNavClearance.current
    val accountCard: @Composable () -> Unit = {
        RefinedAccountCard(
            accountName = accountName,
            accountStatus = accountStatus,
            accountAvatar = accountAvatar,
            signedIn = accountSignedIn,
            onAccount = { onOpenAccountSection(AccountSection.FAVORITES) },
            onOpenAccountSection = onOpenAccountSection,
        )
    }
    val bento: @Composable () -> Unit = {
        MyBentoSection(
            latest = state.history.firstOrNull(),
            favoriteCount = state.favorites.size,
            historyCount = state.history.size,
            onContinue = { entry ->
                if (entry != null) model.openLibrary(entry) else model.selectTab(AppTab.HOME)
            },
            onFavorites = openLocalFavorites,
            onHistory = model::openPlayHistory,
        )
    }
    val menu: @Composable (Modifier) -> Unit = { menuModifier ->
        MyMainMenuCard(
            themeLabel = state.themeStyle.label,
            updateState = updateState,
            onSettings = openSettings,
            onAbout = openAbout,
            onQuickUpdate = updateModel::checkForUpdates,
            modifier = menuModifier,
        )
    }

    // 横屏：账号卡与快捷数据在左，设置菜单在右；竖屏仍是原来的一整列。
    if (isWideLayout()) {
        val density = LocalDensity.current
        // 右栏只有一张菜单卡，跟左栏的账号卡保持同高。
        var accountCardHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize().testTag("my_screen")) {
        WarmTwoPane(
            left = {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.onSizeChanged { accountCardHeight = with(density) { it.height.toDp() } }) {
                        accountCard()
                    }
                    bento()
                }
            },
            rightScroll = true,
            right = {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + navClearance),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    menu(if (accountCardHeight > 0.dp) Modifier.heightIn(min = accountCardHeight) else Modifier)
                }
            },
        )
        }
    } else {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("my_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        accountCard()
        bento()
        menu(Modifier)
    }
    }
}

@Composable
private fun RefinedAccountCard(
    accountName: String?,
    accountStatus: String,
    accountAvatar: String?,
    signedIn: Boolean,
    onAccount: () -> Unit,
    onOpenAccountSection: (AccountSection) -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(AccountCardStart, AccountCardEnd)))
            .border(1.dp, AccountCardBorder, shape),
    ) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier.offset(x = 28.dp, y = (-32).dp).size(136.dp)
                    .background(Brush.radialGradient(listOf(AccountCardGlow, Color.Transparent))),
            )
            Image(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.offset(x = 12.dp, y = (-12).dp).size(84.dp).alpha(0.05f),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_account_card")
                    .clickable(onClick = onAccount)
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
                        val initial = accountName?.trim()?.firstOrNull()?.toString() ?: "果"
                        Text(initial, color = AccentInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(24.dp), tint = Muted)
                    }
                    AccountAvatarImage(accountAvatar)
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = accountName ?: "登录红果账号",
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
                        if (signedIn) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(StatusOk))
                        }
                        Text(
                            text = accountStatus,
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
                        text = if (signedIn) "我的账号" else "去登录",
                        color = if (signedIn) Ink else AccentInk,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            if (signedIn) {
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 16.dp))
                AccountShortcut("我的预约", Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("my_reservations")) {
                    onOpenAccountSection(AccountSection.RESERVATIONS)
                }
            }
        }
    }
}

@Composable
private fun AccountShortcut(label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(16.dp), tint = Muted)
    }
}

@Composable
internal fun AccountQuickStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun MyBentoSection(
    latest: LibraryEntry?,
    favoriteCount: Int,
    historyCount: Int,
    onContinue: (LibraryEntry?) -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val posterWidth = minOf((maxWidth - 10.dp) * 7f / 12f, 168.dp)
        val titleLines = if (posterWidth < 132.dp * LocalDensity.current.fontScale) 3 else 2
        Row(Modifier.fillMaxWidth().height(posterWidth / DramaPosterRatio), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ContinueWatchingCard(latest, titleLines, Modifier.width(posterWidth), onClick = { onContinue(latest) })
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BentoStatTile(
                    label = "本机收藏",
                    count = favoriteCount,
                    icon = Icons.Outlined.StarBorder,
                    iconTint = AccentInk,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("my_favorites"),
                    onClick = onFavorites,
                )
                BentoStatTile(
                    label = "观看历史",
                    count = historyCount,
                    icon = Icons.Outlined.History,
                    iconTint = StatusBlue,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("my_history"),
                    onClick = onHistory,
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(latest: LibraryEntry?, titleLines: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val progress = latest?.completedCaption ?: when {
        latest == null -> "去首页找部好剧"
        latest.episodeIndex > 0 && latest.card.episodeCount > 0 -> {
            val percent = (100L * latest.episodeIndex / latest.card.episodeCount).coerceIn(1L, 100L)
            "第 ${latest.episodeIndex} 集 · 已看 $percent%"
        }
        latest.episodeIndex > 0 -> "第 ${latest.episodeIndex} 集 · ${libraryPositionLabel(latest.positionMs)}"
        latest.positionMs > 0 -> "看到 ${libraryPositionLabel(latest.positionMs)}"
        else -> "查看剧集"
    }
    Box(
        modifier.fillMaxHeight().clip(shape)
            .background(CardSurface)
            .border(1.dp, Line, shape)
            .testTag("my_continue_watching")
            .clickable(role = Role.Button, onClickLabel = if (latest == null) "前往首页" else "打开剧集详情", onClick = onClick),
    ) {
        if (latest != null) {
            Cover(latest.card.cover, latest.card.title, Modifier.matchParentSize().testTag("my_continue_poster"))
        } else {
            Box(Modifier.matchParentSize().background(SoftOrange).padding(bottom = 48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayCircleOutline, null, modifier = Modifier.size(36.dp), tint = AccentInk)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    .25f to Color.Black.copy(alpha = .68f),
                    1f to Color.Black.copy(alpha = .91f),
                ))
                .padding(start = 11.dp, end = 11.dp, top = 32.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PosterCaption("继续追剧", 10.sp, 12.sp, Color(0xFFFFCEB6), FontWeight.SemiBold)
            PosterCaption(
                latest?.card?.title ?: "还没有观看记录", 13.sp, 20.sp, Color.White, FontWeight.Bold,
                maxLines = titleLines, modifier = Modifier.weight(1f, fill = false),
            )
            PosterCaption(progress, 10.sp, 12.sp, Color.White.copy(alpha = .9f))
            if (latest != null) WatchProgress(latest, Modifier.padding(top = 3.dp),
                track = Color.White.copy(alpha = .32f), fill = Color(0xFFFF896D))
        }
    }
}

@Composable
private fun PosterCaption(
    text: String, minSize: TextUnit, maxSize: TextUnit, color: Color,
    weight: FontWeight = FontWeight.Normal, maxLines: Int = 1, modifier: Modifier = Modifier,
) {
    BasicText(
        text, modifier,
        style = LocalTextStyle.current.copy(color = color, fontWeight = weight, lineHeight = 1.18.em, letterSpacing = 0.sp),
        autoSize = TextAutoSize.StepBased(minSize, maxSize, 1.sp),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BentoStatTile(
    label: String,
    count: Int,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Line, shape)
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = CardSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 部", color = Muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Only two entries on 我的: 软件设置 and 关于软件. */
@Composable
private fun MyMainMenuCard(
    themeLabel: String,
    updateState: UpdateUiState,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onQuickUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val update = updateState.update
    Surface(
        shape = shape,
        color = CardSurface,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Line, shape),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_settings")
                    .clickable(onClick = onSettings)
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
                    Icon(Icons.Outlined.Settings, null, Modifier.size(19.dp), tint = Ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("软件设置", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("外观 · 播放 · 账号 · 存储", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(themeLabel, color = Muted, fontSize = 12.sp, maxLines = 1)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
            }

            HorizontalDivider(
                color = Line.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 64.dp, end = 16.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("my_about")
                    .clickable(onClick = onAbout)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Cream),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(19.dp), tint = Ink)
                    }
                    if (update != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Orange)
                                .border(1.5.dp, CardSurface, CircleShape),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("关于软件", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (update != null) "发现新版本 v${update.versionName} · 版本与许可"
                        else "红果独立版 v${BuildConfig.VERSION_NAME} · 检查更新",
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (update != null) {
                    Surface(
                        shape = CircleShape,
                        color = SoftOrange,
                        modifier = Modifier
                            .testTag("my_update_badge")
                            .clickable(onClick = onQuickUpdate),
                    ) {
                        Text(
                            text = "新版 ${update.versionName}",
                            color = AccentInk,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Text("v${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
            }
        }
    }
}

@Composable
internal fun PageHeader(title: String, backLabel: String, backTag: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, tint = Ink)
        }
        Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** 软件设置: 4 grouped destinations (外观设置 / 播放设置 / 账号设置 / 存储设置), no footer caption. */
@Composable
internal fun SystemSettingsScreen(
    state: HongguoUiState,
    accountState: AccountState = AccountState(),
    onAppearance: () -> Unit,
    onPlayback: () -> Unit,
    onAccount: () -> Unit,
    onStorage: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheBytes = CacheStorage.measure(context).totalBytes }

    val navClearance = LocalNavClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader("软件设置", "返回我的", "settings_back", onBack)
        val shape = RoundedCornerShape(22.dp)
        Surface(
            shape = shape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, shape),
        ) {
            Column {
                SettingsHubRow(
                    title = "外观设置",
                    subtitle = "主题样式、外观模式与底栏样式",
                    pill = state.themeStyle.label,
                    icon = Icons.Outlined.Palette,
                    iconBg = SoftOrange,
                    iconTint = AccentInk,
                    tag = "settings_theme",
                    onClick = onAppearance,
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                )
                SettingsHubRow(
                    title = "播放设置",
                    subtitle = "自动连播、预加载、画中画、倍速与弹幕",
                    pill = playbackSpeedLabel(state.playbackSpeed),
                    icon = Icons.Outlined.PlayCircleOutline,
                    iconBg = StatusBlueSoft,
                    iconTint = StatusBlue,
                    tag = "settings_playback",
                    onClick = onPlayback,
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                )
                SettingsHubRow(
                    title = "账号设置",
                    subtitle = "登录状态、观看进度同步与云端管理",
                    pill = accountSettingsStatusPill(accountState),
                    icon = Icons.Outlined.ManageAccounts,
                    iconBg = StatusWarnSoft,
                    iconTint = StatusWarn,
                    tag = "settings_account",
                    onClick = onAccount,
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                )
                SettingsHubRow(
                    title = "存储设置",
                    subtitle = "查看缓存占用与清理缓存",
                    pill = cacheBytes?.let(CacheStorage::formatBytes) ?: "计算中…",
                    icon = Icons.Outlined.Storage,
                    iconBg = StatusOkSoft,
                    iconTint = StatusOk,
                    tag = "settings_storage",
                    onClick = onStorage,
                )
            }
        }
    }
}

@Composable
private fun SettingsHubRow(
    title: String,
    subtitle: String,
    pill: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = iconTint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(shape = CircleShape, color = Cream) {
            Text(
                text = pill,
                color = Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

/** 外观设置: 主题样式 + 外观模式 + 底栏样式. Preserves theme_screen / theme_option_* / theme_mode_* / settings_navbar_* tags. */
@Composable
internal fun ThemeStyleScreen(state: HongguoUiState, model: HongguoViewModel, onBack: () -> Unit) {
    val cardShape = RoundedCornerShape(22.dp)
    val navClearance = LocalNavClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("theme_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("外观设置", "返回软件设置", "theme_back", onBack)
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("主题样式", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.themeStyle.label, color = AccentInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppThemeStyle.entries.forEach { style ->
                        ThemeOption(
                            style = style,
                            selected = state.themeStyle == style,
                            modifier = Modifier.weight(1f),
                            previewDark = LocalHongguoPalette.current.isDark,
                        ) {
                            model.setThemeStyle(style)
                        }
                    }
                }
                Text(
                    text = state.themeStyle.summary,
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("外观模式", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val current = state.themeMode.orDefaultFor(state.themeStyle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Cream)
                        .padding(4.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = current == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) CardSurface else Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton) { model.setThemeMode(mode) }
                                .testTag("theme_mode_${mode.key}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) AccentInk else Muted,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            )
                        }
                    }
                }
                Text(
                    text = "三套主题均有浅色与深色；沉浸暗场的刷剧画面始终保持深色。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            NavBarStyleSetting(state.navBarStyle, model::setNavBarStyle)
        }
    }
}

@Composable
internal fun PlaybackSettingsScreen(state: HongguoUiState, model: HongguoViewModel, onBack: () -> Unit) {
    val cardShape = RoundedCornerShape(22.dp)
    val navClearance = LocalNavClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playback_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("播放设置", "返回软件设置", "playback_back", onBack)
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column {
                SettingSwitch(
                    title = "自动连播下一集",
                    subtitle = "本集结束后自动切集，也可上下滑动切换",
                    checked = state.autoPlayNext,
                    tag = "settings_autoplay",
                    onChange = model::setAutoPlayNext,
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingSwitch(
                    title = "下一集预加载",
                    subtitle = "提前准备少量内容，关闭可节省流量",
                    checked = state.preloadNext,
                    tag = "settings_preload",
                    onChange = model::setPreloadNext,
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SettingSwitch(
                    title = "返回桌面时自动进入画中画",
                    subtitle = "播放时按主页键或用手势返回桌面，可在小窗中继续观看",
                    checked = state.autoPictureInPicture,
                    tag = "settings_auto_pip",
                    onChange = model::setAutoPictureInPicture,
                )
            }
        }
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("默认播放倍速", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = playbackSpeedLabel(state.playbackSpeed),
                        color = AccentInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackSpeeds.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { speed ->
                                val selected = state.playbackSpeed == speed
                                val label = playbackSpeedLabel(speed)
                                val chipShape = RoundedCornerShape(12.dp)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp)
                                        .clip(chipShape)
                                        .background(if (selected) SoftOrange else Canvas)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) Orange else Color.Transparent,
                                            shape = chipShape,
                                        )
                                        .selectable(selected = selected, role = Role.RadioButton) {
                                            model.setPlaybackSpeed(speed)
                                        }
                                        .testTag("settings_speed_$label"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) AccentInk else Ink,
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
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            SettingSwitch(
                title = "默认开启官方弹幕",
                subtitle = "播放页仍可随时微调显示区域、字号与透明度",
                checked = state.danmaku.enabled,
                tag = "settings_danmaku",
                onChange = model::setDanmakuEnabled,
            )
        }
    }
}

/** 存储设置: 实时展示视频/封面/更新包缓存占用并支持单项与一键清理. */
@Composable
internal fun StorageSettingsScreen(
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(CacheSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var clearing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        snapshot = CacheStorage.measure(context)
        loading = false
    }

    fun clearSingle(category: CacheCategory, label: String) {
        if (clearing) return
        if (category == CacheCategory.UPDATES && updateState.downloading) {
            feedback = "更新包正在下载中，暂不可清理"
            return
        }
        val before = when (category) {
            CacheCategory.MEDIA -> snapshot.mediaBytes
            CacheCategory.IMAGE -> snapshot.imageBytes
            CacheCategory.UPDATES -> snapshot.updatesBytes
        }
        if (before <= 0L) return
        clearing = true
        scope.launch {
            snapshot = CacheStorage.clear(context, category, allowUpdatesCleanup = !updateState.downloading)
            if (category == CacheCategory.UPDATES && !updateState.downloading) {
                updateModel.clearDownloadedFile()
            }
            feedback = "已清理$label（释放 ${CacheStorage.formatBytes(before)}）"
            clearing = false
        }
    }

    fun clearAll() {
        if (clearing || snapshot.totalBytes <= 0L) return
        val before = snapshot.totalBytes
        clearing = true
        scope.launch {
            val allowUpdates = !updateState.downloading
            snapshot = CacheStorage.clearAll(context, allowUpdatesCleanup = allowUpdates)
            if (allowUpdates) updateModel.clearDownloadedFile()
            val freed = (before - snapshot.totalBytes).coerceAtLeast(0L)
            feedback = if (allowUpdates) "已清理全部缓存（释放 ${CacheStorage.formatBytes(freed)}）"
            else "已清理视频与图片缓存，正在下载的更新包已跳过"
            clearing = false
        }
    }

    val cardShape = RoundedCornerShape(22.dp)
    val navClearance = LocalNavClearance.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("storage_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("存储设置", "返回软件设置", "storage_back", onBack)
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("当前缓存占用", color = Muted, fontSize = 12.sp)
                        Text(
                            text = if (loading) "计算中…" else CacheStorage.formatBytes(snapshot.totalBytes),
                            color = Ink,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Surface(shape = CircleShape, color = Cream) {
                        Text(
                            text = if (snapshot.totalBytes > 0L) "均可安全清理" else "空间整洁",
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // 3-segment proportional bar
                val total = snapshot.totalBytes.coerceAtLeast(1L).toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Cream),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (snapshot.mediaBytes > 0L) {
                        Box(
                            Modifier
                                .weight((snapshot.mediaBytes / total).coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .background(Orange),
                        )
                    }
                    if (snapshot.imageBytes > 0L) {
                        Box(
                            Modifier
                                .weight((snapshot.imageBytes / total).coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .background(StatusBlue),
                        )
                    }
                    if (snapshot.updatesBytes > 0L) {
                        Box(
                            Modifier
                                .weight((snapshot.updatesBytes / total).coerceAtLeast(0.03f))
                                .fillMaxHeight()
                                .background(StatusOk),
                        )
                    }
                }

                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CacheLegendDot("视频", CacheStorage.formatBytes(snapshot.mediaBytes), Orange)
                    CacheLegendDot("图片", CacheStorage.formatBytes(snapshot.imageBytes), StatusBlue)
                    CacheLegendDot("安装包", CacheStorage.formatBytes(snapshot.updatesBytes), StatusOk)
                }

                val canClearAll = !loading && !clearing && snapshot.totalBytes > 0L
                Surface(
                    shape = CircleShape,
                    color = if (canClearAll) Orange else Cream,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .testTag("storage_clear_all")
                        .clickable(enabled = canClearAll, onClick = ::clearAll),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        if (clearing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Muted)
                        } else {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                null,
                                Modifier.size(17.dp),
                                tint = if (canClearAll) OnOrange else Muted,
                            )
                        }
                        Text(
                            text = when {
                                clearing -> "正在清理缓存…"
                                canClearAll -> "一键清理全部缓存（${CacheStorage.formatBytes(snapshot.totalBytes)}）"
                                else -> "缓存已清空"
                            },
                            color = if (canClearAll) OnOrange else Muted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                feedback?.let { message ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StatusOkSoft,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(16.dp), tint = StatusOk)
                            Text(message, color = StatusOk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Column {
                CacheItemRow(
                    title = "视频播放缓存",
                    subtitle = "剧集分段缓冲与预加载文件",
                    bytes = snapshot.mediaBytes,
                    dotColor = Orange,
                    enabled = !loading && !clearing && snapshot.mediaBytes > 0L,
                    tag = "storage_clear_media",
                    onClear = { clearSingle(CacheCategory.MEDIA, "视频播放缓存") },
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CacheItemRow(
                    title = "封面图片缓存",
                    subtitle = "首页、榜单与片单剧集封面",
                    bytes = snapshot.imageBytes,
                    dotColor = StatusBlue,
                    enabled = !loading && !clearing && snapshot.imageBytes > 0L,
                    tag = "storage_clear_image",
                    onClear = { clearSingle(CacheCategory.IMAGE, "封面图片缓存") },
                )
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                CacheItemRow(
                    title = "更新安装包",
                    subtitle = if (updateState.downloading) "正在下载更新包，暂不可清理" else "已下载待安装或残留的 APK",
                    bytes = snapshot.updatesBytes,
                    dotColor = StatusOk,
                    enabled = !loading && !clearing && !updateState.downloading && snapshot.updatesBytes > 0L,
                    tag = "storage_clear_updates",
                    onClear = { clearSingle(CacheCategory.UPDATES, "更新安装包") },
                )
            }
        }
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
                    Icons.Outlined.VerifiedUser,
                    null,
                    Modifier.padding(top = 1.dp).size(16.dp),
                    tint = StatusOk,
                )
                Text(
                    text = "清理缓存仅删除临时播放与图片文件，不会移除登录状态、本机收藏和观看记录。",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

@Composable
private fun CacheLegendDot(label: String, size: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("$label $size", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CacheItemRow(
    title: String,
    subtitle: String,
    bytes: Long,
    dotColor: Color,
    enabled: Boolean,
    tag: String,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(CacheStorage.formatBytes(bytes), color = Muted, fontSize = 12.sp)
            }
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(
            shape = CircleShape,
            color = if (enabled) Cream else Color.Transparent,
            modifier = Modifier
                .clip(CircleShape)
                .testTag(tag)
                .clickable(enabled = enabled, onClick = onClear),
        ) {
            Text(
                text = if (bytes > 0L) "清理" else "已清",
                color = if (enabled) Ink else Muted.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** 关于软件: 品牌版本卡 + 检查更新 / 启动查新 / 更新线路 + 开源许可. */
@Composable
internal fun AboutSoftwareScreen(
    updateState: UpdateUiState,
    updateModel: UpdateViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(22.dp)
    val navClearance = LocalNavClearance.current
    val update = updateState.update

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("about_screen")
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp + navClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("关于软件", "返回我的", "about_back", onBack)
        Surface(
            shape = cardShape,
            color = CardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Line, cardShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Canvas)
                        .border(1.dp, Line, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("红果独立版", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Android ${BuildConfig.VERSION_NAME}（Build ${BuildConfig.VERSION_CODE}）",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
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
                        .testTag("update_check")
                        .clickable(onClick = updateModel::checkForUpdates)
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
                        Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(19.dp), tint = AccentInk)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("检查更新", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                updateState.checking -> "正在检查新版本…"
                                update != null -> "发现新版 v${update.versionName}，点此下载"
                                else -> "当前已是最新版本"
                            },
                            color = Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (updateState.checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentInk)
                    } else if (update != null) {
                        Surface(shape = CircleShape, color = Orange) {
                            Text(
                                text = "NEW",
                                color = OnOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
                }

                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                SettingSwitch(
                    title = "启动时检查更新",
                    subtitle = "后台静默检查，下载与安装仍需确认",
                    checked = updateState.automaticChecksEnabled,
                    tag = "settings_auto_update",
                    onChange = updateModel::setAutomaticChecksEnabled,
                )

                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("about_update_source")
                        .clickable(enabled = !updateState.downloading) {
                            val entries = UpdateSource.entries
                            val next = entries[(entries.indexOf(updateState.source) + 1) % entries.size]
                            updateModel.selectSource(next)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("更新线路", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(updateState.source.label, color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
                    }
                }
            }
        }
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
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPOSITORY}")),
                                )
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("开源项目与发布页", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(BuildConfig.UPDATE_REPOSITORY, color = Muted, fontSize = 12.sp)
                }
                HorizontalDivider(
                    color = Line.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("开源许可", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("GPL-3.0-only", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun ThemeOption(
    style: AppThemeStyle,
    selected: Boolean,
    modifier: Modifier,
    previewDark: Boolean? = null,
    onSelect: () -> Unit,
) {
    val ring = if (selected) Orange else Color.Transparent
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics { contentDescription = "${style.label}主题" }
            .testTag("theme_option_${style.key}")
            .border(2.dp, ring, RoundedCornerShape(16.dp))
            .background(Canvas)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreview(style, previewDark)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) {
            if (selected) Box(Modifier.size(18.dp).clip(CircleShape).background(Orange), Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = OnOrange)
            } else Box(Modifier.size(18.dp).padding(1.dp).border(2.dp, Muted, CircleShape))
            Text(
                text = style.label,
                color = Ink,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private val PreviewTones = listOf(
    Color(0xFF7B3B2C), Color(0xFF8A4F62), Color(0xFF5B4636), Color(0xFF3E4A61),
    Color(0xFF2F5D50), Color(0xFF7A5C2E), Color(0xFF4B3F6B),
)

/** A miniature of each theme's home screen, always drawn in that theme's own colors. */
@Composable
private fun ThemePreview(style: AppThemeStyle, dark: Boolean?) {
    val p = if (dark == null) style.palette() else style.palette(dark)
    Box(
        Modifier
            .widthIn(max = 116.dp)
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(p.canvas)
            .border(1.dp, p.line, RoundedCornerShape(10.dp)),
    ) {
        when (style) {
            AppThemeStyle.IMMERSIVE -> {
                Box(Modifier.fillMaxSize().background(PreviewTones[0]))
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))),
                )
                Column(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = .85f))) }
                }
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(Color.White))
                    Box(Modifier.width(30.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .6f)))
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp).background(p.canvas))
            }
            AppThemeStyle.WARM -> Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(p.cream))
                Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(4.dp)).background(PreviewTones[5]))
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(3) { col ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(.75f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(PreviewTones[(row * 3 + col) % PreviewTones.size]),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                PreviewNav(p)
            }
            AppThemeStyle.CARDS -> Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(p.card))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(p.card)
                        .padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(Modifier.fillMaxWidth(.7f).height(3.dp).background(p.accent))
                    Box(Modifier.fillMaxWidth(.85f).height(3.dp).background(p.line))
                    Box(Modifier.fillMaxWidth(.6f).height(3.dp).background(p.line))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1.4f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[5]))
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[2]))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[1]))
                        Box(Modifier.fillMaxWidth().weight(1.4f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[6]))
                    }
                }
                PreviewNav(p)
            }
        }
    }
}

@Composable
private fun PreviewNav(p: HongguoPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(9.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(p.card),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { i ->
            Box(
                Modifier
                    .size(width = 6.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(if (i == 0) p.accent else p.line),
            )
        }
    }
}

/** 底栏样式: two-way choice, applied immediately so the bar below changes while the user watches. */
@Composable
private fun NavBarStyleSetting(selected: NavBarStyle, onSelect: (NavBarStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("底栏样式", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("悬浮玻璃：页面在半透明胶囊下滚动，Android 12 及以上有模糊效果", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        val pill = rememberTabIndicator()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Cream)
                .padding(3.dp)
                .slidingPill(pill, selected, CardSurface, corner = 10.dp)
                .selectableGroup(),
        ) {
            NavBarStyle.entries.forEach { style ->
                val isSelected = style == selected
                Box(
                    Modifier
                        .weight(1f)
                        .tabAnchor(pill, style)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(style) }
                        .testTag("settings_navbar_${style.key}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = style.label,
                        color = if (isSelected) Ink else Muted,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = title,
                color = if (enabled) Ink else Ink.copy(alpha = 0.55f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = Muted,
                uncheckedBorderColor = Muted,
                uncheckedTrackColor = Cream,
            ),
        )
    }
}

/** Account avatar over its placeholder; the placeholder stays while loading, without an avatar, or when the image fails. */
@Composable
internal fun AccountAvatarImage(url: String?) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
        contentDescription = "账号头像",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}
