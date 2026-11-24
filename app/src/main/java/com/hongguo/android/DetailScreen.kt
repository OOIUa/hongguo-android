package com.hongguo.android

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hongguo.account.AccountViewModel

@Composable
internal fun DetailScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaDetailScreen(state = state, model = model, accountModel = accountModel, rootTag = "detail_screen")
}

/**
 * C1 柔焦铺底 + 居中完整 3:4 立体大海报详情页（全 3 套主题 × 深浅 6 色板统一适配）。
 * 底层使用柔焦海报铺满通顶（状态栏全程透明），前景居中完整展示无裁切海报并支持点击看原图；
 * 剧名、标签与操作按钮全部置于海报下方，零遮挡画面主体。
 */
@Composable
internal fun CinemaDetailScreen(
    state: HongguoUiState,
    model: HongguoViewModel,
    accountModel: AccountViewModel,
    rootTag: String = "detail_screen",
) {
    val card = state.selectedCard ?: return
    val detail = state.detail
    val coverUrl = detail?.cover?.takeIf { it.isNotBlank() } ?: card.cover
    val title = detail?.title?.takeIf { it.isNotBlank() } ?: card.title
    val totalEpisodes = detail?.episodes?.size ?: card.episodeCount
    val favorite = state.favorites.any { it.card.id == card.id }

    var range by rememberSaveable(card.id) { mutableIntStateOf(-1) }
    var reverseOrder by rememberSaveable(card.id) { mutableStateOf(false) }
    var summaryExpanded by rememberSaveable(card.id) { mutableStateOf(false) }
    var posterPreviewOpen by rememberSaveable(card.id) { mutableStateOf(false) }
    // Adaptive portrait ratio matching the poster's intrinsic ratio (defaults to 3:4 = 0.75f).
    var posterRatio by remember(coverUrl) { mutableFloatStateOf(0.75f) }

    val list = rememberLazyListState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val episodeLabel = detail?.episodes?.getOrNull(state.episodePosition)?.index
        ?: detail?.episodes?.firstOrNull()?.index

    Box(Modifier.fillMaxSize().background(Canvas)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(rootTag),
            state = list,
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. C1 Hero: Full-bleed blurred ambient backdrop + Centered uncut 3:4 framed poster
            item(key = "hero") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Ambient blurred backdrop running edge-to-edge under the transparent status bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusTop + 308.dp)
                            .clip(RoundedCornerShape(0.dp)),
                    ) {
                        val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.24f
                                    scaleY = 1.24f
                                }
                                .blur(28.dp)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = 1.18f
                                    scaleY = 1.18f
                                    alpha = 0.55f
                                }
                        }
                        if (coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(coverUrl).crossfade(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = blurModifier,
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(listOf(Color(0xFF4A251D), Canvas))),
                            )
                        }
                        // Soft ambient vignette & seamless bottom melt into current theme Canvas
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0.0f to Color.Black.copy(alpha = 0.36f),
                                        0.48f to Color.Black.copy(alpha = 0.16f),
                                        1.0f to Canvas,
                                    ),
                                ),
                        )
                    }

                    // Foreground centered uncut vertical poster card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = statusTop + 44.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val posterShape = RoundedCornerShape(18.dp)
                        Box(
                            modifier = Modifier
                                .width(188.dp)
                                .aspectRatio(posterRatio)
                                .shadow(
                                    elevation = 18.dp,
                                    shape = posterShape,
                                    ambientColor = Color.Black.copy(alpha = 0.42f),
                                    spotColor = Color.Black.copy(alpha = 0.42f),
                                )
                                .clip(posterShape)
                                .background(Color(0xFF1C1715))
                                .border(1.dp, Color.White.copy(alpha = 0.24f), posterShape)
                                .testTag("detail_poster")
                                .clickable(enabled = coverUrl.isNotBlank()) { posterPreviewOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(coverUrl).crossfade(true).build(),
                                    contentDescription = "$title 完整海报",
                                    contentScale = ContentScale.Fit,
                                    onSuccess = { result ->
                                        val w = result.painter.intrinsicSize.width
                                        val h = result.painter.intrinsicSize.height
                                        if (w > 0f && h > 0f && w.isFinite() && h.isFinite()) {
                                            posterRatio = (w / h).coerceIn(0.62f, 0.85f)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.52f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                ) {
                                    Text(
                                        text = "看原图",
                                        color = Color.White.copy(alpha = 0.92f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            } else {
                                Cover("", title, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }

            // 2. Centered Title, Meta & Action Row cleanly BELOW the poster
            item(key = "title_and_actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = title,
                            color = Ink,
                            fontSize = 21.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when {
                                    totalEpisodes > 0 -> "全 $totalEpisodes 集"
                                    state.loadingDetail -> "正在读取剧集"
                                    else -> "短剧详情"
                                },
                                color = Muted,
                                fontSize = 12.sp,
                            )
                            if (episodeLabel != null) {
                                Text("·", color = Muted, fontSize = 12.sp)
                                Text(
                                    text = "看至第 $episodeLabel 集",
                                    color = AccentInk,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text("·", color = Muted, fontSize = 12.sp)
                            Text(
                                text = if (state.autoPlayNext) "自动连播" else "单集播放",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = model::openPlayer,
                            enabled = detail != null && detail.episodes.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Orange,
                                contentColor = OnOrange,
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("open_player"),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    episodeLabel != null -> "立即播放 第 $episodeLabel 集"
                                    detail != null -> "暂无可播放剧集"
                                    state.loadingDetail -> "正在读取选集…"
                                    else -> "暂时无法播放"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Compact Local Favorite Square Button
                        Surface(
                            onClick = { model.toggleFavorite(card) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (favorite) SoftOrange else CardSurface,
                            border = BorderStroke(1.dp, if (favorite) AccentBorder else Line),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("favorite_toggle")
                                .semantics { contentDescription = if (favorite) "取消本机收藏" else "加入本机收藏" },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = if (favorite) AccentInk else Ink,
                                )
                            }
                        }

                        // Compact Cloud Favorite Square Button
                        AccountFavoriteIconButton(
                            card = card,
                            model = accountModel,
                            onSignIn = { model.selectTab(AppTab.ACCOUNT) },
                        )
                    }
                }
            }

            if (state.loadingDetail) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentInk)
                        Text("正在获取完整选集…", Modifier.padding(start = 10.dp), color = Muted, fontSize = 14.sp)
                    }
                }
            }

            state.detailError?.let { error ->
                item(key = "error") {
                    Box(Modifier.padding(horizontal = 16.dp)) { FailurePanel(error, model::retryDetails) }
                }
            }

            if (detail != null) {
                // 3. Episode Selector right near the top for fast access
                if (detail.episodes.isNotEmpty()) {
                    val orderedIndices = if (reverseOrder) detail.episodes.indices.reversed() else detail.episodes.indices.toList()
                    val ranges = orderedIndices.chunked(30)
                    val currentRange = if (range in ranges.indices) range
                    else ranges.indexOfFirst { state.episodePosition in it }.coerceAtLeast(0)

                    item(key = "episode_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("选集", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (ranges.size > 1) {
                                    ranges.forEachIndexed { i, indices ->
                                        val firstEp = detail.episodes[indices.first()].index
                                        val lastEp = detail.episodes[indices.last()].index
                                        val selected = i == currentRange
                                        Text(
                                            text = "$firstEp-$lastEp",
                                            color = if (selected) AccentInk else Muted,
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier
                                                .quietSelectable(selected) { range = i }
                                                .heightIn(min = 36.dp)
                                                .wrapContentHeight()
                                                .testTag("episode_range_$i"),
                                        )
                                    }
                                }
                            }
                            if (detail.episodes.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            reverseOrder = !reverseOrder
                                            range = -1
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("detail_episode_sort"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Outlined.SwapVert, null, Modifier.size(14.dp), tint = Muted)
                                    Text(if (reverseOrder) "倒序" else "正序", color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    val activeIndices = ranges.getOrElse(currentRange) { emptyList() }
                    items(activeIndices.chunked(6), key = { "ep_row_${it.first()}_${reverseOrder}" }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { index ->
                                val selected = state.episodePosition == index
                                Surface(
                                    onClick = { model.playEpisode(index) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) Orange else Cream,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .testTag("episode_$index"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${detail.episodes[index].index}",
                                            color = if (selected) OnOrange else Ink,
                                            fontSize = 14.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // 4. Clean Borderless Expandable Synopsis
                if (detail.summary.isNotBlank()) {
                    item(key = "summary") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("简介", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (detail.summary.length > 68) {
                                    Text(
                                        text = if (summaryExpanded) "收起" else "展开",
                                        color = AccentInk,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { summaryExpanded = !summaryExpanded }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .testTag("detail_summary_toggle"),
                                    )
                                }
                            }
                            Text(
                                text = detail.summary,
                                color = Muted,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                maxLines = if (summaryExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 5. Cast Section (unified across all 3 themes)
                if (detail.cast.isNotEmpty()) {
                    item(key = "cast") {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            CastSection(detail.cast)
                        }
                    }
                }

                // 6. Related Works Section
                if (state.related.isNotEmpty()) {
                    item(key = "related") {
                        RelatedWorksSection(state, model, accountModel, horizontalPadding = 16)
                    }
                }
            }
        }

        // Pinned frosted back button over the transparent status bar
        IconButton(
            onClick = model::closeDetail,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.36f))
                .testTag("detail_back"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
        }

        // Keep light status-bar icons while the blurred backdrop is visibly behind the transparent bar
        DetailStatusBar(list, 260.dp + statusTop)
    }

    if (posterPreviewOpen && coverUrl.isNotBlank()) {
        DetailPosterLightbox(
            url = coverUrl,
            title = title,
            onDismiss = { posterPreviewOpen = false },
        )
    }
}

@Composable
private fun DetailPosterLightbox(url: String, title: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
                .testTag("detail_poster_lightbox"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                    contentDescription = "$title 原图海报",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Text(
                    text = "完整海报 · 点击任意位置关闭",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭原图", tint = Color.White)
            }
        }
    }
}

/** 演员表: 横向头像行，点击演员展开简介；上游未返回数据时隐藏。 */
@Composable
internal fun CastSection(cast: List<CastMember>) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("演员表", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cast, key = { it.id }) { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .width(68.dp)
                        .clickable(enabled = member.intro.isNotBlank()) {
                            expandedId = if (expandedId == member.id) null else member.id
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Cream)
                            .border(1.dp, Line, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (member.avatar.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(member.avatar).crossfade(true).build(),
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                            )
                        } else {
                            Text(member.name.take(1), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        text = member.name,
                        color = Ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (member.role.isBlank()) "演员" else "饰 ${member.role}",
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val selected = cast.firstOrNull { it.id == expandedId }
        if (selected != null && selected.intro.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardSurface,
                border = BorderStroke(1.dp, Line),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(selected.name, color = AccentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(selected.intro, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
