// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuEmojiTest {
    @Test fun `single code is replaced by its emoji`() {
        assertEquals("😄", DanmakuEmoji.display("[大笑]"))
    }

    @Test fun `consecutive repeated codes are each replaced`() {
        assertEquals("😍😍😍", DanmakuEmoji.display("[爱慕][爱慕][爱慕]"))
    }

    @Test fun `unknown code is kept verbatim`() {
        assertEquals("[不存在的表情]", DanmakuEmoji.display("[不存在的表情]"))
        assertEquals("前缀[不存在的表情]后缀", DanmakuEmoji.display("前缀[不存在的表情]后缀"))
    }

    @Test fun `plain text without brackets is returned unchanged`() {
        val text = "百万富翁了真的好看"
        assertEquals(text, DanmakuEmoji.display(text))
    }

    @Test fun `surrounding text survives replacement`() {
        assertEquals("百万富翁了😍真的😄", DanmakuEmoji.display("百万富翁了[爱慕]真的[大笑]"))
        assertEquals("门给关上呀🤦🤦🤦", DanmakuEmoji.display("门给关上呀[捂脸][捂脸][捂脸]"))
    }

    @Test fun `code lookup is case insensitive`() {
        assertEquals("🐶", DanmakuEmoji.display("[Doge]"))
        assertEquals("🐶", DanmakuEmoji.display("[doge]"))
        assertEquals("👌", DanmakuEmoji.display("[OK]"))
        assertEquals("😲", DanmakuEmoji.display("[What]"))
    }

    @Test fun `unclosed bracket is kept verbatim`() {
        assertEquals("[大笑", DanmakuEmoji.display("[大笑"))
    }

    @Test fun `image sticker placeholder is dropped`() {
        assertEquals("还是躲在水里最自在", DanmakuEmoji.display("还是躲在水里\uFFFC最自在"))
        assertEquals("", DanmakuEmoji.display("\uFFFC"))
    }

    @Test fun `sticker placeholder inside bracketed text still drops`() {
        assertEquals("好看😄", DanmakuEmoji.display("好看\uFFFC[大笑]"))
    }
}

class DanmakuPrefsTest {
    @Test fun `settings normalize every out-of-range value`() {
        val fixed = DanmakuSettings(enabled = false, opacity = 5, font = 9, speed = -1, rows = 99).normalized()
        assertEquals(false, fixed.enabled)
        assertEquals(DanmakuPrefs.OPACITY_DEFAULT, fixed.opacity)
        assertEquals(DanmakuPrefs.FONT_LABELS.lastIndex, fixed.font)
        assertEquals(0, fixed.speed)
        assertEquals(DanmakuPrefs.ROWS_DEFAULT, fixed.rows)
    }

    @Test fun `labels and scales follow the selected step`() {
        assertEquals("较大", DanmakuPrefs.fontLabel(4))
        assertEquals(1.36f, DanmakuPrefs.fontScale(4))
        assertEquals("慢", DanmakuPrefs.speedLabel(0))
        assertEquals(1.5f, DanmakuPrefs.speedScale(0))
    }
}
