package com.hongguo.android

import android.annotation.SuppressLint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Detects Xiaomi HyperOS (澎湃OS) on Xiaomi / Redmi / POCO devices; used for the default 底栏样式. MIUI and other vendors return false. */
internal object SystemRom {
    /** Present only on HyperOS builds (e.g. "1", "2", "4"); MIUI uses ro.miui.ui.version.* instead. */
    private const val HYPER_OS_VERSION_CODE = "ro.mi.os.version.code"

    val isHyperOs: Boolean by lazy { isHyperOs(::systemProperty) }

    internal fun isHyperOs(read: (String) -> String?): Boolean = !read(HYPER_OS_VERSION_CODE).isNullOrBlank()

    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (_: Throwable) {
        // Only when reflection is unavailable; avoids spawning a process on every non-Xiaomi phone.
        runCatching {
            ProcessBuilder("getprop", key).redirectErrorStream(true).start()
                .inputStream.bufferedReader().use { it.readLine() }
        }.getOrNull()
    }
}

/**
 * Extra bottom space a page must leave so its last item can scroll clear of the floating capsule.
 * Zero wherever the classic bar is used, because there the page already ends above the bar.
 */
internal val LocalNavClearance = compositionLocalOf { 0.dp }

/** [PaddingValues] with [LocalNavClearance] added to the bottom. */
@Composable
internal fun PaddingValues.plusNavClearance(): PaddingValues {
    val clearance = LocalNavClearance.current
    if (clearance == 0.dp) return this
    val direction = LocalLayoutDirection.current
    return PaddingValues(start = calculateStartPadding(direction), top = calculateTopPadding(),
        end = calculateEndPadding(direction), bottom = calculateBottomPadding() + clearance)
}

private val CapsuleHeight = 62.dp
private val CapsuleGap = 8.dp
private val CapsuleSideMargin = 20.dp
private val CapsuleShape = RoundedCornerShape(50)
private val GlassBlurDp = 8.dp
private val GlassBevelDp = 15.dp
private val GlassRefractionDp = 17.dp

/** Space the capsule occupies above the bottom edge, including the system navigation bar. */
@Composable
internal fun floatingNavClearance(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + CapsuleGap + CapsuleHeight + 12.dp

/**
 * Offscreen sampling scale for the backdrop buffer (~240 dpi effective), keeping blur + AGSL
 * cost flat across 1080p/1.5K/2K screens while staying smooth under an 8 dp optical blur.
 */
internal fun glassBackdropScale(density: Float): Float =
    if (density <= 0f) 0.5f else (1.5f / density).coerceIn(0.45f, 0.75f)

/** Reusable mutable holder for the moving selection droplet so drawing allocates zero objects per frame. */
internal class LensScratch {
    var left: Float = 0f; private set
    var top: Float = 0f; private set
    var width: Float = 0f; private set
    var height: Float = 0f; private set
    var liquid: Float = 0f; private set

    fun update(
        barWidth: Float,
        barHeight: Float,
        tabCount: Int,
        position: Float,
        velocity: Float,
        press: Float,
        insetX: Float,
        insetY: Float,
        crossGrowth: Float,
        vertical: Boolean = false,
    ) {
        val trackLength = if (vertical) barHeight else barWidth
        val trackWidth = if (vertical) barWidth else barHeight
        val alongInset = if (vertical) insetY else insetX
        val crossInset = if (vertical) insetX else insetY
        val itemLength = trackLength / tabCount.coerceAtLeast(1)
        val clampedPress = press.coerceIn(0f, 1f)
        val speed = abs(velocity)
        // Stretch along the gesture and compress across it, in either orientation.
        val stretch = (speed * 0.014f).coerceIn(0f, 0.24f)
        val glideGlow = (speed * 0.085f).coerceIn(0f, 0.85f)
        liquid = maxOf(clampedPress, glideGlow)
        val endMargin = minOf(alongInset * (1f - 0.5f * clampedPress), trackLength * 0.5f)
        val baseLength = (itemLength - alongInset * 2f).coerceAtLeast(0f)
        val length = (baseLength + itemLength * (0.14f * clampedPress + 0.34f * stretch))
            .coerceAtMost(trackLength - endMargin * 2f)
        val baseWidth = (trackWidth - crossInset * 2f).coerceAtLeast(0f)
        val crossExpand = crossGrowth * (clampedPress - 0.45f * stretch)
        val breadth = (baseWidth + crossExpand * 2f)
            .coerceIn(baseWidth * 0.86f, (trackWidth - 2f).coerceAtLeast(baseWidth))
        val center = (position + 0.5f) * itemLength
        val along = (center - length * 0.5f).coerceIn(endMargin, (trackLength - length - endMargin).coerceAtLeast(endMargin))
        val across = (trackWidth - breadth) * 0.5f
        if (vertical) {
            top = along
            left = across
            height = length
            width = breadth
        } else {
            left = along
            top = across
            width = length
            height = breadth
        }
    }
}

/**
 * What sits behind the glass. The page records itself into [layer] once per frame and the capsule re-draws that
 * same layer downsampled, blurred, and refracted, so no second composition of the page is needed.
 */
@Stable
internal class GlassBackdrop(val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    /** Bumped whenever the page re-records, so the capsule redraws even when only content behind it moved. */
    internal var version by mutableIntStateOf(0)
}

@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Marks the page content as the glass backdrop. Put it before the page's own background in the modifier chain.
 * On Android 8–11 (API < 31) backdrop blur is unavailable, so this is a no-op with zero recording overhead.
 */
internal fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this
    else this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            // Only the glass surface should observe this counter when the page records a new frame.
            Snapshot.withoutReadObservation { backdrop.version++ }
        }

