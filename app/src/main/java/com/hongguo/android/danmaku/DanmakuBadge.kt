// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import java.util.Locale

/**
 * 高赞弹幕的点赞数展示规则：20 起显示，并分档压缩，避免长数字占满屏幕。
 *
 * 20~99 向下取整到十位加号（27 → `20+`）；100~999 向下取整到百位加号（456 → `400+`）；
 * 1000 以上用 K（3485 → `3.5K`）；10000 以上用 W（12333 → `1.2W`）。
 */
internal object DanmakuBadge {
    /** 展示门槛：低于这个数不显示。 */
    const val MIN_COUNT = 20

    fun show(count: Int): Boolean = count >= MIN_COUNT

    /** 展示用的文案；低于门槛返回 null，调用方据此决定是否画徽章。 */
    fun label(count: Int): String? = if (show(count)) format(count) else null

    fun format(count: Int): String = when {
        count < 100 -> "${(count / 10) * 10}+"
        count < 1_000 -> "${(count / 100) * 100}+"
        count < 10_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000f)
        else -> String.format(Locale.ROOT, "%.1fW", count / 10_000f)
    }
}
