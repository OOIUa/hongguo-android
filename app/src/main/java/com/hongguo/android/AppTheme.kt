package com.hongguo.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable

/** User-selectable interface styles. The key is what is persisted, so never rename an existing key. */
enum class AppThemeStyle(val key: String, val label: String, val summary: String) {
    WARM("warm", "暖白精修", "温暖留白的经典布局：推荐位、继续观看横滑和三列封面，适合日常找剧。"),
    IMMERSIVE("immersive", "沉浸暗场", "竖屏沉浸布局：首页上下滑动刷剧，剧场看榜单，追剧页接着看。"),
    CARDS("cards", "内容卡片", "卡片式布局：频道切换、热度榜和双列瀑布流，信息更紧凑。");

    companion object {
        fun fromKey(key: String?): AppThemeStyle = entries.firstOrNull { it.key == key } ?: WARM
    }
}

/** Whether the interface is light or dark, independent of the layout. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色");

    companion object {
        /** The old switch meant following the system. Null means nothing was chosen yet. */
        fun stored(savedKey: String?, legacyFollowsSystem: Boolean): ThemeMode? =
            entries.firstOrNull { it.key == savedKey } ?: if (legacyFollowsSystem) SYSTEM else null
    }
}

/** The mode in effect: without a choice 沉浸暗场 is dark and the other layouts are light. */
internal fun ThemeMode?.orDefaultFor(style: AppThemeStyle): ThemeMode = this ?: if (style == AppThemeStyle.IMMERSIVE) ThemeMode.DARK else ThemeMode.LIGHT

internal fun resolveDark(mode: ThemeMode?, style: AppThemeStyle, systemDark: Boolean): Boolean = when (mode.orDefaultFor(style)) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Preference keys in the "hongguo_local" store. Never rename: they are persisted on users' devices. */
internal object ThemePreferences {
    const val FILE = "hongguo_local"
    const val STYLE = "theme_style"
    /** The 0.7.13 switch 深色模式时自动切换; read only to carry its choice over to [MODE]. */
    const val DARK_FOLLOWS_SYSTEM = "theme_dark_follows_system"
    /** 跟随系统, 浅色 or 深色; absent until the user picks one, so each layout keeps its natural look. */
    const val MODE = "theme_mode"
    /** Set once the user has answered the first-launch theme picker (0.7.3+). */
    const val ONBOARDING_DONE = "theme_onboarding_done"
    /** Which picker the user answered. 0.7.4 gave each theme its own layout, so 0.7.3 answers are asked again once. */
    const val ONBOARDING_VERSION = "theme_onboarding_version"
    const val CURRENT_ONBOARDING_VERSION = 2
    /** 底栏样式; absent until the user picks one, so the device default can still apply. */
    const val NAV_BAR_STYLE = "nav_bar_style"
}

/** Bottom bar look, chosen in 我的 › 设置. */
enum class NavBarStyle(val key: String, val label: String) {
    CLASSIC("classic", "经典"),
    GLASS("glass", "悬浮玻璃");

    companion object {
        /** A saved choice wins; otherwise HyperOS (澎湃OS) starts with the glass capsule and other phones with the classic bar. */
        fun resolve(savedKey: String?, hyperOs: Boolean): NavBarStyle =
            entries.firstOrNull { it.key == savedKey } ?: if (hyperOs) GLASS else CLASSIC
    }
}

/** Every color the screens read. Screens use the named accessors below instead of literal colors. */
@Immutable
internal data class HongguoPalette(
    val isDark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val accentBorder: Color,
    val softAccent: Color,
    val ink: Color,
    val muted: Color,
    val canvas: Color,
    val cream: Color,
    val line: Color,
    val card: Color,
    val navMuted: Color,
    val navDivider: Color,
    val noticeBackground: Color,
    val noticeText: Color,
)

internal val WarmPalette = HongguoPalette(
    isDark = false,
    accent = Color(0xFFC94426), onAccent = Color.White,
    accentText = Color(0xFFB84428), accentBorder = Color(0xFFE6B6A5), softAccent = Color(0xFFFFEADF),
    ink = Color(0xFF292421), muted = Color(0xFF6B625C),
    canvas = Color(0xFFFCF8F4), cream = Color(0xFFF5EDE5), line = Color(0xFFE5DCD5), card = Color.White,
    navMuted = Color(0xFF827970), navDivider = Color(0xFFEAE3DD),
    noticeBackground = Color(0xFFFFEFE7), noticeText = Color(0xFF96492F),
)