/**
 * iOS 26 / HyperOS style floating liquid-glass capsule.
 * Android 13+: downsampled crisp blur + fused AGSL optical bezel refraction, color vibrancy, luminance-adaptive
 * translucency, and a live refractive selection droplet that swells & stretches during drag or tab glide.
 * Android 12: downsampled crisp blur + vibrancy color filter + native specular rims.
 * Android 8–11: high-craft frosted glass fill with zero offscreen backdrop recording.
 */
@Composable
internal fun LiquidGlassNavigationBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    tabs: List<AppNavTab>,
    style: AppThemeStyle,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    overVideo: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val palette = LocalHongguoPalette.current
    val scope = rememberCoroutineScope()
    val selectedIndex = tabs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    val activeColor = if (style == AppThemeStyle.WARM) AccentInk else Ink
    val idleColor = Ink.copy(alpha = if (palette.isDark) .78f else .72f)

    // iOS 26 tab bar physics: press and drag the capsule so the selection lens turns into a refractive liquid
    // droplet, ticks as it crosses each tab, and springs to the nearest tab (or one further on a flick) on release.
    var dragging by remember(vertical) { mutableStateOf(false) }
    var dragPos by remember(vertical) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var hovered by remember(vertical) { mutableIntStateOf(selectedIndex) }
    val follow = remember(vertical) { Animatable(selectedIndex.toFloat()) }
    val followSpring = spring<Float>(dampingRatio = .84f, stiffness = 1400f)
    val settle = remember(vertical) { Animatable(selectedIndex.toFloat()) }
    val press = animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = .56f, stiffness = Spring.StiffnessMediumLow),
        label = "glass_press",
    )
    LaunchedEffect(selectedIndex, vertical) {
        if (!dragging) {
            hovered = selectedIndex
            settle.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(dampingRatio = .70f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    // Read only inside draw / graphicsLayer scopes so motion never recomposes the bar.
    val lensPosition = remember(follow, settle) { { if (dragging) follow.value else settle.value } }
    val lensVelocity = remember(follow, settle) { { if (dragging) follow.velocity else settle.velocity } }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentTabs by rememberUpdatedState(tabs)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectTab = remember {
        { index: Int ->
            keyboard?.hide()
            focus.clearFocus()
            currentOnSelect(currentTabs[index].tab)
        }
    }

    var capsuleOrigin by remember { mutableStateOf(Offset.Zero) }
    val isDark = palette.isDark
    val base = Canvas

    // The landscape host supplies the rail's size and positions it within the safe area.
    val container = if (vertical) modifier else modifier.fillMaxWidth()
        .padding(WindowInsets.navigationBars.asPaddingValues())
        .padding(start = CapsuleSideMargin, end = CapsuleSideMargin, bottom = CapsuleGap)
    val capsule = if (vertical) Modifier.fillMaxSize()
        else Modifier.widthIn(max = 440.dp).fillMaxWidth().height(CapsuleHeight)
    Box(container.semantics { testTagsAsResourceId = true }, contentAlignment = Alignment.BottomCenter) {
        Box(
            capsule
                // The whole capsule eases up gently while held, like pressing on elastic glass.
                .graphicsLayer {
                    val p = press.value
                    scaleX = 1f + (if (vertical) .042f else .022f) * p
                    scaleY = 1f + (if (vertical) .022f else .042f) * p
                }
                // Hardware rounded-rect clip + soft floating elevation (avoids per-frame Path + clipPath).
                .shadow(
                    elevation = if (isDark) 16.dp else 12.dp,
                    shape = CapsuleShape,
                    clip = true,
                    ambientColor = Color.Black.copy(alpha = if (isDark) .42f else .14f),
                    spotColor = Color.Black.copy(alpha = if (isDark) .50f else .18f),
                )
                .onGloballyPositioned { capsuleOrigin = it.positionInRoot() }
                .pointerInput(tabs.size, vertical) {
                    val velocity = VelocityTracker()
                    val count = tabs.size
                    fun itemExtent() = (if (vertical) size.height else size.width) / count.toFloat()
                    fun finish() {
                        val speed = velocity.calculateVelocity()
                        val tabsPerSecond = (if (vertical) speed.y else speed.x) / itemExtent()
                        // A quick flick carries the lens on in its direction, at most about one more tab.
                        val projected = dragPos + (tabsPerSecond * .12f).coerceIn(-.6f, .6f)
                        val target = projected.roundToInt().coerceIn(0, count - 1)
                        scope.launch {
                            settle.snapTo(follow.value)
                            dragging = false
                            hovered = target
                            settle.animateTo(
                                targetValue = target.toFloat(),
                                animationSpec = spring(dampingRatio = .62f, stiffness = Spring.StiffnessMediumLow),
                                initialVelocity = tabsPerSecond.coerceIn(-12f, 12f),
                            )
                        }
                        if (target != currentSelectedIndex) selectTab(target)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                    fun start(position: Offset) {
                        velocity.resetTracking()
                        val along = if (vertical) position.y else position.x
                        dragPos = (along / itemExtent() - .5f).coerceIn(0f, count - 1f)
                        hovered = dragPos.roundToInt()
                        val from = settle.value
                        dragging = true
                        scope.launch { follow.snapTo(from); follow.animateTo(dragPos, followSpring) }
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                    fun drag(change: PointerInputChange, amount: Float) {
                        change.consume()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        dragPos = (dragPos + amount / itemExtent()).coerceIn(-.3f, count - 1 + .3f)
                        scope.launch { follow.animateTo(dragPos, followSpring) }
                        val now = dragPos.roundToInt().coerceIn(0, count - 1)
                        if (now != hovered) {
                            hovered = now
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    }
                    fun cancel() {
                        dragging = false
                        hovered = currentSelectedIndex
                    }
                    if (vertical) detectVerticalDragGestures(::start, ::finish, ::cancel, ::drag)
                    else detectHorizontalDragGestures(::start, ::finish, ::cancel, ::drag)
                },
        ) {
            // Isolated composable so tab label/icon color animations never recompose or invalidate the backdrop.
            GlassCapsuleSurface(
                backdrop = backdrop,
                capsuleOrigin = { capsuleOrigin },
                tabCount = tabs.size,
                lensPosition = lensPosition,
                lensVelocity = lensVelocity,
                press = { press.value },
                isDark = isDark,
                baseColor = base,
                vertical = vertical,
                overVideo = overVideo,
            )
            GlassTabRow(
                tabs = tabs,
                selectedIndex = selectedIndex,
                highlightedIndex = if (dragging) hovered else selectedIndex,
                activeColor = activeColor,
                idleColor = idleColor,
                lensPosition = lensPosition,
                press = { press.value },
                onSelectIndex = selectTab,
                vertical = vertical,
            )
        }
    }
}

@Composable
private fun GlassCapsuleSurface(
    backdrop: GlassBackdrop,
    capsuleOrigin: () -> Offset,
    tabCount: Int,
    lensPosition: () -> Float,
    lensVelocity: () -> Float,
    press: () -> Float,
    isDark: Boolean,
    baseColor: Color,
    vertical: Boolean = false,
    overVideo: Boolean = false,
) {
    val glass = rememberGraphicsLayer()
    val effects = remember { GlassEffectCache() }
    val recordCache = remember(backdrop) { GlassRecordCache() }
    val brushes = remember { GlassBrushCache() }
    val lens = remember { LensScratch() }

    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                val radius = size.minDimension * 0.5f
                val p = press()
                lens.update(
                    barWidth = size.width,
                    barHeight = size.height,
                    tabCount = tabCount,
                    position = lensPosition(),
                    velocity = lensVelocity(),
                    press = p,
                    insetX = 4.5.dp.toPx(),
                    insetY = 4.5.dp.toPx(),
                    crossGrowth = 3.2.dp.toPx(),
                    vertical = vertical,
                )
                val lensRadius = minOf(lens.width, lens.height) * 0.5f

                // 1. Glass backdrop (Android 12+ downsampled offscreen blur + Android 13+ fused AGSL refraction)
                if (overVideo) {
                    // SurfaceView is composited outside the page's graphics layer. Alpha lets those
                    // frames show through while keeping the navigation labels readable over bright scenes.
                    drawRect(if (isDark) Color(0xA0181B21) else Color(0xB3FAFAFC))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val version = backdrop.version // observe page redraws
                    val offset = capsuleOrigin() - backdrop.origin
                    val scale = glassBackdropScale(density)
                    val blurPx = GlassBlurDp.toPx()
                    // Only blur margin is needed: refraction always samples inward inside the capsule.
                    val pad = ceil(blurPx * 2f + 2.dp.toPx())
                    val baseArgb = baseColor.toArgb()

                    if (recordCache.needsRecord(version, size.width, size.height, offset.x, offset.y, baseArgb, scale)) {
                        val recW = ceil((size.width + pad * 2f) * scale).toInt().coerceAtLeast(1)
                        val recH = ceil((size.height + pad * 2f) * scale).toInt().coerceAtLeast(1)
                        glass.record(IntSize(recW, recH)) {
                            // Fill padded margin with canvas color so bottom overhang never blurs transparent black.
                            drawRect(baseColor)
                            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero) {
                                translate(pad - offset.x, pad - offset.y) { drawLayer(backdrop.layer) }
                            }
                        }
                        recordCache.markRecorded(version, size.width, size.height, offset.x, offset.y, baseArgb, scale)
                    }

                    val lensCx = (pad + lens.left + lens.width * 0.5f) * scale
                    val lensCy = (pad + lens.top + lens.height * 0.5f) * scale
                    val lensHw = (lens.width * 0.5f) * scale
                    val lensHh = (lens.height * 0.5f) * scale
                    glass.renderEffect = effects.get(
                        width = size.width * scale,
                        height = size.height * scale,
                        pad = pad * scale,
                        radius = radius * scale,
                        blur = blurPx * scale,
                        bevel = GlassBevelDp.toPx() * scale,
                        refraction = GlassRefractionDp.toPx() * scale,
                        isDark = isDark,
                        lensCx = lensCx,
                        lensCy = lensCy,
                        lensHw = lensHw,
                        lensHh = lensHh,
                        lensRadius = lensRadius * scale,
                        lensAmount = lens.liquid,
                    )
                    drawRect(baseColor)
                    scale(scaleX = 1f / scale, scaleY = 1f / scale, pivot = Offset.Zero) {
                        translate(-pad * scale, -pad * scale) { drawLayer(glass) }
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        // API 31–32 has blur + vibrancy without AGSL adaptive tinting.
                        drawRect(brushes.api31Tint(size.height, isDark))
                    }
                } else {
                    drawRect(brushes.legacyFill(size.height, isDark))
                }

                // 2. Selection pill (subtle frosted rest state -> crystal 3D liquid droplet when pressed or gliding)
                val pillSize = Size(lens.width, lens.height)
                val pillCorner = CornerRadius(lensRadius)
                val liquid = lens.liquid
                val hasAgsl = !overVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                translate(lens.left, lens.top) {
                    // Resting pill softens into a clear glass glaze while the droplet lifts & refracts underneath.
                    val restWeight = if (hasAgsl) (1f - 0.72f * liquid) else (1f - 0.35f * liquid)
                    val restColor = if (isDark) Color.White.copy(alpha = 0.145f * restWeight)
                    else Color.Black.copy(alpha = 0.068f * restWeight)
                    drawRoundRect(color = restColor, size = pillSize, cornerRadius = pillCorner)

                    // Subtle inner top highlight even at rest so the pill reads as nested glass.
                    val restSheenAlpha = (1f - liquid) * (if (isDark) 0.16f else 0.45f)
                    if (restSheenAlpha > 0.01f) {
                        val restStroke = 0.75.dp.toPx()
                        drawRoundRect(
                            brush = brushes.restingPillRim(lens.height, isDark),
                            topLeft = Offset(restStroke * 0.5f, restStroke * 0.5f),
                            size = Size(lens.width - restStroke, lens.height - restStroke),
                            cornerRadius = CornerRadius((lensRadius - restStroke * 0.5f).coerceAtLeast(0f)),
                            alpha = restSheenAlpha,
                            style = Stroke(restStroke),
                        )
                    }

                    if (liquid > 0.01f) {
                        // Luminous glaze + curved top dome reflection + razor-sharp 1x directional Fresnel rim.
                        val glazeAlpha = (if (hasAgsl) (if (isDark) 0.09f else 0.14f) else (if (isDark) 0.16f else 0.24f)) * liquid
                        drawRoundRect(color = Color.White.copy(alpha = glazeAlpha), size = pillSize, cornerRadius = pillCorner)
                        drawRoundRect(
                            brush = brushes.dropletSheen(lens.height, isDark),
                            size = Size(lens.width, lens.height * 0.56f),
                            cornerRadius = pillCorner,
                            alpha = liquid,
                        )
                        val dropletStroke = 1.15.dp.toPx()
                        drawRoundRect(
                            brush = brushes.dropletRim(lens.width, lens.height, isDark),
                            topLeft = Offset(dropletStroke * 0.5f, dropletStroke * 0.5f),
                            size = Size(lens.width - dropletStroke, lens.height - dropletStroke),
                            cornerRadius = CornerRadius((lensRadius - dropletStroke * 0.5f).coerceAtLeast(0f)),
                            alpha = liquid,
                            style = Stroke(dropletStroke),
                        )
                    }
                }

                // 3. Native 1x capsule outer Fresnel specular rim (+ subtle light-mode hairline for white pages)
                val rim = 1.15.dp.toPx()
                drawRoundRect(
                    brush = brushes.capsuleRim(size.width, size.height, isDark),
                    topLeft = Offset(rim * 0.5f, rim * 0.5f),
                    size = Size(size.width - rim, size.height - rim),
                    cornerRadius = CornerRadius(radius - rim * 0.5f),
                    style = Stroke(rim),
                )
                if (!isDark) {
                    val hairline = 0.5.dp.toPx()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.075f),
                        topLeft = Offset(hairline * 0.5f, hairline * 0.5f),
                        size = Size(size.width - hairline, size.height - hairline),
                        cornerRadius = CornerRadius(radius - hairline * 0.5f),
                        style = Stroke(hairline),
                    )
                }
            },
    )
}

