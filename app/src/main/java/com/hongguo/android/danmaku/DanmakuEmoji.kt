// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

/**
 * The platform embeds its own emoji codes in bullet-comment text, e.g. "[爱慕]". Drawing the raw
 * text shows literal brackets instead of the picture the official app renders, so the canvas must
 * expand known codes to Unicode emoji first. Unknown codes are left untouched so nothing is lost.
 * The text also carries U+FFFC wherever the official app draws one of its own picture stickers;
 * those cannot be reconstructed from the payload, so the placeholder is dropped rather than left
 * to render as a missing-glyph box.
 */
object DanmakuEmoji {
    /** Codes are stored without brackets; keys are lower-cased so lookups stay case-insensitive. */
    private val TABLE: Map<String, String> = mapOf(
        "大笑" to "😄", "捂脸" to "🤦", "爱慕" to "😍", "赞" to "👍", "笑哭" to "😂",
        "狗头" to "🐶", "doge" to "🐶", "666" to "🙌", "灵光一闪" to "💡", "滑稽" to "🤪",
        "看" to "👀", "what" to "😲", "星星眼" to "🤩", "打脸" to "🤚", "思考" to "🤔",
        "疑问" to "❓", "憨笑" to "😁", "奸笑" to "😏", "酷拽" to "😎", "黑脸" to "🌚",
        "呲牙" to "😁", "偷笑" to "🤭", "得意" to "😌", "小鼓掌" to "👏", "鼓掌" to "👏",
        "比心" to "🫰", "爱心" to "❤️", "生气" to "😠", "发怒" to "😡", "咒骂" to "🤬",
        "流泪" to "😢", "泣不成声" to "😭", "泪奔" to "😭", "大哭" to "😭", "晕" to "😵",
        "困" to "😪", "衰" to "😩", "擦汗" to "😓", "抠鼻" to "😤", "抓狂" to "😫",
        "震惊" to "😱", "惊讶" to "😮", "ok" to "👌", "耶" to "✌️", "酷" to "😎",
        "微笑" to "🙂", "害羞" to "😊", "抱抱" to "🤗", "白眼" to "🙄", "笑" to "😄",
        "鲜花" to "🌹", "玫瑰" to "🌹", "加油" to "💪", "胜利" to "✌️", "厉害" to "👍",
        "无语" to "😑", "叹气" to "😮‍💨", "吃瓜" to "🍉", "柠檬" to "🍋", "尴尬" to "😅",
        "绝望" to "😩", "暗中观察" to "🕵️", "心碎" to "💔", "礼物" to "🎁", "可爱" to "🥰",
        "拜托" to "🙏", "厉害了我的哥" to "😲",
        // Codes observed in live bullet-comment traffic that the initial guess set missed.
        "哭" to "😭", "爽" to "😆", "怒" to "😠", "送花" to "🌹", "盯" to "👀",
        // 2026-11-21 实网扫描（9 集 × 3 个时间窗）补上的缺失码。
        "飞吻" to "😘", "没看够" to "🥺",
        // 同一系列 15 集扫描 + 真机日志补上的缺失码。
        "舔屏" to "😋", "尬笑" to "😅", "送心" to "💕", "什么" to "❓", "撇嘴" to "😒",
        // 2026-11-21 十小时真机使用日志里出现的缺失码。
        "你细品" to "🧐", "快哭了" to "🥲", "伤心" to "😢", "探究" to "🔍",
        "石化" to "🗿", "恐惧" to "😨",
        // 2026-11-21 晚间日志补上的缺失码。
        "做鬼脸" to "😝", "懂了" to "💡", "注意" to "⚠️",
        // 2026-11-21 深夜日志补上的缺失码。
        "学会了" to "🎓", "重拳出击" to "👊", "饱了" to "😋",
        // 2026-11-22 日志补上的缺失码。
        "敬礼" to "🫡", "求爆更" to "🙏", "顶帖" to "🔝", "惊呆" to "😲", "悲伤" to "😔",
        "心疼" to "💔", "点赞" to "👍", "心" to "❤️", "泪" to "😭",
        // 2026-11-22 真机日志里最后剩下没覆盖的 7 个码（同一份日志的 15:20–17:38 段）。
        "泪目" to "🥲", "暖心" to "🥰", "奋斗" to "💪", "跪了" to "🧎", "惊喜" to "🤩",
        "感动" to "🥹", "kiss" to "😘",
        // 2026-11-23 并入桌面版（jackotom/hongguo-drama 8.0）表情表里的 24 个码。
        // 那套名字与平台同源（抖音系常见名），但对方只实测过三集，这 24 个还没在真机日志里出现过。
        "再见" to "👋", "拜拜" to "👋", "握手" to "🤝", "拥抱" to "🤗", "亲亲" to "😘",
        "色" to "😍", "可怜" to "🥺", "难过" to "😞", "开心" to "😄", "坏笑" to "😏",
        "调皮" to "😜", "吐舌" to "😛", "皱眉" to "😣", "翻白眼" to "🙄", "鄙视" to "😒",
        "机智" to "🤓", "灵机一动" to "💡", "捂嘴笑" to "🤭", "汗" to "😓", "睡" to "😴",
        "花" to "🌸", "强" to "💪", "吐血" to "🤮", "破涕为笑" to "😂",
        // 同一批扫描里没有出现、但名称与图形一一对应的常见码，先补上以免将来显示成方括号。
        "抱拳" to "🙏", "膜拜" to "🙇", "红包" to "🧧", "蛋糕" to "🎂", "啤酒" to "🍺",
        "咖啡" to "☕", "牛" to "🐮", "猪" to "🐷", "猫" to "🐱", "摸鱼" to "🐟",
        "干饭" to "🍚", "委屈" to "🥺", "裂开" to "💔", "打call" to "🙌",
    )

