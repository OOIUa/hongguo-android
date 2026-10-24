package com.hongguo.android

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Orange = Color(0xFFC94426)
internal val Ink = Color(0xFF292421)
internal val Muted = Color(0xFF6B625C)
internal val Canvas = Color(0xFFFCF8F4)
internal val Cream = Color(0xFFF5EDE5)
internal val Line = Color(0xFFE5DCD5)
internal val SoftOrange = Color(0xFFFFEADF)

/** Native Android type and shared surfaces across content, forms and system dialogs. */
@Composable
internal fun HongguoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Orange, onPrimary = Color.White,
            primaryContainer = SoftOrange, onPrimaryContainer = Orange,
            secondary = Orange, onSecondary = Color.White,
            secondaryContainer = SoftOrange, onSecondaryContainer = Orange,
            background = Canvas, onBackground = Ink,
            surface = Canvas, onSurface = Ink, onSurfaceVariant = Muted,
            surfaceVariant = Cream, outline = Line, outlineVariant = Line,
            surfaceContainer = Color.White, surfaceContainerLow = Canvas,
            surfaceContainerHigh = Color.White, surfaceContainerHighest = Cream,
            surfaceTint = Color.Transparent,
        ),
        typography = Typography(
            headlineSmall = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            titleMedium = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            bodyLarge = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp),
            labelLarge = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelSmall = nativeStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content,
    )
}

private fun nativeStyle(fontFamily: FontFamily, fontSize: TextUnit, lineHeight: TextUnit,
                        fontWeight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = fontFamily, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)