@Composable
private fun GlassTabRow(
    tabs: List<AppNavTab>,
    selectedIndex: Int,
    highlightedIndex: Int,
    activeColor: Color,
    idleColor: Color,
    lensPosition: () -> Float,
    press: () -> Float,
    onSelectIndex: (Int) -> Unit,
    vertical: Boolean,
) {
    val items: @Composable (Modifier) -> Unit = { itemModifier ->
        tabs.forEachIndexed { index, item ->
            val isSelected = index == selectedIndex
            val highlighted = index == highlightedIndex
            val color by animateColorAsState(
                targetValue = if (highlighted) activeColor else idleColor,
                animationSpec = tween(160),
                label = "glass_tab_color",
            )
            val pop = remember { Animatable(1f) }
            LaunchedEffect(isSelected) {
                if (isSelected) {
                    pop.snapTo(.84f)
                    pop.animateTo(1f, spring(dampingRatio = .44f, stiffness = 420f))
                }
            }
            Column(
                itemModifier
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelectIndex(index) }
                    .testTag("nav_${item.tab.name.lowercase(Locale.ROOT)}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (highlighted) item.selectedIcon else item.icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            val p = press()
                            val delta = index - lensPosition()
                            val near = (1f - abs(delta)).coerceIn(0f, 1f)
                            // Optical smoothstep magnification and subtle parallax along the drag axis.
                            val smoothNear = near * near * (3f - 2f * near)
                            val scale = pop.value * (1f + .18f * smoothNear * p)
                            scaleX = scale
                            scaleY = scale
                            val parallax = -2.2.dp.toPx() * delta * smoothNear * p
                            translationX = if (vertical) 0f else parallax
                            translationY = if (vertical) parallax else -2.dp.toPx() * smoothNear * p
                        },
                )
                Text(
                    text = item.label,
                    color = color,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    if (vertical) {
        Column(Modifier.fillMaxSize().selectableGroup()) {
            items(Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            items(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Tracks whether the offscreen backdrop layer needs re-recording (skipped during static-page lens motion). */
private class GlassRecordCache {
    private var version = Int.MIN_VALUE
    private var width = Float.NaN
    private var height = Float.NaN
    private var offsetX = Float.NaN
    private var offsetY = Float.NaN
    private var baseArgb = 0
    private var scale = Float.NaN

    fun needsRecord(
        version: Int,
        width: Float,
        height: Float,
        offsetX: Float,
        offsetY: Float,
        baseArgb: Int,
        scale: Float,
    ): Boolean = this.version != version ||
        this.width != width ||
        this.height != height ||
        this.offsetX != offsetX ||
        this.offsetY != offsetY ||
        this.baseArgb != baseArgb ||
        this.scale != scale

    fun markRecorded(
        version: Int,
        width: Float,
        height: Float,
        offsetX: Float,
        offsetY: Float,
        baseArgb: Int,
        scale: Float,
    ) {
        this.version = version
        this.width = width
        this.height = height
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.baseArgb = baseArgb
        this.scale = scale
    }
}

/** Caches gradient brushes by dimension & theme so scrolling and steady drags allocate zero objects. */
private class GlassBrushCache {
    private var capsuleW = Float.NaN; private var capsuleH = Float.NaN; private var capsuleDark = -1
    private var capsuleRimBrush: Brush? = null

    private var api31H = Float.NaN; private var api31Dark = -1
    private var api31Brush: Brush? = null

    private var legacyH = Float.NaN; private var legacyDark = -1
    private var legacyBrush: Brush? = null

    private var restH = Float.NaN; private var restDark = -1
    private var restBrush: Brush? = null

    private var sheenH = Float.NaN; private var sheenDark = -1
    private var sheenBrush: Brush? = null

    private var dropletW = Float.NaN; private var dropletH = Float.NaN; private var dropletDark = -1
    private var dropletRimBrush: Brush? = null

    fun capsuleRim(width: Float, height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (capsuleRimBrush == null || capsuleW != width || capsuleH != height || capsuleDark != d) {
            capsuleW = width; capsuleH = height; capsuleDark = d
            capsuleRimBrush = Brush.linearGradient(
                0.00f to Color.White.copy(alpha = if (isDark) 0.52f else 0.94f),
                0.28f to Color.White.copy(alpha = if (isDark) 0.14f else 0.28f),
                0.52f to Color.White.copy(alpha = if (isDark) 0.05f else 0.06f),
                0.80f to Color.White.copy(alpha = if (isDark) 0.18f else 0.34f),
                1.00f to Color.White.copy(alpha = if (isDark) 0.34f else 0.72f),
                start = Offset.Zero,
                end = Offset(width * 0.36f, height * 2.15f),
            )
        }
        return capsuleRimBrush!!
    }

    fun api31Tint(height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (api31Brush == null || api31H != height || api31Dark != d) {
            api31H = height; api31Dark = d
            api31Brush = if (isDark) {
                Brush.verticalGradient(
                    0f to Color(0xFF282B33).copy(alpha = 0.42f),
                    1f to Color(0xFF16181D).copy(alpha = 0.36f),
                    startY = 0f, endY = height,
                )
            } else {
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.42f),
                    1f to Color(0xFFF6F7FA).copy(alpha = 0.30f),
                    startY = 0f, endY = height,
                )
            }
        }
        return api31Brush!!
    }

    fun legacyFill(height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (legacyBrush == null || legacyH != height || legacyDark != d) {
            legacyH = height; legacyDark = d
            legacyBrush = if (isDark) {
                Brush.verticalGradient(
                    0f to Color(0xFF272930).copy(alpha = 0.92f),
                    1f to Color(0xFF1A1C21).copy(alpha = 0.92f),
                    startY = 0f, endY = height,
                )
            } else {
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.94f),
                    1f to Color(0xFFF5F5F8).copy(alpha = 0.90f),
                    startY = 0f, endY = height,
                )
            }
        }
        return legacyBrush!!
    }

    fun restingPillRim(height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (restBrush == null || restH != height || restDark != d) {
            restH = height; restDark = d
            restBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (isDark) 0.55f else 0.85f),
                0.55f to Color.White.copy(alpha = 0.04f),
                1f to Color.White.copy(alpha = if (isDark) 0.16f else 0.25f),
                startY = 0f, endY = height,
            )
        }
        return restBrush!!
    }

    fun dropletSheen(height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (sheenBrush == null || sheenH != height || sheenDark != d) {
            sheenH = height; sheenDark = d
            sheenBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (isDark) 0.24f else 0.34f),
                1f to Color.Transparent,
                startY = 0f, endY = height * 0.56f,
            )
        }
        return sheenBrush!!
    }

    fun dropletRim(width: Float, height: Float, isDark: Boolean): Brush {
        val d = if (isDark) 1 else 0
        if (dropletRimBrush == null || dropletW != width || dropletH != height || dropletDark != d) {
            dropletW = width; dropletH = height; dropletDark = d
            dropletRimBrush = Brush.linearGradient(
                0.00f to Color.White.copy(alpha = if (isDark) 0.65f else 0.96f),
                0.35f to Color.White.copy(alpha = if (isDark) 0.14f else 0.22f),
                0.65f to Color.White.copy(alpha = if (isDark) 0.10f else 0.14f),
                1.00f to Color.White.copy(alpha = if (isDark) 0.38f else 0.68f),
                start = Offset.Zero,
                end = Offset(width * 0.55f, height * 1.4f),
            )
        }
        return dropletRimBrush!!
    }
}

