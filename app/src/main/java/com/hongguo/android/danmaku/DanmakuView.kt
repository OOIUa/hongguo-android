// SPDX-License-Identifier: GPL-3.0-only
// Ported from hongguoTV (HGPlayer) app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuView.kt.
package com.hongguo.android.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.PathParser
import com.hongguo.android.R

/**
 * Lightweight rolling-comment overlay. The playback position is the clock: a comment enters
 * from the right edge when playback crosses its anchor and drifts left at a fixed speed, so
 * pausing, seeking and playback-speed changes need no extra handling. Comments are assigned
 * to the first track whose previous comment has fully entered; when every track is busy the
 * comment is dropped, matching common danmaku behavior.
 */
internal class DanmakuView(context: Context, textSizeSp: Float = 18f, crossMs: Long = 9_000L) : View(context) {
    /**
     * [anchorMs] is when this comment crossed the right edge. It normally equals the comment's
     * own offset, but a rotation re-anchors it so the comment resumes at the same share of its
     * crossing instead of restarting from the right edge.
     */
    private data class Active(
        val danmaku: Danmaku,
        val text: String,
        val textWidth: Float,
        val width: Float,
        val badge: String?,
        val track: Int,
        val anchorMs: Long,
    )

    private val active = ArrayList<Active>()
    var loader: DanmakuLoader? = null
        private set
    private var positionProvider: () -> Long = { 0L }
    private var needMore: (Long) -> Unit = {}
    private var lastPumpAt = 0L
    private var nextTrack = 0
    private var lastEmit = -1L
    private var running = false
    private var dirty = false
    private var pxPerMs = 0f
    private var tracks = 1
    /** Height of one comment line in px; also the vertical pitch between tracks. */
    private var lineHeight = 0f
    private var laidOutWidth = -1
    private var laidOutHeight = -1
    /**
     * 用户设定的那一块高度（px）。视图本身会多给半行左右的高度用来越界绘制，
     * 但算行数时只认这块带子，所以行距固定、最后一行可以稍微顶出去。
     */
    private var bandHeightPx = 0f
    // Danmaku stay independent of the system font size: 18 here means 18dp, not 18sp, so a phone
    // with a huge reading font does not get two giant comments per screen.
    private val baseTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, textSizeSp, resources.displayMetrics)
    private val baseCrossMs = crossMs
    private var currentCrossMs = crossMs
    /** Comments hitting a blocked word are dropped before track allocation. */
    private var blocked: (String) -> Boolean = { false }
    /** 同轨前后间距，随「密度」档位变化。 */
    private var gapPx = 24 * resources.displayMetrics.density
    /** 行距系数，随「密度」档位变化。 */
    private var linePitch = 1.12f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseTextSize
        setShadowLayer(textSize / 6f, 0f, 0f, Color.argb(255, 0, 0, 0))
    }
    /** Outline pass for 黑边; kept separate so the glyphs stay solid white. */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = baseTextSize
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private var effect = DanmakuPrefs.EFFECT_DEFAULT

    /** 高赞弹幕点赞数：小一号的白字，与描边图标并排。 */
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = baseTextSize * BADGE_TEXT_SCALE
        alpha = 230
    }
    /** 点赞数外框：白色描边圆角矩形，内部完全透明（不填充）。 */
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        alpha = 150
    }
    /** 黑边/阴影模式下垫在外框和图标后面的深色轮廓：与正文一致的一圈，而不是向下的浮雕。 */
    private val badgeShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        alpha = 120
    }
    /** 黑边模式下数字的描边 pass。 */
    private val badgeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textSize = baseTextSize * BADGE_TEXT_SCALE
        alpha = 230
    }
    /**
     * 徽章的所有尺寸都以正文字号为基准等比缩放：正文 18dp 时 1 个基准单位 = 1dp。
     * 边框 1、圆角 3、横向内边距 4、纵向 1.5、图标间距 2、与文字间隔 6、深色轮廓 0.5。
     */
    private fun badgeDim(units: Float) = paint.textSize * units / BASE_FONT_DP

    /** 一枚徽章的排版尺寸：测量（碰撞宽度）与绘制共用，避免两处各算一遍。 */
    private class BadgeMetrics(val width: Float, val height: Float, val iconSize: Float)

    private fun badgeMetrics(badge: String): BadgeMetrics {
        // 与正文同高（约 0.9 倍字号），图标和数字都收在这个高度里。
        val height = paint.textSize * BADGE_HEIGHT
        val iconSize = height * BADGE_ICON_RATIO
        return BadgeMetrics(
            width = badgeDim(BADGE_H_PAD) * 2 + iconSize + badgeDim(BADGE_ICON_GAP) +
                badgeTextPaint.measureText(badge),
            height = height,
            iconSize = iconSize,
        )
    }

    /** Material Icons thumb_up 空心描边版（Apache-2.0）：白线勾出拇指，内部透明。 */
    private val likePath = PathParser.createPathFromPathData(
        "M9,21h9c0.83,0 1.54,-0.5 1.84,-1.22l3.02,-7.05c0.09,-0.23 0.14,-0.47 0.14,-0.73v-2c0,-1.1 " +
            "-0.9,-2 -2,-2h-6.31l0.95,-4.57 0.03,-0.32c0,-0.41 -0.17,-0.79 -0.44,-1.06L14.17,1 7.58,7.59" +
            "C7.22,7.95 7,8.45 7,9v10c0,1.1 0.9,2 2,2zM9,9l4.34,-4.34L12,10h9v2l-3,7H9V9zM1,9h4v12H1z",
    )
    private val likeMatrix = Matrix()
    private val likeScaledPath = Path()
    private val likeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    /** 图标的深色轮廓：与正文黑边同款（描边一半在外，看起来才均匀）。 */
    private val likeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    /** 绘制时复用，避免每帧每个徽章都新建矩形。 */
    private val badgeRect = RectF()

    init {
        isFocusable = false
        visibility = GONE
    }

    /**
     * Applies the user's rendering preferences. A font-size change keeps the comments already
     * flying: their text is measured again at the new size, each one keeps its share of the
     * crossing, and they are re-seated into tracks. Opacity and speed apply to what is already
     * flying. The row count is never set from outside: [relayout] derives it from the band height
     * and the current font, so the coverage fraction chosen by the user translates into real rows.
     */
    fun applyPrefs(
        opacityPct: Int,
        font: Int,
        speed: Int,
        effect: Int,
        density: Int = DanmakuPrefs.DENSITY_DEFAULT,
    ) {
        val oldTextSize = paint.textSize
        val alpha = DanmakuPrefs.opacity(opacityPct) * 255 / 100
        paint.alpha = alpha
        paint.textSize = baseTextSize * DanmakuPrefs.fontScale(font)
        this.effect = DanmakuPrefs.effect(effect)
        // 黑边 and 阴影 both need something behind the glyphs; the shadow carries its own colour, and
        // paint.alpha never touches it, so a 25% comment would turn into a grey blob under an opaque
        // black blur. Both the shadow and the outline therefore take the text's alpha as well.
        if (this.effect == DanmakuPrefs.EFFECT_SHADOW) {
            paint.setShadowLayer(paint.textSize / 6f, 0f, 0f, Color.argb(alpha, 0, 0, 0))
        } else {
            // 白字 and 黑边 carry no blur at all.
            paint.clearShadowLayer()
        }
        outlinePaint.textSize = paint.textSize
        outlinePaint.alpha = alpha
        outlinePaint.strokeWidth = (paint.textSize / 14f).coerceAtLeast(1f)
        badgeTextPaint.textSize = paint.textSize * BADGE_TEXT_SCALE
        // 徽章完全跟随用户的「不透明度」设置：100% 时就是纯白，不再额外打折。
        badgeTextPaint.alpha = alpha
        badgeBorderPaint.alpha = alpha
        badgeOutlinePaint.textSize = badgeTextPaint.textSize
        badgeOutlinePaint.alpha = alpha
        badgeOutlinePaint.strokeWidth = (badgeTextPaint.textSize / 14f).coerceAtLeast(1f)
        // 徽章的线宽与深浅也在这里算好，绘制时只负责画，不再改画笔。
        badgeBorderPaint.strokeWidth = badgeDim(BADGE_STROKE)
        badgeShadePaint.strokeWidth = badgeDim(BADGE_STROKE + BADGE_RIM * 2)
        badgeShadePaint.alpha = if (this.effect == DanmakuPrefs.EFFECT_STROKE) {
            alpha
        } else {
            (alpha * 0.45f).toInt().coerceIn(0, 255)
        }
        likeStrokePaint.strokeWidth = badgeDim(BADGE_RIM * 2)
        likeStrokePaint.alpha = when (this.effect) {
            DanmakuPrefs.EFFECT_STROKE -> alpha
            DanmakuPrefs.EFFECT_SHADOW -> (alpha * 0.45f).toInt().coerceIn(0, 255)
            else -> 0
        }
        likeFillPaint.alpha = alpha
        // 数字跟随正文的 阴影 / 黑边 风格：阴影用模糊层，黑边在绘制时多画一遍描边。
        if (this.effect == DanmakuPrefs.EFFECT_SHADOW) {
            badgeTextPaint.setShadowLayer(
                badgeTextPaint.textSize / 6f, 0f, 0f,
                Color.argb(badgeTextPaint.alpha, 0, 0, 0),
            )
        } else {
            badgeTextPaint.clearShadowLayer()
        }
        currentCrossMs = (baseCrossMs * DanmakuPrefs.speedScale(speed)).toLong().coerceAtLeast(1)
        linePitch = DanmakuPrefs.densityLinePitch(density)
        gapPx = DanmakuPrefs.densityGapDp(density) * resources.displayMetrics.density
        relayout()
        if (paint.textSize != oldTextSize) {
            // 字号只是把字放大缩小，不该把正在飞的弹幕清空：按新字号重新量一遍宽度，
            // 保持各自跨屏进度不变，再重新分配轨道，实在排不下的那几条才丢掉。
            reseat(positionProvider().coerceAtLeast(0), remeasure = true)
        }
        invalidate()
    }

    /** 重排屏上弹幕：可选按当前字号重新量宽度，然后按从右到左的顺序重新找轨道。 */
    private fun reseat(position: Long, remeasure: Boolean) {
        val carried = active.toList()
        active.clear()
        val resumed = carried.mapNotNull { item ->
            val progress = (position - item.anchorMs).toFloat() / currentCrossMs
            if (progress < 0f || progress >= 1f) null
            else {
                val textWidth = if (remeasure) paint.measureText(item.text) else item.textWidth
                item.copy(
                    textWidth = textWidth,
                    width = textWidth + badgeWidth(item.badge),
                    anchorMs = position - (progress * currentCrossMs).toLong(),
                )
            }
        }.sortedByDescending { x(it, position) }
        for (item in resumed) {
            val track = (0 until tracks).firstOrNull { candidate ->
                active.none { it.track == candidate && overlaps(it, item, position) }
            }
            if (track != null) active.add(item.copy(track = track))
        }
        lastEmit = position
        dirty = true
    }

    private fun relayout() {
        laidOutWidth = width
        laidOutHeight = height
        if (width <= 0 || height <= 0 || currentCrossMs <= 0) return
        pxPerMs = width.toFloat() / currentCrossMs
        // 行距固定为字高的 1.08 倍，不随屏占比伸缩：行数按「带高 ÷ 行距」四舍五入得到，
        // 于是最后一行可能多顶出去半行左右（比如 1/6 实际占到约 13.3% 再多半行），
        // 也比把行距拉宽塞满整块带更接近用户设定的比例、字距也上下一致。
        val metrics = paint.fontMetrics
        val ink = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        lineHeight = ink * linePitch
        val band = if (bandHeightPx > 0f) minOf(bandHeightPx, height.toFloat()) else height.toFloat()
        // 偏置 0.05：卡在边界上的档位（如 1/6 + 大）多拿一行；最大顶出 0.55 行，
        // 仍在 0.61 行的绘制余量（0.8 × 字高）之内，不会被裁。
        tracks = kotlin.math.round(band / lineHeight + TRACK_ROUND_BIAS).toInt().coerceAtLeast(1)
    }

    /** 告知视图“用户选的那块带子有多高”，视图自身高度可以比它多一点用于越界绘制。 */
    fun setBandHeight(px: Float) {
        if (bandHeightPx == px) return
        bandHeightPx = px
        relayout()
        invalidate()
    }

    /**
     * [position] reports playback milliseconds; [needMore] is raised on the UI thread about once
     * a second while playback advances — the host should schedule [DanmakuLoader.loadUpTo] off-thread.
     */
    fun bind(loader: DanmakuLoader, position: () -> Long, needMore: (Long) -> Unit = {}) {
        unbind()
        this.loader = loader
        positionProvider = position
        this.needMore = needMore
        lastEmit = position().coerceAtLeast(0)
        lastPumpAt = 0L
        running = true
        visibility = VISIBLE
        postOnAnimation(frame)
    }

    fun setBlockedFilter(filter: (String) -> Boolean) {
        blocked = filter
        active.removeAll { blocked(it.text) }
        dirty = true
        invalidate()
    }

    fun unbind() {
        running = false
        loader = null
        needMore = {}
        active.clear()
        lastEmit = -1L
        visibility = GONE
    }

    /** Playback jumped; comments on screen belong to the old position. */
    fun seekTo(positionMs: Long) {
        active.clear()
        lastEmit = positionMs.coerceAtLeast(0)
        dirty = true
        invalidate()
    }

    /**
     * Orientation change (or any resize) keeps the comments already on screen: each one resumes
     * at the same share of its crossing time in the new width, and is re-seated into a free track
     * for the new height. Only comments that would overlap every track are dropped.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val carried = if (oldw > 0 && oldh > 0) active.toList() else emptyList()
        val position = if (carried.isEmpty()) 0L else positionProvider().coerceAtLeast(0)
        relayout()
        active.clear()
        if (carried.isNotEmpty()) {
            val resumed = carried.mapNotNull { item ->
                val progress = (position - item.anchorMs).toFloat() / currentCrossMs
                if (progress < 0f || progress >= 1f) null
                else item.copy(anchorMs = position - (progress * currentCrossMs).toLong())
            }.sortedByDescending { x(it, position) }
            for (item in resumed) {
                val track = (0 until tracks).firstOrNull { candidate ->
                    active.none { it.track == candidate && overlaps(it, item, position) }
                }
                if (track != null) active.add(item.copy(track = track))
            }
            lastEmit = position
            dirty = true
        }
        invalidate()
    }

    /** Two comments collide on a track when their text boxes (plus a gap) intersect. */
    private fun overlaps(first: Active, second: Active, position: Long): Boolean {
        val firstLeft = x(first, position)
        val secondLeft = x(second, position)
        return firstLeft < secondLeft + second.width + gapPx && secondLeft < firstLeft + first.width + gapPx
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            postOnAnimation(this)
        }
    }

    private fun x(item: Active, position: Long) = width - (position - item.anchorMs) * pxPerMs

    private fun step() {
        val loader = loader ?: return
        if (width == 0) return
        if (width != laidOutWidth || height != laidOutHeight) relayout()
        val position = positionProvider().coerceAtLeast(0)
        if (lastEmit < 0) lastEmit = position
        if (position < lastEmit - SEEK_TOLERANCE_MS || position - lastEmit > SEEK_FORWARD_MS) {
            active.clear()
            lastEmit = position
            dirty = true
        }
        if (position > lastEmit) {
            for (comment in loader.between(lastEmit, position)) emit(comment, position)
            lastEmit = position
            dirty = true
        }
        val now = SystemClock.elapsedRealtime()
        if (position > 0 && now - lastPumpAt > 1_000) {
            lastPumpAt = now
            needMore(position)
        }
        val widthF = width.toFloat()
        if (active.removeAll { x(it, position) + it.width < 0 }) dirty = true
        if (dirty) {
            dirty = false
            invalidate()
        }
    }

    private fun emit(comment: Danmaku, position: Long) {
        if (pxPerMs <= 0f) return
        if (blocked(comment.text)) return
        // Expand emoji codes once here: measure and draw must see the same text.
        val text = DanmakuEmoji.display(comment.text)
        val textWidth = paint.measureText(text)
        val badge = DanmakuBadge.label(comment.diggCount)
        val widthF = width.toFloat()
        // Round-robin instead of always top-down: with a sparse feed the comments then spread over
        // the whole coverage band, so 1/4 really looks like a quarter of the picture in use.
        for (offset in 0 until tracks) {
            val track = (nextTrack + offset) % tracks
            // The newest comment on this track is the one furthest right, not the one added last:
            // a rotation re-seats comments in on-screen order, so insertion order stops matching age
            // and the leftmost comment would let a new one start too early, on top of the previous.
            val newest = active.filter { it.track == track }.maxByOrNull { x(it, position) }
            if (newest == null || x(newest, position) + newest.width < widthF - gapPx) {
                active.add(
                    Active(
                        danmaku = comment,
                        text = text,
                        textWidth = textWidth,
                        width = textWidth + badgeWidth(badge),
                        badge = badge,
                        track = track,
                        anchorMs = comment.offsetMs,
                    ),
                )
                nextTrack = (track + 1) % tracks
                return
            }
        }
        // Every track is busy; drop the comment rather than overlapping.
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (active.isEmpty()) return
        val position = positionProvider().coerceAtLeast(0)
        val widthF = width.toFloat()
        val baseline = -paint.fontMetrics.ascent
        val stroked = effect == DanmakuPrefs.EFFECT_STROKE
        for (item in active) {
            val x = x(item, position)
            if (x > widthF || x + item.width < 0) continue
            val y = item.track * lineHeight + baseline
            if (stroked) drawOutline(canvas, item.text, x, y)
            canvas.drawText(item.text, x, y, paint)
            item.badge?.let { drawBadge(canvas, it, x + item.textWidth + badgeDim(BADGE_GAP), y) }
        }
    }

    /** 高赞弹幕的点赞数：白色描边圆角框内放大拇指与小一号数字，框内透明。 */
    private fun drawBadge(canvas: Canvas, badge: String, x: Float, baseline: Float) {
        val layout = badgeMetrics(badge)
        val lineMetrics = paint.fontMetrics
        val centerY = baseline + (lineMetrics.ascent + lineMetrics.descent) / 2f
        val rect = badgeRect
        rect.set(x, centerY - layout.height / 2f, x + layout.width, centerY + layout.height / 2f)
        val corner = minOf(badgeDim(BADGE_CORNER), layout.height / 2f)
        val stroked = effect == DanmakuPrefs.EFFECT_STROKE
        val shadowed = effect == DanmakuPrefs.EFFECT_SHADOW

        // 跟随正文的 黑边 / 阴影：同心的深色轮廓垫底，再画白色框。
        if (stroked || shadowed) canvas.drawRoundRect(rect, corner, corner, badgeShadePaint)
        canvas.drawRoundRect(rect, corner, corner, badgeBorderPaint)

        val iconLeft = x + badgeDim(BADGE_H_PAD)
        val iconTop = centerY - layout.iconSize / 2f
        if (stroked || shadowed) drawLikePath(canvas, iconLeft, iconTop, layout.iconSize, likeStrokePaint)
        drawLikePath(canvas, iconLeft, iconTop, layout.iconSize, likeFillPaint)

        val textMetrics = badgeTextPaint.fontMetrics
        val textBaseline = centerY - (textMetrics.ascent + textMetrics.descent) / 2f
        val textLeft = iconLeft + layout.iconSize + badgeDim(BADGE_ICON_GAP)
        if (stroked) canvas.drawText(badge, textLeft, textBaseline, badgeOutlinePaint)
        canvas.drawText(badge, textLeft, textBaseline, badgeTextPaint)
    }

    /** 按 24×24 的原始路径等比缩放到 [size] 后绘制。 */
    private fun drawLikePath(canvas: Canvas, left: Float, top: Float, size: Float, paint: Paint) {
        val path = likePath ?: return
        likeMatrix.setScale(size / 24f, size / 24f)
        likeScaledPath.set(path)
        likeScaledPath.transform(likeMatrix)
        canvas.save()
        canvas.translate(left, top)
        canvas.drawPath(likeScaledPath, paint)
        canvas.restore()
    }

    private fun badgeWidth(badge: String?): Float = if (badge == null) 0f else badgeMetrics(badge).width
    /**
     * 黑边 pass. Emoji are color bitmaps: a stroke paint outlines glyph shapes, but Skia blits the
     * whole bitmap regardless of the style, so stroking the same text would paint the emoji twice
     * and make them far more opaque than the surrounding words. Only the non-emoji runs get the
     * black edge; the emoji are left to the single fill pass.
     */
    private fun drawOutline(canvas: Canvas, text: String, x: Float, y: Float) {
        var from = 0
        for (range in DanmakuEmoji.emojiRanges(text)) {
            if (from < range.first) canvas.drawText(text, from, range.first, x + paint.measureText(text, 0, from), y, outlinePaint)
            from = range.last + 1
        }
        if (from < text.length) {
            // The stroke pass shares the canvas with the fill pass, so each run has to start where
            // the fill pass would put it: offset it by the width of the prefix at the current size.
            canvas.drawText(text, from, text.length, x + paint.measureText(text, 0, from), y, outlinePaint)
        }
    }

    companion object {
        /** 徽章尺寸的基准字号：正文 18dp 时，1 个徽章单位 = 1dp。 */
        private const val BASE_FONT_DP = 18f
        /** 点赞数文字相对正文字号的比例。 */
        private const val BADGE_TEXT_SCALE = 0.82f
        /** 徽章高度相对正文字号：与正文看起来一样高。 */
        private const val BADGE_HEIGHT = 0.9f
        /** 图标占徽章高度的比例。 */
        private const val BADGE_ICON_RATIO = 0.82f
        /** 徽章几何，单位为「基准单位」（正文 18dp 时等于 dp）。 */
        private const val BADGE_STROKE = 1f
        private const val BADGE_RIM = 0.5f
        private const val BADGE_CORNER = 3f
        private const val BADGE_H_PAD = 4f

        private const val BADGE_ICON_GAP = 2f
        private const val BADGE_GAP = 6f
        private const val SEEK_TOLERANCE_MS = 250L
        private const val SEEK_FORWARD_MS = 1_000L
        /** 行距相对字高的倍数；行数用带高除以它之后四舍五入。 */
        /** 行数四舍五入的偏置：0.05 行约等于「边界情况多给一行」。 */
        private const val TRACK_ROUND_BIAS = 0.05f
    }
}
