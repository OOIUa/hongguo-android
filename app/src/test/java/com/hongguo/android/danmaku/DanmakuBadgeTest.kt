// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuBadgeTest {
    @Test fun `counts below the threshold are hidden`() {
        assertFalse(DanmakuBadge.show(0))
        assertFalse(DanmakuBadge.show(19))
        assertTrue(DanmakuBadge.show(20))
        assertTrue(DanmakuBadge.show(500))
    }

    @Test fun `under one hundred is floored to the ten and suffixed with plus`() {
        assertEquals("20+", DanmakuBadge.format(20))
        assertEquals("20+", DanmakuBadge.format(27))
        assertEquals("40+", DanmakuBadge.format(49))
        assertEquals("60+", DanmakuBadge.format(63))
        assertEquals("90+", DanmakuBadge.format(99))
    }

    @Test fun `hundreds are floored to the hundred and suffixed with plus`() {
        assertEquals("100+", DanmakuBadge.format(100))
        assertEquals("100+", DanmakuBadge.format(199))
        assertEquals("400+", DanmakuBadge.format(456))
        assertEquals("900+", DanmakuBadge.format(999))
    }

    @Test fun `thousands use k with one decimal`() {
        assertEquals("1.0K", DanmakuBadge.format(1_000))
        assertEquals("3.5K", DanmakuBadge.format(3_485))
        assertEquals("9.9K", DanmakuBadge.format(9_949))
    }

    @Test fun `ten thousand and above use w with one decimal`() {
        assertEquals("1.0W", DanmakuBadge.format(10_000))
        assertEquals("1.2W", DanmakuBadge.format(12_333))
        assertEquals("23.4W", DanmakuBadge.format(234_000))
    }
}