internal val WarmDarkPalette = HongguoPalette(
    isDark = true,
    accent = Color(0xFFC94426), onAccent = Color.White,
    accentText = Color(0xFFFF9B7A), accentBorder = Color(0xFF7A4030), softAccent = Color(0xFF3A241C),
    ink = Color(0xFFF5EEE8), muted = Color(0xFFB8ACA3),
    canvas = Color(0xFF1A1512), cream = Color(0xFF2C2520), line = Color(0xFF3A322C), card = Color(0xFF241E1A),
    navMuted = Color(0xFF9C9087), navDivider = Color(0xFF2C2520),
    noticeBackground = Color(0xFF33221B), noticeText = Color(0xFFFFB8A0),
)

internal val ImmersiveLightPalette = HongguoPalette(
    isDark = false,
    accent = Color(0xFFD63A24), onAccent = Color.White,
    accentText = Color(0xFFB8301C), accentBorder = Color(0xFFF0B5A8), softAccent = Color(0xFFFDE9E4),
    ink = Color(0xFF17171B), muted = Color(0xFF62626C),
    canvas = Color(0xFFF6F6F8), cream = Color(0xFFE9E9ED), line = Color(0xFFDFDFE5), card = Color.White,
    navMuted = Color(0xFF6E6E78), navDivider = Color(0xFFE4E4E9),
    noticeBackground = Color(0xFFFDEDE9), noticeText = Color(0xFF9A2E1E),
)

internal val CardsDarkPalette = HongguoPalette(
    isDark = true,
    accent = Color(0xFFD42E24), onAccent = Color.White,
    accentText = Color(0xFFFF8A80), accentBorder = Color(0xFF7A2E28), softAccent = Color(0xFF3A1C1A),
    ink = Color(0xFFF2F3F5), muted = Color(0xFFA5A9B1),
    canvas = Color(0xFF0F1012), cream = Color(0xFF23252A), line = Color(0xFF2C2F35), card = Color(0xFF1A1B1F),
    navMuted = Color(0xFF8F939B), navDivider = Color(0xFF23252A),
    noticeBackground = Color(0xFF2C1A18), noticeText = Color(0xFFFFB4AB),
)

internal val ImmersivePalette = HongguoPalette(
    isDark = true,
    accent = Color(0xFFD63A24), onAccent = Color.White,
    accentText = Color(0xFFFF8B74), accentBorder = Color(0xFF7A3426), softAccent = Color(0xFF3A1E19),
    ink = Color(0xFFF5F5F7), muted = Color(0xFFA3A3AD),
    canvas = Color(0xFF0E0E11), cream = Color(0xFF26262D), line = Color(0xFF2E2E35), card = Color(0xFF1A1A1F),
    navMuted = Color(0xFF8E8E98), navDivider = Color(0xFF26262D),
    noticeBackground = Color(0xFF2A1A16), noticeText = Color(0xFFFFB4A2),
)

internal val CardsPalette = HongguoPalette(
    isDark = false,
    accent = Color(0xFFD42E24), onAccent = Color.White,
    accentText = Color(0xFFB8261D), accentBorder = Color(0xFFF0B3AE), softAccent = Color(0xFFFDE8E6),
    ink = Color(0xFF17181B), muted = Color(0xFF5F646C),
    canvas = Color(0xFFF3F4F6), cream = Color(0xFFE9EBEE), line = Color(0xFFE1E4E8), card = Color.White,
    navMuted = Color(0xFF666B73), navDivider = Color(0xFFE6E8EB),
    noticeBackground = Color(0xFFFDEDEB), noticeText = Color(0xFF9A2A21),
)

internal fun AppThemeStyle.palette(dark: Boolean = this == AppThemeStyle.IMMERSIVE): HongguoPalette = when (this) {
    AppThemeStyle.WARM -> if (dark) WarmDarkPalette else WarmPalette
    AppThemeStyle.IMMERSIVE -> if (dark) ImmersivePalette else ImmersiveLightPalette
    AppThemeStyle.CARDS -> if (dark) CardsDarkPalette else CardsPalette
}

internal val LocalHongguoPalette = staticCompositionLocalOf { WarmPalette }

