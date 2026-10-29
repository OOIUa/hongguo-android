package com.hongguo.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountState
import com.hongguo.account.AccountViewModel
import com.hongguo.account.PhoneStep
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AccountScreen(
    accountModel: AccountViewModel,
    onBack: () -> Unit,
    onOpenLibrary: (LibraryEntry) -> Unit,
) {
    val state by accountModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ownerKey = accountModel.ownerKey
    val sessionKey = accountModel.sessionKey
    val sessionStatus = state.sessionStatus
    var logoutRequested by remember(sessionKey, ownerKey, state.profile?.id, sessionStatus) { mutableStateOf(false) }
    var removeTarget by remember(ownerKey, state.profile?.id, sessionStatus) { mutableStateOf<LibraryEntry?>(null) }
    var diagnosticCopied by remember(state.loginDiagnostic) { mutableStateOf(false) }
    var localResetRequired by rememberSaveable { mutableStateOf(false) }
    var webOpen by rememberSaveable { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    val webLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        webOpen = false
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = OfficialLoginActivity.consumeCookie(result.data).orEmpty()
            if (cookie.isNotBlank()) accountModel.completeWebLogin(cookie)
            else webError = "登录确认已过期，请重新打开官网登录"
        }
    }
    BackHandler(enabled = LocalPageActive.current) { accountModel.cancelLogin(); onBack() }
    DisposableEffect(accountModel) { onDispose { accountModel.cancelLogin() } }
    LaunchedEffect(state.message, state.sessionStatus, state.localClearFailed) {
        if (state.localClearFailed) {
            localResetRequired = true
        } else if (state.sessionStatus == AccountSessionStatus.LOGGED_IN ||
            state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY || state.message?.startsWith("已退出账号") == true) {
            localResetRequired = false
        } else if (state.sessionStatus == AccountSessionStatus.SIGNED_OUT &&
            state.message?.let { "本机账号资料无法读取" in it || "本机清除保存失败" in it || "账号资料保存失败" in it } == true) {
            localResetRequired = true
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("account_screen"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { accountModel.cancelLogin(); onBack() }, modifier = Modifier.testTag("account_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回我的", tint = Ink)
                }
                Text("红果账号", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (state.message != null || state.loginDiagnostic != null) {
            item {
                Surface(color = Cream, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        state.message?.let { message ->
                            Text(message, color = Ink, fontSize = 14.sp, lineHeight = 22.sp,
                                modifier = Modifier.testTag("account_message"))
                        }
                        state.loginDiagnostic?.let { diagnostic ->
                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("红果登录诊断", diagnostic))
                                    diagnosticCopied = true
                                }
                            }, modifier = Modifier.testTag("account_copy_diagnostic")) { Text("复制诊断信息") }
                            if (diagnosticCopied) Text("诊断信息已复制", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.testTag("account_diagnostic_copied"))
                        }
                        if (state.message != null) TextButton(onClick = accountModel::consumeMessage) { Text("知道了") }
                    }
                }
            }
        }
        webError?.let { error ->
            item { AccountNotice(error, "关闭", { webError = null }, tag = "account_web_error") }
        }
        if (state.sessionStatus == AccountSessionStatus.LOGGED_IN && state.profile != null) {
            item { AccountProfileCard(state, onLogout = { logoutRequested = true }) }
            item { AccountProgressSync(state, accountModel::setSyncEnabled) }
            cloudLibraryItems(state, accountModel, onOpenLibrary, { removeTarget = it })
        } else if (state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY) {
            item {
                Surface(color = Cream, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("account_website_only"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.websiteProfile?.takeIf { it.nickname != null || it.avatarUrl != null }?.let { website ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccountAvatar(website.avatarUrl, 40.dp)
                                Text(website.nickname ?: "红果用户", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("account_website_name"))
                            }
                        }
                        Text("官网已登录，云片单待连接", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("官网登录已加密保存在本机。可直接重试，无需重新获取验证码。",
                            color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                        Text("连接账号后，才能查看云片单和开启进度同步。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                        Button(onClick = accountModel::retryWebsiteAccount, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().testTag("account_retry_website")) {
                            Text(if (state.busy) "正在连接账号…" else "重试连接账号")
                        }
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        TextButton(onClick = { logoutRequested = true }, enabled = !state.busy,
                            modifier = Modifier.testTag("account_website_logout")) { Text("退出已保存的官网登录") }
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountAdvancedImport(state, accountModel)
                    }
                }
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (state.sessionStatus) {
                        AccountSessionStatus.NEEDS_VERIFICATION -> "账号需要重新验证"
                        AccountSessionStatus.RESTORING -> "正在确认已保存账号"
                        else -> "接着手机上的故事看"
                    }, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("登录本人的红果账号，查看账号收藏、历史与预约。本机记录单独保留。",
                        color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
            if (state.sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION || localResetRequired) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (localResetRequired) "恢复本机账号登录" else "已保存的账号等待验证",
                                color = Ink, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            state.profile?.let { profile ->
                                Text("${profile.nickname.ifBlank { "红果用户" }} · 账号 ${profile.id}", color = Muted, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (state.sessionStatus == AccountSessionStatus.NEEDS_VERIFICATION) {
                                Text("恢复网络后可重新验证；也可以退出已保存的账号后重新登录。", color = Muted,
                                    fontSize = 12.sp, lineHeight = 20.sp)
                                OutlinedButton(onClick = accountModel::refresh, enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("account_verify_saved")) { Text("重新验证已保存账号") }
                            } else {
                                Text("本机登录资料未能读取。清除已保存的登录态后可重新登录，本机收藏和观看记录继续保留。",
                                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                            }
                            TextButton(onClick = { logoutRequested = true }, enabled = !state.busy,
                                modifier = Modifier.testTag("account_clear_saved")) {
                                Text(if (localResetRequired) "清除本机保存的登录态" else "退出已保存账号")
                            }
                        }
                    }
                }
            }
            if (state.sessionStatus == AccountSessionStatus.RESTORING) {
                item {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("正在确认账号…", color = Muted)
                    }
                }
            } else {
                item {
                    Button(onClick = {
                        accountModel.cancelLogin()
                        webError = null
                        webOpen = true
                        try {
                            webLogin.launch(Intent(context, OfficialLoginActivity::class.java))
                        } catch (_: RuntimeException) {
                            webOpen = false
                            webError = "无法打开官网登录页面，请重试或使用下方登录方式"
                        }
                    }, enabled = !webOpen, modifier = Modifier.fillMaxWidth().testTag("account_web_login")) {
                        Text(if (webOpen) "正在打开登录页面…" else "在本机打开官网登录")
                    }
                    Text("只有一部手机也可以使用。请在官网完成登录，再点“完成登录”返回确认。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp))
                }
                item { AccountLoginMethods(state, accountModel) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    AccountConfirmationDialogs(
        logoutRequested, { logoutRequested = false },
        {
            logoutRequested = false
            if (accountModel.sessionKey == sessionKey && accountModel.state.value.sessionStatus == sessionStatus) accountModel.logout()
        },
        removeTarget, { removeTarget = null },
        { entry -> removeTarget = null; accountModel.setFavorite(entry.card, false, ownerKey) },
        accountLabel = if (state.sessionStatus == AccountSessionStatus.WEBSITE_ONLY)
                state.websiteProfile?.nickname?.let { "官网账号「$it」的本机登录会话" } ?: "本机保存的官网登录会话"
            else state.profile?.let { "${it.nickname.ifBlank { "红果用户" }}（账号 ${it.id}）" },
        localReset = localResetRequired,
    )
}

@Composable
private fun AccountAvatar(url: String?, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Cream), Alignment.Center) {
        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(size * 0.6f), tint = Muted)
        AccountAvatarImage(url)
    }
}

@Composable
private fun AccountProfileCard(state: AccountState, onLogout: () -> Unit) {
    val profile = state.profile ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountAvatar(profile.avatarUrl, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.nickname.ifBlank { "红果用户" }, color = Ink, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("account_profile"))
                    Text("账号已连接", color = AccentInk, fontSize = 12.sp)
                }
            }
            Text("账号 ${profile.id}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.lastSyncAt > 0) "上次读取 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastSyncAt))}"
                    else "已确认账号，正在准备片单", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onLogout, modifier = Modifier.testTag("account_logout")) { Text("退出账号") }
            }
        }
    }
}

