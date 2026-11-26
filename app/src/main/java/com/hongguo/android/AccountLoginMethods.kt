package com.hongguo.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private enum class LoginMethod(val label: String, val tag: String, val icon: ImageVector) {
    PHONE("手机验证码", "account_method_sms", Icons.Outlined.PhoneAndroid),
    QR("扫码登录", "account_method_qr", Icons.Outlined.QrCode2),
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
internal fun AccountLoginMethods(state: AccountState, model: AccountViewModel) {
    var method by rememberSaveable { mutableStateOf(LoginMethod.PHONE) }
    val cardShape = RoundedCornerShape(24.dp)
    val trackShape = RoundedCornerShape(14.dp)
    val tabs = rememberTabIndicator()

    Surface(
        shape = cardShape,
        color = CardSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Line, cardShape),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(trackShape)
                    .background(Canvas)
                    .border(1.dp, Line, trackShape)
                    .padding(4.dp)
                    .slidingPill(tabs, method, CardSurface, corner = 11.dp),
            ) {
                LoginMethod.entries.forEach { entry ->
                    val selected = method == entry
                    val labelColor by animatedTabColor(selected, Ink, Muted)
                    val iconColor by animatedTabColor(selected, AccentInk, Muted)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .tabAnchor(tabs, entry)
                            .heightIn(min = 42.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .quietSelectable(selected) {
                                if (!selected) { model.cancelLogin(); method = entry }
                            }
                            .testTag(entry.tag),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(entry.icon, null, modifier = Modifier.size(16.dp), tint = iconColor)
                        Text(
                            text = entry.label,
                            color = labelColor,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
            when (method) {
                LoginMethod.PHONE -> PhoneLoginForm(state, model)
                LoginMethod.QR -> QrLoginForm(state, model)
            }
        }
    }
}

@Composable
private fun ColumnScope.PhoneLoginForm(state: AccountState, model: AccountViewModel) {
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
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Canvas,
        unfocusedContainerColor = Canvas,
        disabledContainerColor = Canvas,
        focusedBorderColor = AccentInk,
        unfocusedBorderColor = Line,
    )

    if (!codeStep) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = mainlandPhoneDigits(it) },
            label = { Text("手机号") },
            prefix = { Text("+86  ", color = Muted, fontWeight = FontWeight.SemiBold) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
            visualTransformation = MainlandPhoneTransformation,
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
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.PhoneNumberNational }
                .testTag("account_phone"),
        )
        Button(
            onClick = { model.sendSms(phone) },
            enabled = canSend,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = OnOrange,
                disabledContainerColor = Orange.copy(alpha = 0.36f),
                disabledContentColor = OnOrange.copy(alpha = 0.9f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("account_sms_send"),
        ) {
            Text(
                text = when {
                    state.busy -> "正在发送…"
                    state.phoneRemainingSeconds > 0 -> "${state.phoneRemainingSeconds} 秒后可重新获取"
                    else -> "获取验证码"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val masked = state.phoneMasked.ifEmpty { phone.take(3) + "****" + phone.takeLast(4) }
            Text("验证码已发送至 $masked", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            TextButton(onClick = { model.cancelLogin(); code = "" }, modifier = Modifier.testTag("account_sms_cancel")) {
                Text("更换手机号", color = AccentInk)
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { value -> code = value.filter(Char::isDigit).take(8) },
            label = { Text("短信验证码") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
            textStyle = TextStyle(fontSize = 20.sp, letterSpacing = 4.sp, textAlign = TextAlign.Center, color = Ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { verify() }),
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .semantics { contentType = ContentType.SmsOtpCode }
                .testTag("account_sms_code"),
        )
        Button(
            onClick = { verify() },
            enabled = !state.busy && code.length >= 4,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = OnOrange,
                disabledContainerColor = Orange.copy(alpha = 0.36f),
                disabledContentColor = OnOrange.copy(alpha = 0.9f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("account_sms_verify"),
        ) {
            Text(if (submitted) "正在验证…" else "验证并登录", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = { resending = true; code = ""; model.sendSms(phone) },
            enabled = !state.busy && state.phoneRemainingSeconds <= 0,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("account_sms_send"),
        ) {
            Text(
                text = when {
                    resending -> "正在重新发送…"
                    state.phoneRemainingSeconds > 0 -> "${state.phoneRemainingSeconds} 秒后可重新获取"
                    else -> "没收到？重新获取"
                },
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun QrLoginForm(state: AccountState, model: AccountViewModel) {
    LaunchedEffect(Unit) { if (state.qrBytes == null && !state.busy) model.startQrLogin() }
    val bitmap = remember(state.qrBytes) { decodeAccountQr(state.qrBytes) }
    val qrShape = RoundedCornerShape(18.dp)

    Box(Modifier.fillMaxWidth().padding(top = 2.dp), Alignment.Center) {
        Surface(
            shape = qrShape,
            color = Color.White,
            modifier = Modifier
                .size(224.dp)
                .border(1.dp, Line, qrShape),
        ) {
            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "红果账号登录二维码",
                        modifier = Modifier
                            .size(200.dp)
                            .alpha(if (state.qrRemainingSeconds > 0) 1f else .3f)
                            .testTag("account_qr_image"),
                    )
                } else if (state.busy) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = Orange)
                }
            }
        }
    }
    Text(
        text = when {
            bitmap == null -> "用另一台设备上的红果 App 扫码确认"
            state.qrRemainingSeconds > 0 -> "用另一台设备上的红果 App 扫码并确认 · ${state.qrRemainingSeconds} 秒"
            else -> "二维码已过期，请刷新"
        },
        modifier = Modifier.fillMaxWidth().testTag("account_qr_expiry"),
        color = Muted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Center,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = model::startQrLogin,
            enabled = !state.busy || state.qrBytes != null,
            shape = CircleShape,
            modifier = Modifier.testTag("account_qr_refresh"),
        ) {
            Text(if (bitmap == null) "获取二维码" else "刷新二维码", fontWeight = FontWeight.SemiBold)
        }
        if (state.qrBytes != null || state.busy) {
            TextButton(
                onClick = model::cancelLogin,
                modifier = Modifier.testTag("account_login_cancel"),
            ) { Text("取消登录", color = Muted) }
        }
    }
}