// Named colors used across the screens. They follow the active theme.
internal val Orange: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.accent
internal val OnOrange: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.onAccent
/** Accent for text and icons: the brand red on light themes, a lighter red on dark so small text stays readable. */
internal val AccentInk: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.let { if (it.isDark) it.accentText else it.accent }
internal val AccentText: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.accentText
internal val AccentBorder: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.accentBorder
internal val Ink: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.ink
internal val Muted: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.muted
internal val Canvas: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.canvas
internal val Cream: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.cream
internal val Line: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.line
internal val SoftOrange: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.softAccent
internal val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.card
internal val NavMuted: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.navMuted
internal val NavDivider: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.navDivider
internal val NoticeBackground: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.noticeBackground
internal val NoticeText: Color @Composable @ReadOnlyComposable get() = LocalHongguoPalette.current.noticeText

private val LightNavigationScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Window background plus system-bar styles for [palette]. Going through enableEdgeToEdge keeps icons, the
 * API 26-28 navigation-bar scrim and the API 29+ contrast handling consistent with the theme, not the system mode.
 */
internal fun ComponentActivity.applyThemeWindow(palette: HongguoPalette, statusBarOverPicture: Boolean = false) {
    window.setBackgroundDrawable(palette.canvas.toArgb().toDrawable())
    val transparent = android.graphics.Color.TRANSPARENT
    enableEdgeToEdge(
        statusBarStyle = if (palette.isDark || statusBarOverPicture) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (palette.isDark) SystemBarStyle.dark(DarkNavigationScrim)
            else SystemBarStyle.light(LightNavigationScrim, DarkNavigationScrim),
    )
}

/**
 * Native Android type and shared surfaces across content, forms and system dialogs.
 * [applyToWindow] is false for nested previews, and while the player owns the system bars. Turning it back on
 * re-applies the theme after the player restores its saved bar state, which may predate a rotation.
 */
@Composable
internal fun HongguoTheme(style: AppThemeStyle = AppThemeStyle.WARM, dark: Boolean = style == AppThemeStyle.IMMERSIVE,
                          windowDark: Boolean = dark, statusBarOverPicture: Boolean = false, applyToWindow: Boolean = true,
                          content: @Composable () -> Unit) {
    val palette = style.palette(dark)
    val windowPalette = style.palette(windowDark)
    val view = LocalView.current
    if (applyToWindow && !view.isInEditMode) {
        LaunchedEffect(windowPalette, statusBarOverPicture) { (view.context as? ComponentActivity)?.applyThemeWindow(windowPalette, statusBarOverPicture) }
    }
    CompositionLocalProvider(LocalHongguoPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.colorScheme(),
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
}

private fun HongguoPalette.colorScheme(): ColorScheme = if (isDark) darkColorScheme(
    primary = accent, onPrimary = onAccent,
    primaryContainer = softAccent, onPrimaryContainer = accentText,
    secondary = accent, onSecondary = onAccent,
    secondaryContainer = softAccent, onSecondaryContainer = accentText,
    background = canvas, onBackground = ink,
    surface = canvas, onSurface = ink, onSurfaceVariant = muted,
    surfaceVariant = cream, outline = line, outlineVariant = line,
    surfaceContainer = card, surfaceContainerLow = canvas,
    surfaceContainerHigh = card, surfaceContainerHighest = cream,
    surfaceTint = Color.Transparent,
) else lightColorScheme(
    primary = accent, onPrimary = onAccent,
    primaryContainer = softAccent, onPrimaryContainer = accent,
    secondary = accent, onSecondary = onAccent,
    secondaryContainer = softAccent, onSecondaryContainer = accent,
    background = canvas, onBackground = ink,
    surface = canvas, onSurface = ink, onSurfaceVariant = muted,
    surfaceVariant = cream, outline = line, outlineVariant = line,
    surfaceContainer = card, surfaceContainerLow = canvas,
    surfaceContainerHigh = card, surfaceContainerHighest = cream,
    surfaceTint = Color.Transparent,
)

private fun nativeStyle(fontFamily: FontFamily, fontSize: TextUnit, lineHeight: TextUnit,
                        fontWeight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = fontFamily, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * Selection for tab-like text (feed, genre, library and range tabs). The default ripple drew a white or grey box
 * behind the label for a moment after each tap; these labels show their state with colour and an underline instead,
 * so a press only dims them briefly.
 */
internal fun Modifier.quietSelectable(selected: Boolean, role: Role = Role.Tab, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dim by animateFloatAsState(if (pressed) .55f else 1f, tween(if (pressed) 60 else 160), label = "tab_press")
    this.selectable(selected = selected, interactionSource = source, indication = null, role = role, onClick = onClick)
        .graphicsLayer { alpha = dim }
}
