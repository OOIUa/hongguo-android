package com.hongguo.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.AccountState
import com.hongguo.account.AccountViewModel
import com.hongguo.account.PhoneStep

private enum class LoginMethod(val label: String, val tag: String) {
    PHONE("手机验证码", "account_method_sms"), WEBSITE("官网登录", "account_method_web"), QR("扫码登录", "account_method_qr"),
}

private val MainlandMobile = Regex("1[3-9][0-9]{9}")

/** Digits of a typed or autofilled number; a pasted +86 prefix is dropped rather than truncating the number. */
internal fun mainlandPhoneDigits(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return (if (digits.length > 11 && digits.startsWith("86")) digits.drop(2) else digits).take(11)
}

/** Shows `13800138000` as `138 0013 8000`; the field's value stays digits only. */
internal object MainlandPhoneTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown = buildString {
            digits.forEachIndexed { index, digit ->
                if (index == 3 || index == 7) append(' ')
                append(digit)
            }
        }
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + (if (offset > 3) 1 else 0) + (if (offset > 7) 1 else 0)
            override fun transformedToOriginal(offset: Int) = offset - (if (offset > 3) 1 else 0) - (if (offset > 8) 1 else 0)
        })
    }
}

@Composable
internal fun AccountLoginMethods(state: AccountState, model: AccountViewModel, webOpening: Boolean, openWebsite: () -> Unit) {
    var method by rememberSaveable { mutableStateOf(LoginMethod.PHONE) }
    Surface(shape = RoundedCornerShape(20.dp), color = CardSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Canvas).padding(3.dp)) {
                LoginMethod.entries.forEach { entry ->
                    val selected = method == entry
                    val fill by animateColorAsState(if (selected) CardSurface else Canvas, label = "login method")
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                        .quietSelectable(selected) {
                            if (!selected) { model.cancelLogin(); method = entry }
                        }.testTag(entry.tag), Alignment.Center) {
                        Text(entry.label, color = if (selected) Ink else Muted, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            when (method) {
                LoginMethod.PHONE -> PhoneLoginForm(state, model) { method = LoginMethod.WEBSITE; openWebsite() }
                LoginMethod.WEBSITE -> {
                    Text("只有一部手机也可以使用。在官网页面完成登录和平台验证后，点“完成登录”返回确认。",
                        color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    Button(onClick = openWebsite, enabled = !webOpening, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_web_login")) {
                        Text(if (webOpening) "正在打开登录页面…" else "打开官网登录")
                    }
                }
                LoginMethod.QR -> QrLoginForm(state, model)
            }
            HorizontalDivider(color = Canvas)
            AccountAdvancedImport(state, model)
        }
    }
}

@Composable
private fun ColumnScope.PhoneLoginForm(state: AccountState, model: AccountViewModel, openWebsite: () -> Unit) {
    // Phone number and one-time code deliberately stay in memory, outside saved instance state.
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // Keeps the code step on screen while a resend or verification is in flight.
    var resending by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) { if (!state.busy) { resending = false; submitted = false } }
    val valid = MainlandMobile.matches(phone)
    val codeStep = state.phoneStep == PhoneStep.CODE_SENT || (state.busy && (resending || submitted))
    val canSend = !state.busy && valid && state.phoneRemainingSeconds <= 0
    val verify = {
        if (!state.busy && code.length >= 4) { submitted = true; model.verifySms(code); code = "" }
    }
    // The platform asked for a slider: the user passes it personally on the official login page.
    var verificationDismissed by remember(state.suggestWebsiteLogin) { mutableStateOf(false) }
    if (state.suggestWebsiteLogin && !verificationDismissed) {
        AlertDialog(
            onDismissRequest = { verificationDismissed = true },
            title = { Text("需要人工验证") },
            text = { Text("红果要求完成滑块验证。请在官方登录页面重新输入手机号，手动完成验证并登录。") },
            confirmButton = {
                TextButton(onClick = { verificationDismissed = true; openWebsite() },
                    modifier = Modifier.testTag("account_verification_open")) { Text("去验证") }
            },
            dismissButton = {
                TextButton(onClick = { verificationDismissed = true },
                    modifier = Modifier.testTag("account_verification_later")) { Text("稍后") }
            },
        )
    }
    if (state.suggestWebsiteLogin) {
        Surface(color = Cream, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("需要平台验证，请到官网页面完成", Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                TextButton(onClick = openWebsite, modifier = Modifier.testTag("account_sms_to_web")) { Text("去官网登录") }
            }
        }
    }
    if (!codeStep) {
        OutlinedTextField(value = phone, onValueChange = { phone = mainlandPhoneDigits(it) },
            label = { Text("手机号") }, prefix = { Text("+86  ", color = Muted) }, singleLine = true,
            shape = RoundedCornerShape(12.dp), visualTransformation = MainlandPhoneTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) model.sendSms(phone) }),
            isError = phone.length == 11 && !valid,
            supportingText = if (phone.length == 11 && !valid) ({ Text("请输入有效的中国大陆手机号") }) else null,
            trailingIcon = if (phone.isEmpty() || state.busy) null else ({
                IconButton(onClick = { phone = "" }, modifier = Modifier.testTag("account_phone_clear")) {
                    Icon(Icons.Outlined.Close, "清空手机号", Modifier.size(18.dp), tint = Muted)
                }
            }),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumberNational }.testTag("account_phone"))
        Button(onClick = { model.sendSms(phone) }, enabled = canSend, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_sms_send")) {
            Text(when {
                state.busy -> "正在发送…"
                state.phoneRemainingSeconds > 0 -> "${state.phoneRemainingSeconds} 秒后可重新获取"
                else -> "获取验证码"
            })
        }
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val masked = state.phoneMasked.ifEmpty { phone.take(3) + "****" + phone.takeLast(4) }
            Text("验证码已发送至 $masked", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            TextButton(onClick = { model.cancelLogin(); code = "" }, modifier = Modifier.testTag("account_sms_cancel")) { Text("更换手机号") }
        }
        OutlinedTextField(value = code, onValueChange = { value -> code = value.filter(Char::isDigit).take(8) },
            label = { Text("短信验证码") }, singleLine = true, shape = RoundedCornerShape(12.dp),
            textStyle = TextStyle(fontSize = 20.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center, color = Ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { verify() }), enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentType = ContentType.SmsOtpCode }
                .testTag("account_sms_code"))
        Button(onClick = { verify() }, enabled = !state.busy && code.length >= 4, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_sms_verify")) {
            Text(if (submitted) "正在验证…" else "验证并登录")
        }
        TextButton(onClick = { resending = true; code = ""; model.sendSms(phone) },
            enabled = !state.busy && state.phoneRemainingSeconds <= 0,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("account_sms_send")) {
            Text(when {
                resending -> "正在重新发送…"
                state.phoneRemainingSeconds > 0 -> "${state.phoneRemainingSeconds} 秒后可重新获取"
                else -> "没收到？重新获取"
            }, fontSize = 13.sp)
        }
    }
}

