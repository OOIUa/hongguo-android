package com.hongguo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel
import com.hongguo.account.protocol.RelatedWork

internal fun RelatedWork.toCard() = DramaCard(series.id, series.title, series.cover, episodeCount, series.description)

/** Other seasons and same-IP works. Tapping an on-line work opens it; an upcoming one can be reserved. */
@Composable
internal fun RelatedWorksSection(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel,
                                 horizontalPadding: Int = 0) {
    if (state.related.isEmpty()) return
    val account by accountModel.state.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<RelatedWork?>(null) }
    val signedIn = account.sessionStatus == AccountSessionStatus.LOGGED_IN
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("相关作品", Modifier.padding(horizontal = horizontalPadding.dp), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(Modifier.fillMaxWidth().testTag("related_works"), contentPadding = PaddingValues(horizontal = horizontalPadding.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.related, key = { it.series.id }) { work ->
                val reserved = account.reservations.any { it.card.id.removePrefix("reservation:") == work.series.id }
                val busy = work.series.id in account.reservationBusyIds
                Column(Modifier.width(96.dp).clickable(enabled = !work.upcoming) { model.openDrama(work.toCard()) }
                    .testTag("related_${work.series.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cover(work.series.cover, work.series.title, Modifier.fillMaxWidth().height(132.dp))
                    Text(work.season.ifBlank { "相关" }, color = Orange, fontSize = 12.sp, maxLines = 1)
                    Text(work.series.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (work.upcoming) {
                        Text(work.releaseText.ifEmpty { "待上线" }, color = Muted, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("related_release_${work.series.id}"))
                        TextButton(onClick = { if (signedIn) confirmation = work else model.selectTab(AppTab.ACCOUNT) },
                            enabled = !busy && !reserved, modifier = Modifier.heightIn(min = 40.dp).testTag("related_reserve_${work.series.id}"),
                            contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(when { reserved -> "已预约"; busy -> "预约中…"; signedIn -> "预约"; else -> "登录后预约" }, fontSize = 13.sp)
                        }
                    } else Text(work.series.badge, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
    confirmation?.let { work ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text("预约这部作品？") },
            text = { Text("将在红果账号中预约《${work.series.title}》" + work.releaseText.takeIf { it.isNotEmpty() }?.let { "（$it）" }.orEmpty() +
                "，上线后可在账号的预约列表查看。") },
            confirmButton = { TextButton(onClick = { confirmation = null; accountModel.reserve(work) },
                modifier = Modifier.testTag("related_reserve_confirm")) { Text("预约") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}
