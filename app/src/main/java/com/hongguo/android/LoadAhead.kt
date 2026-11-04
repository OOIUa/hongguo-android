package com.hongguo.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

/** How many grid items may remain below the viewport before the next page is requested. */
internal const val LoadAheadItems = 12

/** How many covers past the visible position are downloaded ahead of time (about 50 KB each). */
internal const val PreloadCovers = 9

/** A failed page waits for the retry button, so a dead network cannot turn scrolling into a request loop. */
internal fun shouldLoadAhead(feed: FeedState, remaining: Int): Boolean =
    feed.items.isNotEmpty() && feed.hasMore && !feed.loading && !feed.loadingMore && feed.error == null &&
        remaining <= LoadAheadItems

/** The next covers below the visible position. [remaining] may include headers or a footer, which only shifts the window. */
internal fun upcomingCovers(items: List<DramaCard>, remaining: Int): List<String> =
    items.drop((items.size - remaining).coerceAtLeast(0)).take(PreloadCovers).map { it.cover }.filter { it.isNotBlank() }

/**
 * Requests the next page as the visible position nears the end, so it is already in the list when the user gets
 * there. Re-evaluated after each page, which also keeps loading while a short page still leaves the end in reach.
 */
@Composable
internal fun LoadAheadEffect(
    feed: FeedState, lastVisibleIndex: () -> Int, totalItems: () -> Int, enabled: Boolean = true, onLoad: () -> Unit,
) {
    val currentFeed by rememberUpdatedState(feed)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentLoad by rememberUpdatedState(onLoad)
    LaunchedEffect(Unit) {
        snapshotFlow { currentEnabled && shouldLoadAhead(currentFeed, totalItems() - 1 - lastVisibleIndex()) }
            .collect { if (it) currentLoad() }
    }
    CoverPreloadEffect(feed.items, lastVisibleIndex, totalItems)
}

/** Fills Coil's disk cache for the covers about to scroll in, so they appear without waiting for the network. */
@Composable
internal fun CoverPreloadEffect(items: List<DramaCard>, lastVisibleIndex: () -> Int, totalItems: () -> Int) {
    val context = LocalContext.current
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(Unit) {
        val requested = HashSet<String>()
        snapshotFlow { upcomingCovers(currentItems, totalItems() - 1 - lastVisibleIndex()) }.collect { covers ->
            covers.filter(requested::add).forEach { cover ->
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(cover).size(240)
                    .memoryCachePolicy(CachePolicy.DISABLED).build())
            }
        }
    }
}

/** Shown under the list while more pages exist; only visible when scrolling outruns the request. */
@Composable
internal fun LoadAheadFooter(tag: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
