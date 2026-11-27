// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.android.comments.ReviewScoreTier
import com.hongguo.android.comments.SeriesReview
import com.hongguo.android.comments.SeriesReviewFormat
import com.hongguo.android.comments.SeriesReviewPage
import com.hongguo.android.danmaku.DanmakuEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesReviewStats(
    detail: DramaDetail,
    reviewCount: Int?,
    onReviews: () -> Unit,
) {
    val heat = detail.hotScore
    val heatLabel = when {
        heat > 0L -> "${SeriesReviewFormat.compactWan(heat)}热度"
        detail.heatText.isNotBlank() -> detail.heatText.removePrefix("红果热度值").trim()
            .let { if (it.contains("热度")) it else "${it}热度" }
        else -> ""
    }
    val favorites = detail.favorites
    val favoriteLabel = if (favorites > 0L) "${SeriesReviewFormat.compactWan(favorites)}收藏" else ""
    val reviewLabel = reviewCount?.let { "$it 条剧评" } ?: "剧评"
    FlowRow(
        Modifier.fillMaxWidth().testTag("detail_reviews_stats"),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        for (label in listOf(heatLabel, favoriteLabel).filter { it.isNotBlank() }) {
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
        Row(
            Modifier.align(Alignment.CenterVertically).clip(RoundedCornerShape(8.dp))
                .testTag("detail_reviews").clickable(role = Role.Button, onClickLabel = "查看剧评", onClick = onReviews)
                .heightIn(min = 36.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(reviewLabel, color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AccentInk, modifier = Modifier.size(16.dp))
        }
    }
}

/** Retains the loaded pages and cursor when the same reviews switch between a sheet and side panel. */
private class ReviewListState {
    var page by mutableStateOf<SeriesReviewPage?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun load(loader: suspend (Int) -> SeriesReviewPage, initialPage: SeriesReviewPage? = null) {
        val previous = page
        if (loading || previous?.hasMore == false) return
        loading = true
        failed = false
        try {
            val offset = previous?.nextOffset ?: 0
            val batch = if (previous == null && initialPage != null) initialPage else loader(offset)
            val previousItems = previous?.items.orEmpty()
            val items = (previousItems + batch.items).distinctBy { it.id }
            page = batch.copy(
                items = items,
                total = batch.total ?: previous?.total,
                averageScore = batch.averageScore.takeIf { it > 0.0 } ?: previous?.averageScore ?: 0.0,
                bookName = batch.bookName.ifBlank { previous?.bookName.orEmpty() },
                hasMore = batch.hasMore && batch.nextOffset > offset && items.size > previousItems.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesReviewsOverlay(
    seriesId: String,
    seriesTitle: String,
    landscape: Boolean,
    visible: Boolean,
    onDismiss: () -> Unit,
    initialPage: SeriesReviewPage? = null,
    pageLoader: suspend (Int) -> SeriesReviewPage,
) {
    key(seriesId) {
        val reviews = remember { ReviewListState() }
        val list = rememberLazyListState()
        val scope = if (visible) rememberCoroutineScope() else null
        LaunchedEffect(visible) {
            if (visible && reviews.page == null) reviews.load(pageLoader, initialPage)
        }
        val content: @Composable (Modifier) -> Unit = { modifier ->
            SeriesReviewsContent(seriesTitle, reviews, list, onDismiss, modifier) {
                scope?.launch { reviews.load(pageLoader) }
            }
        }
        if (landscape) {
            PlayerSidePanel(visible, onDismiss, tag = "detail_reviews_panel", containerColor = Canvas, contentColor = Ink) {
                content(Modifier.weight(1f).statusBarsPadding())
            }
        } else if (visible) {
            val sheetHeight = (LocalConfiguration.current.screenHeightDp * .86f).dp
            ModalBottomSheet(
                onDismissRequest = onDismiss, containerColor = Canvas, contentColor = Ink,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                content(Modifier.fillMaxWidth().height(sheetHeight).navigationBarsPadding()
                    .padding(horizontal = 20.dp).testTag("detail_reviews_sheet"))
            }
        }
    }
}

@Composable
private fun SeriesReviewsContent(
    seriesTitle: String,
    reviews: ReviewListState,
    list: LazyListState,
    onDismiss: () -> Unit,
    modifier: Modifier,
    onLoad: () -> Unit,
) {
    val page = reviews.page
    val items = page?.items.orEmpty()
    LaunchedEffect(list, page?.nextOffset, page?.hasMore, reviews.loading, reviews.failed) {
        if (page?.hasMore == true && !reviews.loading && !reviews.failed) {
            snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size }.first { it }
            onLoad()
        }
    }
    val scoreLabel = page?.let { SeriesReviewFormat.score(it.averageScore) }?.takeIf { it.isNotEmpty() }?.let { "$it 分" }
    val countLabel = page?.total?.let { "$it 条" }
    val heading = listOfNotNull("剧评", scoreLabel, countLabel).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(heading, color = Ink, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("reviews_title"))
                Text(page?.bookName.orEmpty().ifBlank { seriesTitle }, color = Muted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("reviews_subtitle"))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("reviews_close")) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭剧评", tint = Muted)
            }
        }
        if (items.isEmpty()) {
            val loading = page == null && !reviews.failed
            Box(Modifier.fillMaxWidth().weight(1f).testTag(if (loading) "reviews_loading" else "reviews_empty"),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AccentInk)
                    Text(when {
                        loading -> "正在读取剧评…"
                        reviews.failed -> "剧评加载失败，请稍后重试"
                        else -> "这部剧还没有剧评"
                    }, color = Muted, fontSize = 13.sp)
                    if (reviews.failed) {
                        Text("点击重试", color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).testTag("reviews_retry")
                                .clickable(role = Role.Button, onClick = onLoad).padding(horizontal = 12.dp, vertical = 10.dp))
                    }
                }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).testTag("reviews_list"),
                contentPadding = PaddingValues(vertical = 2.dp)) {
                items(items, key = { it.id }) { review -> SeriesReviewRow(review) }
                item(key = "reviews_footer") {
                    ReviewsFooter(loading = reviews.loading, failed = reviews.failed, hasMore = page?.hasMore == true, onLoad = onLoad)
                }
            }
        }
    }
}

