package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("下一部好剧，从这里开始", Modifier.padding(top = 8.dp, bottom = 15.dp),
            fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(value = state.query, onValueChange = model::updateQuery,
                modifier = Modifier.weight(1f).testTag("search_input"),
                placeholder = { Text("输入剧名或关键词", fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }))
            Button(onClick = submit, enabled = state.query.isNotBlank(), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(54.dp).testTag("search_button")) { Text("搜索") }
        }
        if (state.searching) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                Text("正在寻找相关短剧…", Modifier.padding(start = 11.dp).weight(1f), color = Muted, fontSize = 13.sp)
                TextButton(onClick = model::cancelSearch) { Text("取消") }
            }
        } else if (state.searchedQuery.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("搜索结果", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text("${state.results.size} 部", color = Muted, fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("search_results"), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (state.results.isEmpty() && !state.searching && state.searchError == null) {
                item {
                    EmptyPrompt(if (state.searchedQuery.isBlank()) "发现属于你的故事" else "没有找到相关剧集",
                        if (state.searchedQuery.isBlank()) "搜索剧名，查看完整选集并在本机播放。" else "试试缩短关键词，或换个剧名再搜索。")
                }
            }
            itemsIndexed(state.results, key = { _, item -> item.id }) { index, card ->
                DramaResult(card, index) { keyboard?.hide(); focus.clearFocus(); model.openDrama(card) }
            }
            if (state.searchError != null) {
                item { FailurePanel(state.searchError, onRetry = if (state.results.isEmpty()) model::search else model::loadMore) }
            }
            if (state.hasMore) {
                item {
                    OutlinedButton(onClick = model::loadMore, enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(14.dp)) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Text("加载更多")
                    }
                }
            }
            if (state.results.isNotEmpty() && !state.hasMore && !state.loadingMore) {
                item { Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 16.dp), color = Muted, fontSize = 12.sp) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
internal fun DramaResult(card: DramaCard, index: Int, tag: String = "result_$index", onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Cover(card.cover, card.title, Modifier.width(83.dp).height(112.dp))
            Column(Modifier.weight(1f).heightIn(min = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Surface(color = Cream, shape = RoundedCornerShape(6.dp)) {
                    Text(if (card.episodeCount > 0) "${card.episodeCount} 集" else "查看剧集",
                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 11.sp, color = Muted)
                }
                if (card.summary.isNotBlank()) Text(card.summary, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("查看选集  →", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun Cover(url: String, title: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(Cream), Alignment.Center) {
        Text("红果\n剧场", color = Color(0xFFC5AB96), fontSize = 14.sp, lineHeight = 22.sp)
        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = "$title 封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun FailurePanel(message: String, onRetry: () -> Unit, tag: String = "retry_request") {
    Surface(color = Color(0xFFFFEFE7), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = Color(0xFF96492F), fontSize = 13.sp, lineHeight = 20.sp)
            TextButton(onClick = onRetry, modifier = Modifier.testTag(tag)) { Text("重试") }
        }
    }
}

@Composable
internal fun EmptyPrompt(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(Cream), Alignment.Center) {
            Text("剧", fontSize = 31.sp, color = Orange, fontWeight = FontWeight.Light)
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, fontSize = 12.sp, lineHeight = 20.sp, color = Muted)
    }
}
