// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val PLATFORM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * The expected release of an upcoming work, as the official app words it ("预计11月21日上线").
 * The platform's own text wins; otherwise [publishAtMs] is formatted in Beijing time, with the year only when it
 * differs from [nowMs]'s. Empty when neither is known.
 */
internal fun releaseLabel(text: String, publishAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    text.replace(Regex("\\s+"), " ").trim().take(40).takeIf { it.isNotEmpty() }?.let { return it }
    if (publishAtMs <= 0) return ""
    val at = Instant.ofEpochMilli(publishAtMs).atZone(PLATFORM_ZONE)
    val year = if (at.year != ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PLATFORM_ZONE).year) "${at.year}年" else ""
    return "预计$year${at.monthValue}月${at.dayOfMonth}日上线"
}