@Composable
private fun QrLoginForm(state: AccountState, model: AccountViewModel) {
    LaunchedEffect(Unit) { if (state.qrBytes == null && !state.busy) model.startQrLogin() }
    val bitmap = remember(state.qrBytes) { decodeAccountQr(state.qrBytes) }
    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), Alignment.Center) {
        if (bitmap != null) Image(bitmap, "红果账号登录二维码", modifier = Modifier.size(220.dp)
            .alpha(if (state.qrRemainingSeconds > 0) 1f else .3f).testTag("account_qr_image"))
        else if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    Text(when {
        bitmap == null -> "用另一台设备上的红果 App 扫码确认"
        state.qrRemainingSeconds > 0 -> "用另一台设备上的红果 App 扫码并确认 · ${state.qrRemainingSeconds} 秒"
        else -> "二维码已过期，请刷新"
    }, Modifier.fillMaxWidth().testTag("account_qr_expiry"), color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = model::startQrLogin, enabled = !state.busy || state.qrBytes != null,
            modifier = Modifier.testTag("account_qr_refresh")) { Text(if (bitmap == null) "获取二维码" else "刷新二维码") }
        if (state.qrBytes != null || state.busy) TextButton(onClick = model::cancelLogin,
            modifier = Modifier.testTag("account_login_cancel")) { Text("取消登录") }
    }
}