/**
 * Caches compiled AGSL shader, blur filter, and resulting RenderEffect using primitive comparisons
 * (zero per-frame List / boxed-Float allocations). At rest (lensAmount == 0) the exact same RenderEffect
 * reference is reused across every scroll frame.
 */
private class GlassEffectCache {
    private var cachedBlur = Float.NaN
    private var blurEffect: PlatformRenderEffect? = null
    private var api31Effect: androidx.compose.ui.graphics.RenderEffect? = null

    private var shader: RuntimeShader? = null
    private var cachedW = Float.NaN
    private var cachedH = Float.NaN
    private var cachedPad = Float.NaN
    private var cachedRadius = Float.NaN
    private var cachedBevel = Float.NaN
    private var cachedRefraction = Float.NaN
    private var cachedDark = -1

    private var restingEffect: androidx.compose.ui.graphics.RenderEffect? = null

    private var activeEffect: androidx.compose.ui.graphics.RenderEffect? = null
    private var lastLx = Float.NaN
    private var lastLy = Float.NaN
    private var lastLhw = Float.NaN
    private var lastLhh = Float.NaN
    private var lastLr = Float.NaN
    private var lastLAmt = Float.NaN

    @RequiresApi(Build.VERSION_CODES.S)
    fun get(
        width: Float,
        height: Float,
        pad: Float,
        radius: Float,
        blur: Float,
        bevel: Float,
        refraction: Float,
        isDark: Boolean,
        lensCx: Float,
        lensCy: Float,
        lensHw: Float,
        lensHh: Float,
        lensRadius: Float,
        lensAmount: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val blurChanged = blurEffect == null || cachedBlur != blur
        if (blurChanged) {
            cachedBlur = blur
            blurEffect = PlatformRenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
            api31Effect = null
            restingEffect = null
            activeEffect = null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (api31Effect == null) {
                val vibrancy = ColorMatrix().apply { setSaturation(1.30f) }
                api31Effect = PlatformRenderEffect.createChainEffect(
                    PlatformRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(vibrancy)),
                    blurEffect!!,
                ).asComposeRenderEffect()
            }
            return api31Effect!!
        }
        return getApi33(
            width, height, pad, radius, bevel, refraction, isDark,
            lensCx, lensCy, lensHw, lensHh, lensRadius, lensAmount,
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getApi33(
        width: Float,
        height: Float,
        pad: Float,
        radius: Float,
        bevel: Float,
        refraction: Float,
        isDark: Boolean,
        lensCx: Float,
        lensCy: Float,
        lensHw: Float,
        lensHh: Float,
        lensRadius: Float,
        lensAmount: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val s = shader ?: RuntimeShader(LENS_AGSL).also { shader = it }
        val darkInt = if (isDark) 1 else 0
        val geomChanged = cachedW != width || cachedH != height || cachedPad != pad ||
            cachedRadius != radius || cachedBevel != bevel || cachedRefraction != refraction ||
            cachedDark != darkInt
        if (geomChanged) {
            cachedW = width; cachedH = height; cachedPad = pad
            cachedRadius = radius; cachedBevel = bevel; cachedRefraction = refraction
            cachedDark = darkInt
            s.setFloatUniform("origin", pad, pad)
            s.setFloatUniform("size", width, height)
            s.setFloatUniform("radius", radius)
            s.setFloatUniform("bevel", bevel)
            s.setFloatUniform("refraction", refraction)
            s.setFloatUniform("dark", darkInt.toFloat())
            restingEffect = null
            activeEffect = null
        }

        if (lensAmount <= 0.005f) {
            if (restingEffect == null) {
                s.setFloatUniform("lensRect", 0f, 0f, 1f, 1f)
                s.setFloatUniform("lensRadius", 1f)
                s.setFloatUniform("lensAmount", 0f)
                restingEffect = PlatformRenderEffect.createChainEffect(
                    PlatformRenderEffect.createRuntimeShaderEffect(s, "content"),
                    blurEffect!!,
                ).asComposeRenderEffect()
            }
            return restingEffect!!
        }

        if (activeEffect == null || lastLx != lensCx || lastLy != lensCy ||
            lastLhw != lensHw || lastLhh != lensHh || lastLr != lensRadius || lastLAmt != lensAmount
        ) {
            lastLx = lensCx; lastLy = lensCy; lastLhw = lensHw; lastLhh = lensHh
            lastLr = lensRadius; lastLAmt = lensAmount
            s.setFloatUniform("lensRect", lensCx, lensCy, lensHw, lensHh)
            s.setFloatUniform("lensRadius", lensRadius)
            s.setFloatUniform("lensAmount", lensAmount)
            activeEffect = PlatformRenderEffect.createChainEffect(
                PlatformRenderEffect.createRuntimeShaderEffect(s, "content"),
                blurEffect!!,
            ).asComposeRenderEffect()
        }
        return activeEffect!!
    }
}

/**
 * Fused iOS 26 Liquid Glass AGSL shader:
 * - Single-pass exact analytical rounded-rect SDF + normal (1 evaluation instead of 5 finite differences).
 * - Curved bezel Snell-like refraction + rim-localized RGB chromatic dispersion (1 fetch in flat core, 3 at rim).
 * - Live moving selection droplet optical magnification, bezel bending, Fresnel highlight, and cast shadow.
 * - Apple-style color vibrancy (saturation boost) + luminance-adaptive translucency + 3D internal Fresnel rim.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refraction;
uniform float dark;
uniform float4 lensRect;
uniform float lensRadius;
uniform float lensAmount;

float3 sdRoundRectGrad(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    float2 qMax = max(q, 0.0);
    float len = length(qMax);
    float sd = len + min(max(q.x, q.y), 0.0) - r;
    float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    float2 n = len > 0.0001
        ? s * (qMax / len)
        : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
    return float3(sd, n.x, n.y);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - origin - halfSize;

    float3 g = sdRoundRectGrad(p, halfSize, radius);
    float inside = -g.x;
    // Extend 2.5px past the silhouette so upscaled bilinear filtering never bleeds raw pixels into the clipped rim.
    if (inside < -2.5) return content.eval(coord);

    float2 n = g.yz;
    float clampedInside = max(inside, 0.0);

    // Subtle cylindrical body magnification + thick curved bezel Snell-like refraction.
    float2 magnification = size.x >= size.y ? float2(0.012, 0.055) : float2(0.055, 0.012);
    float2 shift = -p * magnification;
    float rimBend = 0.0;
    float rimU = 0.0;
    if (clampedInside < bevel) {
        rimU = 1.0 - clampedInside / max(bevel, 1.0);
        float s = rimU * 0.88;
        rimBend = (rimU / sqrt(max(1.0 - s * s, 0.1))) * 0.475;
        shift += -n * rimBend * refraction;
    }

    // Active selection droplet: uniformly skipped across the whole GPU wavefront when resting.
    float lensMix = 0.0;
    float lensRim = 0.0;
    float lensSpec = 0.0;
    float lensShadow = 0.0;
    if (lensAmount > 0.005) {
        float2 lp = coord - lensRect.xy;
        float3 lg = sdRoundRectGrad(lp, lensRect.zw, lensRadius);
        float lInside = -lg.x;
        float shadowSpan = max(lensRadius * 0.28, 2.5);
        if (lInside <= 0.0 && lInside > -shadowSpan) {
            float d = -lInside / shadowSpan;
            float falloff = (1.0 - d) * (1.0 - d);
            lensShadow = falloff * lensAmount * (dark > 0.5 ? 0.24 : 0.10);
        }
        lensMix = clamp((lInside + 0.6) / 1.2, 0.0, 1.0) * lensAmount;
        if (lensMix > 0.001) {
            float lBevel = min(lensRadius * 0.68, bevel * 0.95);
            float lu = clamp(1.0 - max(lInside, 0.0) / max(lBevel, 1.0), 0.0, 1.0);
            float ls = lu * 0.86;
            float lBend = (lu / sqrt(max(1.0 - ls * ls, 0.12))) * 0.51;
            float2 lShift = -lp * 0.085 - lg.yz * lBend * (refraction * 0.92);
            shift = mix(shift, shift * 0.35 + lShift, lensMix);
            rimBend = max(rimBend, lBend * lensMix);
            lensRim = lu * lu * lensMix;
            float2 lightDir = float2(-0.48, -0.88);
            float lDot1 = max(dot(lg.yz, lightDir), 0.0);
            float lDot2 = max(dot(lg.yz, -lightDir), 0.0);
            lensSpec = (lDot1 * lDot1 + lDot2 * lDot2 * 0.45) * lensRim;
        }
    }

    // Rim-localized prismatic chromatic dispersion (1 texture sample in flat center, 3 along curved glass rims).
    float dispStrength = max(rimBend, lensRim * 0.85);
    float3 col;
    if (dispStrength > 0.06) {
        float disp = 0.13 * clamp(dispStrength, 0.0, 1.0);
        float3 base = float3(content.eval(coord + shift).rgb);
        float rCh = float(content.eval(coord + shift * (1.0 + disp)).r);
        float bCh = float(content.eval(coord + shift * (1.0 - disp)).b);
        col = float3(rCh, base.g, bCh);
    } else {
        col = float3(content.eval(coord + shift).rgb);
    }

    // Color vibrancy boost + luminance-adaptive crystal translucency.
    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));
    float sat = dark > 0.5 ? 1.28 : 1.32;
    col = clamp(mix(float3(luma), col, sat), 0.0, 1.0);

    float vGrad = clamp(0.5 - p.y / max(size.y, 1.0), 0.0, 1.0);
    if (dark > 0.5) {
        float tintAlpha = mix(0.26, 0.46, luma) * (1.0 - 0.25 * lensMix);
        col = mix(col, float3(0.11, 0.118, 0.138), tintAlpha);
        col += float3(0.055 + 0.045 * vGrad) * (1.0 - luma * 0.65);
        col += float3(0.06) * lensMix;
    } else {
        float tintAlpha = mix(0.46, 0.22, luma) * (1.0 - 0.30 * lensMix);
        col = mix(col, float3(0.99, 0.99, 1.0), tintAlpha);
        col += float3(0.045 * vGrad);
        col += float3(0.055) * lensMix;
    }

    col *= (1.0 - lensShadow);

    // 3D directional Fresnel internal bevel glow + subtle light-mode lower thickness.
    float2 lightDir = float2(-0.48, -0.88);
    float rimPow = rimU * rimU;
    float spec1 = max(dot(n, lightDir), 0.0);
    float spec2 = max(dot(n, -lightDir), 0.0);
    float fresnel = rimPow * (spec1 * spec1 * 0.85 + spec2 * spec2 * 0.38 + 0.14);
    col += float3(fresnel * (dark > 0.5 ? 0.26 : 0.34));
    if (dark <= 0.5) {
        col -= float3(rimPow * max(n.y, 0.0) * 0.045);
    }
    col += float3(lensSpec * (dark > 0.5 ? 0.36 : 0.42));

    return half4(clamp(col, 0.0, 1.0), 1.0);
}
"""
