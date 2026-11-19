// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import kotlin.math.abs

/**
 * 弹幕的显示参数。除了开关，用户只调四件事：占据屏幕多少高度、字号、透明度、滚动速度。
 * 具体的行数不再单独设置——弹幕层按屏占比拿到高度后，自己用「层高 ÷ 当前字号的行高」
 * 算出能排几行，所以同一个屏占比下，字越大行越少，字越小行越多。
 */
object DanmakuPrefs {
    /** 透明度只给四档，滑杆在播放过程中既难对准也没必要。 */
    val OPACITY_STEPS = listOf(25, 50, 75, 100)
    const val OPACITY_DEFAULT = 100
    val OPACITY_LABELS = OPACITY_STEPS.map { "$it%" }

    /** 屏占比四档：占播放区域的 1/4、1/3、1/2 或全部高度。 */
    val COVERAGE_LABELS = listOf("1/4", "1/3", "1/2", "全屏")
    const val COVERAGE_DEFAULT = 1
    private val COVERAGE_FRACTIONS = floatArrayOf(0.25f, 1f / 3f, 0.5f, 1f)

    /**
     * 字号五档。相对基准的倍数拉开到 0.5～1.55，最小值足够小、最大值足够醒目，
     * 相邻两档之间能明显看出差别。
     */
    val FONT_LABELS = listOf("极小", "小", "标准", "大", "特大")
    const val FONT_DEFAULT = 2
    private val FONT_SCALES = floatArrayOf(0.5f, 0.72f, 0.95f, 1.22f, 1.55f)

    /** 速度五档。最快的跨屏时间压到基准的 0.42 倍，最慢拉到 1.9 倍，档位之间不再挤在一起。 */
    val SPEED_LABELS = listOf("很慢", "慢", "标准", "快", "很快")
    const val SPEED_DEFAULT = 2
    private val SPEED_SCALES = floatArrayOf(1.9f, 1.45f, 1f, 0.62f, 0.42f)

    /** 文字效果三档：纯白字、细黑边、黑阴影，默认沿用阴影。 */
    val EFFECT_LABELS = listOf("白字", "黑边", "阴影")
    const val EFFECT_DEFAULT = 2
    const val EFFECT_WHITE = 0
    const val EFFECT_STROKE = 1
    const val EFFECT_SHADOW = 2

    /** 透明度吸附到最近的一档，旧版本存下的 80% 之类的值也能安全读入。 */
    fun opacity(value: Int) = OPACITY_STEPS.minByOrNull { abs(it - value) } ?: OPACITY_DEFAULT

    /** 透明度落在第几个候选按钮上；面板靠它决定哪一格高亮，别拿百分比直接比下标。 */
    fun opacityIndex(value: Int) = OPACITY_STEPS.indexOf(opacity(value)).coerceAtLeast(0)
    fun font(value: Int) = value.coerceIn(0, FONT_LABELS.lastIndex)
    fun speed(value: Int) = value.coerceIn(0, SPEED_LABELS.lastIndex)
    fun coverage(value: Int) = value.coerceIn(0, COVERAGE_LABELS.lastIndex)
    fun effect(value: Int) = value.coerceIn(0, EFFECT_LABELS.lastIndex)

    fun coverageFraction(value: Int) = COVERAGE_FRACTIONS[coverage(value)]
    fun fontLabel(value: Int) = FONT_LABELS[font(value)]
    fun speedLabel(value: Int) = SPEED_LABELS[speed(value)]
    fun coverageLabel(value: Int) = COVERAGE_LABELS[coverage(value)]
    fun effectLabel(value: Int) = EFFECT_LABELS[effect(value)]
    fun opacityLabel(value: Int) = "${opacity(value)}%"

    /** 字号倍数，乘在画布基准字号上。 */
    fun fontScale(value: Int) = FONT_SCALES[font(value)]

    /** 跨屏时长倍数；数字越大滚得越慢。 */
    fun speedScale(value: Int) = SPEED_SCALES[speed(value)]
}

/** 一次完整的弹幕参数快照，随 [com.hongguo.android.HongguoUiState] 在进程内流转。 */
data class DanmakuSettings(
    val enabled: Boolean = true,
    val coverage: Int = DanmakuPrefs.COVERAGE_DEFAULT,
    val font: Int = DanmakuPrefs.FONT_DEFAULT,
    val opacity: Int = DanmakuPrefs.OPACITY_DEFAULT,
    val effect: Int = DanmakuPrefs.EFFECT_DEFAULT,
    val speed: Int = DanmakuPrefs.SPEED_DEFAULT,
) {
    /** 把每一项夹回支持的档位；持久化里可能有旧版本写入的任意整数。 */
    fun normalized() = copy(
        coverage = DanmakuPrefs.coverage(coverage),
        font = DanmakuPrefs.font(font),
        opacity = DanmakuPrefs.opacity(opacity),
        effect = DanmakuPrefs.effect(effect),
        speed = DanmakuPrefs.speed(speed),
    )
}