/** 列表最后一项：加载更多/重试/已到末尾，随列表一起滚动，不冻结在视窗底部。 */
@Composable
private fun ReviewsFooter(loading: Boolean, failed: Boolean, hasMore: Boolean, onLoad: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reviews_more"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentInk)
                Text("正在加载更多…", color = Muted, fontSize = 12.sp)
            }
            failed -> Text(
                "加载失败，点击重试",
                color = AccentInk,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLoad)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("reviews_retry"),
            )
            hasMore -> Text(
                "加载更多",
                color = AccentInk,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLoad)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            else -> Text("没有更多剧评了", color = Muted, fontSize = 12.sp)
        }
    }
}

/** Solid score badge colors; the top tier follows the current theme accent. */
private data class ScoreBadge(val background: Color, val text: Color)

@Composable
private fun scoreBadge(score: Double): ScoreBadge = when (SeriesReviewFormat.scoreTier(score)) {
    ReviewScoreTier.GRAY -> ScoreBadge(Color(0xFFB0B5BC), Color.Black)
    ReviewScoreTier.YELLOW -> ScoreBadge(Color(0xFFFFC01F), Color(0xFF221A14))
    ReviewScoreTier.ORANGE -> ScoreBadge(Color(0xFFF5821F), Color(0xFF221A14))
    ReviewScoreTier.RED -> ScoreBadge(Orange, OnOrange)
}

@Composable
private fun SeriesReviewRow(review: SeriesReview) {
    Column(Modifier.fillMaxWidth().testTag("review_${review.id}")) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The App comment endpoint anonymizes every avatar to the same placeholder, so the
            // score is a more useful and distinctive identity in the list.
            val score = SeriesReviewFormat.score(review.score)
            val badge = scoreBadge(review.score)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(badge.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    score.ifEmpty { "—" },
                    color = badge.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Column(
                Modifier.weight(1f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SeriesReviewFormat.displayName(review.author),
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val meta = listOfNotNull(
                        SeriesReviewFormat.time(review.createdAt).takeIf { it.isNotBlank() },
                        if (review.likes > 0) "${review.likes} 赞" else null,
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, color = Muted, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    DanmakuEmoji.display(review.text),
                    color = Ink.copy(alpha = .9f),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .6f)))
    }
}
