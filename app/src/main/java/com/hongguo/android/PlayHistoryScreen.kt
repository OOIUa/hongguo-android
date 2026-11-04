package com.hongguo.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel

/** 播放历史: a page of wherever it was opened from. A signed-in account's history comes first unless the entry was about this device. */
@Composable
internal fun PlayHistoryScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val account by accountModel.state.collectAsStateWithLifecycle()
    val signedIn = account.sessionStatus == AccountSessionStatus.LOGGED_IN
    val showsAccount = signedIn && !state.playHistoryLocal
    var managing by rememberSaveable(showsAccount) { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("play_history_screen")) {
        LocalPageHeader("播放历史", "play_history_back", model::closeMyLibrary, !showsAccount, state.history.isNotEmpty(), managing,
            { managing = !managing }, { confirmClear = true }, "history")
        if (signedIn) TabRow(selectedTabIndex = if (showsAccount) 0 else 1, containerColor = Canvas, contentColor = AccentInk) {
            listOf(false to "红果账号", true to "本机").forEach { (local, label) ->
                Tab(selected = state.playHistoryLocal == local, onClick = { model.selectPlayHistorySource(local) },
                    modifier = Modifier.testTag(if (local) "play_history_local" else "play_history_account_tab"),
                    selectedContentColor = AccentInk, unselectedContentColor = Muted, text = { Text(label, fontSize = 15.sp) })
            }
        }
        if (showsAccount) AccountHistoryList(accountModel, model::openLibrary, Modifier.fillMaxWidth().weight(1f))
        else LocalLibraryList(state.history, true, managing, model, Modifier.fillMaxWidth().weight(1f))
    }
    if (confirmClear) LocalClearDialog(true, model) { confirmClear = false }
}

/** 本机收藏 opened from 我的: the device's list whichever source the 追剧 tab is showing. */
@Composable
internal fun LocalFavoritesScreen(state: HongguoUiState, model: HongguoViewModel) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("local_favorites_screen")) {
        LocalPageHeader("本机收藏", "local_favorites_back", model::closeMyLibrary, true, state.favorites.isNotEmpty(), managing,
            { managing = !managing }, { confirmClear = true }, "favorites")
        LocalLibraryList(state.favorites, false, managing, model, Modifier.fillMaxWidth().weight(1f))
    }
    if (confirmClear) LocalClearDialog(false, model) { confirmClear = false }
}

@Composable
private fun LocalPageHeader(title: String, backTag: String, onBack: () -> Unit, manageable: Boolean, hasEntries: Boolean, managing: Boolean,
                            onManage: () -> Unit, onClear: () -> Unit, kind: String) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
        }
        Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (manageable && hasEntries) {
            if (managing) TextButton(onClick = onClear, modifier = Modifier.testTag("clear_$kind")) { Text("清空") }
            TextButton(onClick = onManage, modifier = Modifier.testTag("library_manage")) { Text(if (managing) "完成" else "管理", color = Muted) }
        }
    }
}
