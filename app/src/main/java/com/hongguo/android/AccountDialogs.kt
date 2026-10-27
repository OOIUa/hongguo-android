package com.hongguo.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

/** Explicit confirmations are tied to a specific account operation, never a whole list. */
@Composable
internal fun AccountConfirmationDialogs(
    logoutRequested: Boolean,
    onDismissLogout: () -> Unit,
    onLogout: () -> Unit,
    removeTarget: LibraryEntry?,
    onDismissRemove: () -> Unit,
    onRemove: (LibraryEntry) -> Unit,
    accountLabel: String? = null,
    localReset: Boolean = false,
) {
    if (logoutRequested) {
        AlertDialog(
            onDismissRequest = onDismissLogout,
            icon = { Icon(if (localReset) Icons.Outlined.DeleteOutline else Icons.Outlined.PersonOutline, null, tint = AccentInk) },
            title = { Text(if (localReset) "清除本机保存的登录态？" else "退出红果账号？") },
            text = {
                Text(if (localReset) "将清除这台设备保存的红果登录信息，之后需要重新登录。本机收藏和观看记录保留。"
                    else "将退出${accountLabel ?: "本机保存的红果账号"}并停止账号同步。本机收藏和观看记录保留。", fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(onClick = onLogout, modifier = Modifier.testTag("account_logout_confirm")) {
                    Text(if (localReset) "清除登录态" else "退出账号")
                }
            },
            dismissButton = { TextButton(onClick = onDismissLogout) { Text("取消") } },
        )
    }
    removeTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = onDismissRemove,
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = AccentInk) },
            title = { Text("取消账号收藏？") },
            text = { Text("将从当前红果账号移除《${entry.card.title}》。本机收藏保留。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { onRemove(entry) }, modifier = Modifier.testTag("account_remove_favorite_confirm")) {
                    Text("取消收藏")
                }
            },
            dismissButton = { TextButton(onClick = onDismissRemove) { Text("保留") } },
        )
    }
}