    /** Expands every recognized "[code]" to its emoji, leaving the rest of [text] and unknown codes as-is. */
    fun display(text: String): String {
        val hasCode = text.indexOf('[') >= 0
        val hasSticker = text.indexOf(STICKER) >= 0
        // Budget the common case: literal text needs no work at all.
        if (!hasCode && !hasSticker) return text
        val clean = if (hasSticker) text.replace(STICKER.toString(), "") else text
        if (!hasCode) return clean
        val out = StringBuilder(clean.length)
        var i = 0
        while (i < clean.length) {
            val open = clean.indexOf('[', i)
            if (open < 0) {
                out.append(clean, i, clean.length)
                break
            }
            val close = clean.indexOf(']', open + 1)
            if (close < 0) {
                out.append(clean, i, clean.length)
                break
            }
            out.append(clean, i, open)
            val code = clean.substring(open + 1, close)
            val emoji = TABLE[code.lowercase()]
            if (emoji != null) out.append(emoji) else out.append(clean, open, close + 1)
            i = close + 1
        }
        return out.toString()
    }

    private const val STICKER = '\uFFFC'

    /**
     * Ranges of color-emoji clusters inside [text], as UTF-16 [start, end) pairs.
     *
     * 黑边 mode draws the comment twice — a stroke pass for the black edge and a fill pass on top.
     * A stroke paint only outlines real glyphs; a color-emoji glyph is a bitmap that Skia blits
     * whole, so drawing it in both passes composites it twice (75% ends up at about 94% and looks
     * opaque). The view therefore skips the stroke pass over these ranges.
     */
    fun emojiRanges(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            val step = Character.charCount(code)
            if (!isEmojiBase(code)) {
                i += step
                continue
            }
            val start = i
            i += step
            // Pull in variation selectors, skin-tone modifiers, ZWJ sequences and keycaps so a
            // composite emoji (👨‍👩‍👧, ❤️, 1️⃣) is treated as one cluster.
            while (i < text.length) {
                val next = text.codePointAt(i)
                if (!isEmojiTail(next)) break
                i += Character.charCount(next)
            }
            ranges.add(start until i)
        }
        return ranges
    }

    private fun isEmojiBase(code: Int): Boolean =
        code in 0x1F000..0x1FAFF || code in 0x2600..0x27BF || code in 0x2B00..0x2BFF

    private fun isEmojiTail(code: Int): Boolean =
        code == 0xFE0F || code == 0xFE0E || code == 0x200D || code == 0x20E3 ||
            code in 0x1F3FB..0x1F3FF || isEmojiBase(code)
}
