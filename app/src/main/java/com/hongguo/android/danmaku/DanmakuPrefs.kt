// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

/**
 * User-tunable bullet-comment (弹幕) rendering preferences. Values persist as plain ints, so every
 * write must pass through these normalizers; the player's danmaku panel and the canvas renderer
 * share the same labels and scales from here.
 */
object DanmakuPrefs {
    const val OPACITY_MIN = 21
    const val OPACITY_MAX = 100
    const val OPACITY_DEFAULT = 100
    const val FONT_DEFAULT = 2
    const val SPEED_DEFAULT = 2
    const val ROWS_MIN = 1
    const val ROWS_MAX = 10
    const val ROWS_DEFAULT = 3
    val FONT_LABELS = listOf("小", "较小", "适中", "大", "较大")
    val SPEED_LABELS = listOf("慢", "较慢", "适中", "快", "较快")

    fun opacity(value: Int) = if (value in OPACITY_MIN..OPACITY_MAX) value else OPACITY_DEFAULT
    fun font(value: Int) = value.coerceIn(0, FONT_LABELS.lastIndex)
    fun speed(value: Int) = value.coerceIn(0, SPEED_LABELS.lastIndex)
    fun rows(value: Int) = if (value in ROWS_MIN..ROWS_MAX) value else ROWS_DEFAULT
    fun fontLabel(value: Int) = FONT_LABELS[font(value)]
    fun speedLabel(value: Int) = SPEED_LABELS[speed(value)]

    /** Text size multiplier per font choice. */
    fun fontScale(value: Int) = when (font(value)) {
        0 -> 0.72f
        1 -> 0.85f
        2 -> 1f
        3 -> 1.18f
        else -> 1.36f
    }

    /** Cross-screen time multiplier per speed choice; a larger factor drifts slower. */
    fun speedScale(value: Int) = when (speed(value)) {
        0 -> 1.5f
        1 -> 1.25f
        2 -> 1f
        3 -> 0.8f
        else -> 0.65f
    }
}

/** One normalized snapshot of every danmaku preference, carried through [com.hongguo.android.HongguoUiState]. */
data class DanmakuSettings(
    val enabled: Boolean = true,
    val opacity: Int = DanmakuPrefs.OPACITY_DEFAULT,
    val font: Int = DanmakuPrefs.FONT_DEFAULT,
    val speed: Int = DanmakuPrefs.SPEED_DEFAULT,
    val rows: Int = DanmakuPrefs.ROWS_DEFAULT,
) {
    /** Clamps every integer to its supported range; persisted values may come from older builds. */
    fun normalized() = copy(
        opacity = DanmakuPrefs.opacity(opacity),
        font = DanmakuPrefs.font(font),
        speed = DanmakuPrefs.speed(speed),
        rows = DanmakuPrefs.rows(rows),
    )
}
