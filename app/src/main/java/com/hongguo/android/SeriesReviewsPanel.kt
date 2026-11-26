// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.android.comments.ReviewScoreTier
import com.hongguo.android.comments.SeriesReview
import com.hongguo.android.comments.SeriesReviewApi
import com.hongguo.android.comments.SeriesReviewFormat
import com.hongguo.android.comments.SeriesReviewPage
import com.hongguo.android.danmaku.DanmakuEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Detail-page stats: one centered line in portrait, two left-aligned lines in landscape. */
@Composable
internal fun SeriesReviewStats(
    detail: DramaDetail?,
    reviewCount: Int?,
    onReviews: () -> Unit,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val heat = detail?.hotScore ?: 0L
    val heatLabel = when {
        heat > 0L -> "${SeriesReviewFormat.compactWan(heat)}热度"
        !detail?.heatText.isNullOrBlank() -> detail.heatText.removePrefix("红果热度值").trim()
            .let { if (it.contains("热度")) it else "${it}热度" }
        else -> ""
    }
    val favorites = detail?.favorites ?: 0L
    val favoriteLabel = if (favorites > 0L) "${SeriesReviewFormat.compactWan(favorites)}收藏" else ""
    val metrics = listOf(heatLabel, favoriteLabel).filter { it.isNotBlank() }.joinToString(" · ")
    val reviewLabel = reviewCount?.takeIf { it > 0 }?.let { "$it 条剧评" } ?: "剧评"

    if (landscape) {
        Column(modifier.fillMaxWidth().testTag("detail_reviews_stats"),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (metrics.isNotBlank()) {
                Text(metrics, color = Muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            ReviewCountLink(reviewLabel, onReviews)
        }
    } else {
        Row(
            modifier.fillMaxWidth().testTag("detail_reviews_stats"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (metrics.isNotBlank()) {
                Text(metrics, color = Muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                Text(" · ", color = Muted, fontSize = 12.sp,
                    maxLines = 1, softWrap = false)
            }
            ReviewCountLink(reviewLabel, onReviews)
        }
    }
}

@Composable
private fun ReviewCountLink(label: String, onReviews: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onReviews)
            .padding(vertical = 3.dp)
            .testTag("detail_reviews"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AccentInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AccentInk,
            modifier = Modifier.size(14.dp))
    }
}

/**
 * 剧评面板, matching the player's 选集 panel: a bottom sheet in portrait and a right-side
 * panel in landscape, both sharing [SeriesReviewsContent]. Colors follow the app theme, not the
 * player's dark palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesReviewsOverlay(
    seriesId: String,
    seriesTitle: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    initialPage: SeriesReviewPage? = null,
    pageLoader: (suspend (Int) -> SeriesReviewPage)? = null,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val repository = SeriesReviewApi.repository
    val defaultLoader: suspend (Int) -> SeriesReviewPage = remember(repository, seriesId) {
        { offset -> repository.page(seriesId, offset) }
    }
    val loadPage = pageLoader ?: defaultLoader
    if (landscape) {
        PlayerSidePanel(
            visible = visible,
            onDismiss = onDismiss,
            tag = "detail_reviews_panel",
            containerColor = Canvas,
            contentColor = Ink,
        ) {
            key(seriesId) {
                SeriesReviewsContent(
                    seriesTitle = seriesTitle,
                    loadPage = loadPage,
                    modifier = Modifier.weight(1f),
                    maxListHeight = null,
                    initialPage = initialPage,
                )
            }
        }
    } else if (visible) {
        val screenHeight = LocalConfiguration.current.screenHeightDp
        // Leave the back button visible above the sheet: start just below it instead of at half screen.
        val sheetHeight = (screenHeight * .86f).dp
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = Canvas,
            contentColor = Ink,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().height(sheetHeight).padding(horizontal = 20.dp)
                    .navigationBarsPadding().testTag("detail_reviews_sheet"),
            ) {
                key(seriesId) {
                    SeriesReviewsContent(
                        seriesTitle = seriesTitle,
                        loadPage = loadPage,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        maxListHeight = null,
                        initialPage = initialPage,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
internal fun SeriesReviewsContent(
    seriesTitle: String,
    loadPage: suspend (Int) -> SeriesReviewPage,
    modifier: Modifier = Modifier,
    maxListHeight: Dp? = null,
    initialPage: SeriesReviewPage? = null,
) {
    var items by remember { mutableStateOf(initialPage?.items ?: emptyList()) }
    var total by remember { mutableIntStateOf(initialPage?.total ?: 0) }
    var averageScore by remember { mutableDoubleStateOf(initialPage?.averageScore ?: 0.0) }
    var bookName by remember { mutableStateOf(initialPage?.bookName.orEmpty()) }
    var nextOffset by remember { mutableIntStateOf(initialPage?.nextOffset ?: 0) }
    var hasMore by remember { mutableStateOf(initialPage?.hasMore ?: false) }
    var loading by remember { mutableStateOf(initialPage == null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun load(offset: Int, first: Boolean) {
        if (inFlight) return
        inFlight = true
        if (first) loading = true else loadingMore = true
        error = null
        scope.launch {
            try {
                val page = loadPage(offset)
                items = if (first) page.items else (items + page.items).distinctBy { it.id }
                total = page.total
                averageScore = page.averageScore
                bookName = page.bookName
                nextOffset = page.nextOffset
                hasMore = page.hasMore
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "剧评加载失败，请稍后重试"
            } finally {
                inFlight = false
                loading = false
                loadingMore = false
            }
        }
    }

    LaunchedEffect(Unit) { if (initialPage == null) load(0, true) }

    // The footer is the last item of the list; it becomes visible only at the bottom.
    LaunchedEffect(listState, items.size, hasMore, loading, loadingMore, error) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (items.isNotEmpty() && last >= items.size && hasMore &&
                    !loading && !loadingMore && error == null) {
                    load(nextOffset, false)
                }
            }
    }

    val name = bookName.ifBlank { seriesTitle }
    val scoreLabel = SeriesReviewFormat.score(averageScore).takeIf { it.isNotBlank() }?.let { "$it 分" }
    val countLabel = total.takeIf { it > 0 }?.let { "$it 条" }
    val heading = listOfNotNull("剧评", scoreLabel, countLabel).joinToString(" · ")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false, modifier = Modifier.testTag("reviews_title"))
            Text(name, color = Muted, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("reviews_subtitle"))
        }

        when {
            loading && items.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(180.dp).testTag("reviews_loading"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AccentInk)
                    Text("正在读取剧评…", color = Muted, fontSize = 13.sp)
                }
            }

            items.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(180.dp).testTag("reviews_empty"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (error != null) "剧评加载失败，请稍后重试" else "这部剧还没有剧评",
                        color = Muted, fontSize = 13.sp,
                    )
                    if (error != null) {
                        Text(
                            "点击重试",
                            color = AccentInk,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { load(0, true) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("reviews_retry"),
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = (if (maxListHeight != null) Modifier.heightIn(max = maxListHeight)
                    else Modifier.weight(1f)).fillMaxWidth().testTag("reviews_list"),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(items, key = { it.id }) { review -> SeriesReviewRow(review) }
                    item(key = "reviews_footer") {
                        ReviewsFooter(
                            loading = loadingMore,
                            error = error,
                            hasMore = hasMore,
                            onLoad = { load(nextOffset, false) },
                        )
                    }
                }
            }
        }
    }
}

/** 列表最后一项：加载更多/重试/已到末尾，随列表一起滚动，不冻结在视窗底部。 */
@Composable
private fun ReviewsFooter(loading: Boolean, error: String?, hasMore: Boolean, onLoad: () -> Unit) {
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
            error != null -> Text(
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
