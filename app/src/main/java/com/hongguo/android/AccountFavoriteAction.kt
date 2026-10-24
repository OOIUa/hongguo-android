package com.hongguo.android

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel

/** A deliberate cloud action alongside the existing device-only favorite. */
@Composable
internal fun AccountFavoriteAction(card: DramaCard, model: AccountViewModel, dark: Boolean = false,
                                   compact: Boolean = false,
                                   onSignIn: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = model.ownerKey
    var confirmation by remember(card.id, owner) { mutableStateOf<Boolean?>(null) }
    val signedIn = state.sessionStatus == AccountSessionStatus.LOGGED_IN
    val present = state.favorites.any { it.card.id == card.id }
    val uncertain = card.id in state.favoriteUncertainIds
    val busy = card.id in state.favoriteBusyIds
    TextButton(onClick = {
        if (!signedIn) onSignIn()
        else if (uncertain) model.resolveFavorite(card.id)
        else confirmation = !present
    }, enabled = !busy && !state.busy,
        modifier = Modifier.testTag("account_favorite_toggle").then(if (dark && !compact) Modifier.widthIn(max = 104.dp) else Modifier)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dark && !compact) Icon(if (present) Icons.Outlined.CloudDone else Icons.Outlined.CloudQueue, null,
                Modifier.size(26.dp), tint = if (present) Orange else Color.White)
            Text(when {
                busy -> "正在核对"
                uncertain -> "核对收藏"
                !signedIn -> "账号收藏"
                present -> "账号已收藏"
                else -> "账号收藏"
            }, color = if (dark) Color.White else Orange, fontSize = if (dark) 12.sp else 14.sp)
        }
    }
    confirmation?.let { add ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (add) "加入账号收藏" else "取消账号收藏") },
            icon = { Icon(if (add) Icons.Outlined.CloudQueue else Icons.Outlined.CloudDone, null, tint = Orange) },
            text = { Text("${if (add) "将《${card.title}》加入" else "将《${card.title}》移出"}红果账号「${state.profile?.nickname.orEmpty()}」的收藏。本机收藏单独保留。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    if (owner != null && owner == model.ownerKey) model.setFavorite(card, add, expectedOwner = owner)
                }, modifier = Modifier.testTag("confirm_account_favorite")) { Text("确认") }
            }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}
