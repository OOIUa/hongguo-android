package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
internal fun SearchScreen(state: HongguoUiState, model: HongguoViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val submit = { keyboard?.hide(); focus.clearFocus(); model.search() }
    val close = { keyboard?.hide(); focus.clearFocus(); model.closeSearch() }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("search_screen")) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = close, modifier = Modifier.testTag("search_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
            }
            OutlinedTextField(value = state.query, onValueChange = model::updateQuery,
                modifier = Modifier.weight(1f).testTag("search_input"),
                placeholder = { Text("搜索剧名或关键词", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = Ink),
                trailingIcon = if (state.query.isNotEmpty()) {
                    { IconButton(onClick = { model.updateQuery("") }, modifier = Modifier.testTag("search_clear")) {
                        Icon(Icons.Outlined.Close, "清空搜索词", tint = Muted)
                    } }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Cream, unfocusedContainerColor = Cream,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Orange),
                singleLine = true, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }))
            Button(onClick = submit, enabled = state.query.isNotBlank(), shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("search_button")) { Text("搜索", fontSize = 14.sp) }
        }
        if (state.searching) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在寻找相关短剧…", Modifier.padding(start = 12.dp).weight(1f), color = Muted, fontSize = 14.sp)
                TextButton(onClick = model::cancelSearch, modifier = Modifier.testTag("search_cancel")) { Text("取消", fontSize = 14.sp) }
            }
        } else if (state.searchedQuery.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“${state.searchedQuery}”的结果", fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = Ink)
                Text("${state.results.size} 部", color = Muted, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("search_results"),
            contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.results.isEmpty() && !state.searching && state.searchError == null) {
                item {
                    EmptyPrompt(if (state.searchedQuery.isBlank()) "搜索你想看的短剧" else "没有找到相关剧集",
                        if (state.searchedQuery.isBlank()) "输入剧名或关键词，查看完整选集。" else "试试缩短关键词，或换个剧名再搜索。")
                }
            }
            itemsIndexed(state.results, key = { _, item -> item.id }) { index, card ->
                DramaResult(card, index) { keyboard?.hide(); focus.clearFocus(); model.openDrama(card) }
            }
            state.searchError?.let { error ->
                item { FailurePanel(error, onRetry = if (state.results.isEmpty()) model::search else model::loadMore) }
            }
            if (state.hasMore) {
                item {
                    OutlinedButton(onClick = model::loadMore, enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search_load_more"), shape = RoundedCornerShape(12.dp)) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("加载更多", fontSize = 14.sp)
                    }
                }
            }
            if (state.results.isNotEmpty() && !state.hasMore && !state.loadingMore) {
                item { Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 16.dp), color = Muted,
                    fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
internal fun DramaResult(card: DramaCard, index: Int, tag: String = "result_$index", onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(card.cover, card.title, Modifier.width(80.dp).height(108.dp))
            Column(Modifier.weight(1f).heightIn(min = 108.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(card.title, color = Ink, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (card.episodeCount > 0) "${card.episodeCount} 集" else "查看剧集", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                if (card.summary.isNotBlank()) Text(card.summary, color = Muted, fontSize = 13.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("查看选集", color = AccentText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Outlined.ChevronRight, null, tint = AccentText, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun Cover(url: String, title: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Cream), Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = Muted.copy(alpha = .45f), modifier = Modifier.size(28.dp))
        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun FailurePanel(message: String, onRetry: () -> Unit, tag: String = "retry_request") {
    Surface(color = NoticeBackground, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, modifier = Modifier.weight(1f), color = NoticeText, fontSize = 14.sp, lineHeight = 22.sp)
            TextButton(onClick = onRetry, modifier = Modifier.testTag(tag)) { Text("重试", fontSize = 14.sp) }
        }
    }
}

@Composable
internal fun EmptyPrompt(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Cream), Alignment.Center) {
            Icon(Icons.Outlined.VideoLibrary, null, modifier = Modifier.size(30.dp), tint = Muted)
        }
        Text(title, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = Ink, textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 14.sp, lineHeight = 22.sp, color = Muted, textAlign = TextAlign.Center)
    }
}