@Composable
private fun AccountProgressSync(state: AccountState, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("同步此后的观看进度", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("将新的观看进度保存到当前红果账号，不上传本机旧记录。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                }
                Switch(checked = state.syncEnabled, onCheckedChange = onChange,
                    modifier = Modifier.testTag("account_sync_switch"))
            }
            if (state.pendingProgressCount > 0) Text("还有 ${state.pendingProgressCount} 条观看进度待确认",
                color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("account_pending_progress"))
        }
    }
}

@Composable
private fun AccountLoginMethods(state: AccountState, model: AccountViewModel) {
    var smsSelected by rememberSaveable { mutableStateOf(false) }
    // Phone number and one-time code deliberately stay in memory, outside saved instance state.
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !smsSelected, onClick = {
                    if (smsSelected) model.cancelLogin()
                    smsSelected = false; code = ""
                }, label = { Text("扫码登录") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentInk),
                    modifier = Modifier.testTag("account_method_qr"))
                FilterChip(selected = smsSelected, onClick = {
                    if (!smsSelected) model.cancelLogin()
                    smsSelected = true
                }, label = { Text("手机号登录") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentInk),
                    modifier = Modifier.testTag("account_method_sms"))
            }
            if (!smsSelected) {
                val bitmap = remember(state.qrBytes) { decodeAccountQr(state.qrBytes) }
                if (bitmap != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(bitmap, "红果账号登录二维码", modifier = Modifier.size(220.dp)
                            .alpha(if (state.qrRemainingSeconds > 0) 1f else .3f).testTag("account_qr_image"))
                    }
                    Text(if (state.qrRemainingSeconds > 0) "请用另一台设备上的红果 App 扫码，并确认登录 · ${state.qrRemainingSeconds} 秒"
                        else "二维码已过期，请刷新", color = Muted, fontSize = 12.sp, lineHeight = 20.sp,
                        modifier = Modifier.testTag("account_qr_expiry"))
                } else {
                    Text("使用另一台设备上的红果 App 扫码确认。当前手机也可使用上方官网登录。",
                        color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = model::startQrLogin,
                        enabled = !state.busy || state.qrBytes != null,
                        modifier = Modifier.testTag("account_qr_refresh")) { Text(if (bitmap == null) "获取二维码" else "刷新二维码") }
                    if (state.qrBytes != null || state.busy) TextButton(onClick = model::cancelLogin,
                        modifier = Modifier.testTag("account_login_cancel")) { Text("取消登录") }
                    if (state.busy && bitmap == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                OutlinedTextField(value = phone, onValueChange = { value ->
                    phone = value.filter(Char::isDigit).take(11)
                }, label = { Text("中国大陆手机号") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !state.busy && state.phoneStep == PhoneStep.IDLE,
                    modifier = Modifier.fillMaxWidth().testTag("account_phone"))
                if (state.phoneStep == PhoneStep.CODE_SENT) {
                    Text("验证码已发送至 ${state.phoneMasked}，验证账号资料后才会显示为已登录。",
                        color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                    OutlinedTextField(value = code, onValueChange = { value -> code = value.filter(Char::isDigit).take(8) },
                        label = { Text("短信验证码") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("account_sms_code"))
                    Button(onClick = { model.verifySms(code); code = "" }, enabled = !state.busy && code.length >= 4,
                        modifier = Modifier.fillMaxWidth().testTag("account_sms_verify")) { Text("验证并登录") }
                }
                OutlinedButton(onClick = { model.sendSms(phone) }, enabled = !state.busy && phone.length == 11 && state.phoneRemainingSeconds <= 0,
                    modifier = Modifier.fillMaxWidth().testTag("account_sms_send")) {
                    Text(if (state.phoneRemainingSeconds > 0) "${state.phoneRemainingSeconds} 秒后重新发送"
                        else if (state.phoneStep == PhoneStep.CODE_SENT) "重新发送验证码" else "获取验证码")
                }
                if (state.phoneStep == PhoneStep.CODE_SENT || state.busy) TextButton(onClick = { model.cancelLogin(); code = "" },
                    modifier = Modifier.testTag("account_sms_cancel")) { Text("取消或更换手机号") }
                if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            HorizontalDivider(color = Canvas)
            AccountAdvancedImport(state, model)
        }
    }
}

@Composable
private fun AccountAdvancedImport(state: AccountState, model: AccountViewModel) {
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var appCookieSelected by rememberSaveable { mutableStateOf(false) }
    // Login credentials stay only in memory and are erased before any validation request.
    var importedCookie by remember { mutableStateOf("") }
    TextButton(onClick = {
        advancedOpen = !advancedOpen
        importedCookie = ""
    }, modifier = Modifier.testTag("account_cookie_expand")) {
        Text(if (advancedOpen) "收起高级登录" else "高级：导入已有登录态", color = Muted, fontSize = 12.sp)
    }
    if (advancedOpen) {
        Text("仅在本机校验你本人的账号。", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !appCookieSelected, onClick = {
                appCookieSelected = false
                importedCookie = ""
            }, enabled = !state.busy, label = { Text("官网登录态") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentInk),
                modifier = Modifier.testTag("account_cookie_source_web"))
            FilterChip(selected = appCookieSelected, onClick = {
                appCookieSelected = true
                importedCookie = ""
            }, enabled = !state.busy, label = { Text("红果 App 登录态") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentInk),
                modifier = Modifier.testTag("account_cookie_source_app"))
        }
        if (appCookieSelected) Text("请确认输入来自你本人的红果 App 账号。通过手机云账号验证后才能使用云片单。",
            color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        OutlinedTextField(value = importedCookie, onValueChange = { value ->
            if (value.length <= 65536) importedCookie = value
        }, label = { Text(if (appCookieSelected) "红果 App Cookie" else "官网 Cookie") }, singleLine = true,
            shape = RoundedCornerShape(16.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("account_cookie_input"))
        OutlinedButton(onClick = {
            val candidate = importedCookie
            importedCookie = ""
            model.cancelLogin()
            if (appCookieSelected) model.importAppCookie(candidate) else model.importCookie(candidate)
        }, enabled = !state.busy && importedCookie.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("account_cookie_submit")) {
            Text("校验并登录")
        }
    }
}

private fun decodeAccountQr(bytes: ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
    if (bytes == null || bytes.size > 1024 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
}
