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

    @Test fun `plain text has no emoji ranges`() {
        assertEquals(emptyList<IntRange>(), DanmakuEmoji.emojiRanges("百万富翁了真的好看"))
        assertEquals(emptyList<IntRange>(), DanmakuEmoji.emojiRanges(""))
    }

    @Test fun `emoji range covers the whole surrogate pair`() {
        assertEquals(listOf(2 until 4), DanmakuEmoji.emojiRanges("哈哈😄好看"))
        assertEquals(listOf(0 until 2, 3 until 5), DanmakuEmoji.emojiRanges("😄啊😍"))
    }

    @Test fun `zwj and variation selector clusters stay one range`() {
        // 家庭 emoji（ZWJ 连接三段）与带变体选择符的红心，都要当成一个整体跳过描边。
        val family = "👨‍👩‍👧"
        assertEquals(listOf(0 until family.length), DanmakuEmoji.emojiRanges(family))
        assertEquals(listOf(1 until 3), DanmakuEmoji.emojiRanges("好❤️"))
    }

    @Test fun `ascii digits and punctuation are not emoji`() {
        assertEquals(emptyList<IntRange>(), DanmakuEmoji.emojiRanges("666!!"))
    }
}

class DanmakuPrefsTest {
    @Test fun `settings normalize every out-of-range value`() {
        val fixed = DanmakuSettings(enabled = false, coverage = 9, opacity = 66, font = 9, speed = -1).normalized()
        assertEquals(false, fixed.enabled)
        assertEquals(DanmakuPrefs.COVERAGE_LABELS.lastIndex, fixed.coverage)
        assertEquals(75, fixed.opacity)
        assertEquals(DanmakuPrefs.FONT_LABELS.lastIndex, fixed.font)
        assertEquals(0, fixed.speed)
    }

    @Test fun `labels and scales follow the selected step`() {
        assertEquals("特大", DanmakuPrefs.fontLabel(4))
        assertEquals(1.55f, DanmakuPrefs.fontScale(4))
        assertEquals("很慢", DanmakuPrefs.speedLabel(0))
        assertEquals(1.9f, DanmakuPrefs.speedScale(0))
        assertEquals("很快", DanmakuPrefs.speedLabel(4))
        assertEquals(0.42f, DanmakuPrefs.speedScale(4))
    }

    @Test fun `opacity index lines up with the stored percentage`() {
        assertEquals(0, DanmakuPrefs.opacityIndex(25))
        assertEquals(3, DanmakuPrefs.opacityIndex(100))
        assertEquals(2, DanmakuPrefs.opacityIndex(80))
    }

    @Test fun `text effects clamp to the three choices`() {
        assertEquals("白字", DanmakuPrefs.effectLabel(0))
        assertEquals("黑边", DanmakuPrefs.effectLabel(1))
        assertEquals("阴影", DanmakuPrefs.effectLabel(9))
        assertEquals(DanmakuPrefs.EFFECT_LABELS.lastIndex, DanmakuSettings(effect = 12).normalized().effect)
        assertEquals(DanmakuPrefs.EFFECT_DEFAULT, DanmakuSettings().effect)
    }

    @Test fun `coverage steps map onto screen fractions`() {
        assertEquals("1/6", DanmakuPrefs.coverageLabel(0))
        assertEquals(0.133f, DanmakuPrefs.coverageFraction(0))
        assertEquals("1/4", DanmakuPrefs.coverageLabel(1))
        assertEquals(0.2f, DanmakuPrefs.coverageFraction(1))
        assertEquals(0.2f, DanmakuPrefs.coverageFraction(DanmakuPrefs.COVERAGE_DEFAULT))
        assertEquals(0.25f, DanmakuPrefs.coverageFraction(2))
        assertEquals(0.4f, DanmakuPrefs.coverageFraction(3))
        assertEquals(1f, DanmakuPrefs.coverageFraction(4))
        // 旧存档是四档，读进来要把整组往后挪一格，1/4（旧 0）不能变成 1/5。
        assertEquals(1, DanmakuPrefs.coverageFromLegacy(0))
        assertEquals(2, DanmakuPrefs.coverageFromLegacy(1))
        assertEquals(DanmakuPrefs.COVERAGE_LABELS.lastIndex, DanmakuPrefs.coverageFromLegacy(3))
        // 新存档用千分数，任何一个值都能吸附回最近的一档。
        assertEquals(0, DanmakuPrefs.coverageFromPermille(133))
        assertEquals(1, DanmakuPrefs.coverageFromPermille(200))
        assertEquals(2, DanmakuPrefs.coverageFromPermille(250))
        assertEquals(DanmakuPrefs.COVERAGE_LABELS.lastIndex, DanmakuPrefs.coverageFromPermille(1000))
    }
}
