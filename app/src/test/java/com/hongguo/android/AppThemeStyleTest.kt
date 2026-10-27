package com.hongguo.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeStyleTest {
    @Test
    fun persistedKeysAreStableAndUnknownFallsBackToWarm() {
        assertEquals(listOf("warm", "immersive", "cards"), AppThemeStyle.entries.map { it.key })
        AppThemeStyle.entries.forEach { assertEquals(it, AppThemeStyle.fromKey(it.key)) }
        assertEquals(AppThemeStyle.WARM, AppThemeStyle.fromKey(null))
        assertEquals(AppThemeStyle.WARM, AppThemeStyle.fromKey("removed-style"))
    }

    @Test
    fun onlyImmersiveIsDark() {
        assertEquals(listOf(AppThemeStyle.IMMERSIVE), AppThemeStyle.entries.filter { it.palette().isDark })
    }

    @Test
    fun warmKeepsTheEstablishedColors() {
        val p = AppThemeStyle.WARM.palette()
        assertEquals(Color(0xFFC94426), p.accent)
        assertEquals(Color(0xFF292421), p.ink)
        assertEquals(Color(0xFFFCF8F4), p.canvas)
    }

    @Test
    fun textColorsMeetContrastOnEveryTheme() {
        AppThemeStyle.entries.forEach { style ->
            val p = style.palette()
            val textAccent = if (p.isDark) p.accentText else p.accent
            listOf(
                "button label" to contrast(p.onAccent, p.accent),
                "body on canvas" to contrast(p.ink, p.canvas),
                "body on card" to contrast(p.ink, p.card),
                "secondary on canvas" to contrast(p.muted, p.canvas),
                "secondary on card" to contrast(p.muted, p.card),
                "accent text on card" to contrast(textAccent, p.card),
                "notice" to contrast(p.noticeText, p.noticeBackground),
            ).forEach { (name, ratio) -> assertTrue("${style.key} $name contrast $ratio", ratio >= 4.5) }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + .05
        val lb = b.luminance() + .05
        return maxOf(la, lb).toDouble() / minOf(la, lb).toDouble()
    }
}
