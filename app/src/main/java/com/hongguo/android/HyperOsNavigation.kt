package com.hongguo.android

import android.annotation.SuppressLint
import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlin.math.ceil

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

/** Space the capsule occupies above the bottom edge, including the system navigation bar. */
@Composable
internal fun floatingNavClearance(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + CapsuleGap + CapsuleHeight + 12.dp

/**
 * What sits behind the glass. The page records itself into [layer] once per frame and the capsule re-draws that
 * same layer blurred and refracted, so no second composition of the page is needed.
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

/** Marks the page content as the glass backdrop. Put it before the page's own background in the modifier chain. */
internal fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
        backdrop.version++
    }

/**
 * HyperOS / iOS 26 style floating capsule with a liquid-glass surface.
 * Android 13+: blur plus an AGSL lens that bends the content along the rim. Android 12: blur only.
 * Android 8–11 cannot blur what is behind a view, so the capsule falls back to a near-opaque frosted fill.
 */
@Composable
internal fun LiquidGlassNavigationBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    tabs: List<AppNavTab>,
    style: AppThemeStyle,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val palette = LocalHongguoPalette.current
    val selectedIndex = tabs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    val activeColor = if (style == AppThemeStyle.WARM) AccentInk else Ink
    // Darker than the classic bar's grey: labels sit on whatever colour scrolls underneath the glass.
    val idleColor = Ink.copy(alpha = .72f)
    val indicatorFraction by animateFloatAsState(selectedIndex.toFloat(),
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "glass_indicator")
    val glass = rememberGraphicsLayer()
    val shape = RoundedCornerShape(50)
    var capsuleOrigin by remember { mutableStateOf(Offset.Zero) }
    val tint = if (palette.isDark) Color(0xFF1E1F23) else Color.White
    val base = Canvas
    val effects = remember { GlassEffectCache() }

    Box(modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())
        .padding(start = CapsuleSideMargin, end = CapsuleSideMargin, bottom = CapsuleGap)
        .semantics { testTagsAsResourceId = true }, contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.widthIn(max = 440.dp).fillMaxWidth().height(CapsuleHeight)
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .28f))
            .onGloballyPositioned { capsuleOrigin = it.positionInRoot() }
            .drawBehind {
                if (backdrop.version < 0) return@drawBehind // read so page changes invalidate this draw
                val radius = size.height / 2
                val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
                val blurPx = 16.dp.toPx()
                val pad = ceil(blurPx * 2 + 24.dp.toPx())
                clipPath(outline) {
                    drawRect(base.copy(alpha = 1f))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val offset = capsuleOrigin - backdrop.origin
                        glass.record(IntSize((size.width + pad * 2).toInt(), (size.height + pad * 2).toInt())) {
                            translate(pad - offset.x, pad - offset.y) { drawLayer(backdrop.layer) }
                        }
                        glass.renderEffect = effects.get(size, pad, radius, blurPx, 22.dp.toPx(), 18.dp.toPx())
                        translate(-pad, -pad) { drawLayer(glass) }
                        drawRect(tint.copy(alpha = if (palette.isDark) .46f else .5f))
                    } else {
                        drawRect(tint.copy(alpha = .9f))
                    }
                }
                // Specular rim: bright on the upper-left, faint on the lower-right, like light catching a glass edge.
                val rim = 1.dp.toPx()
                drawRoundRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = if (palette.isDark) .38f else .85f),
                        .45f to Color.White.copy(alpha = .08f),
                        1f to Color.White.copy(alpha = if (palette.isDark) .2f else .5f),
                        start = Offset.Zero, end = Offset(size.width * .35f, size.height * 2.2f)),
                    topLeft = Offset(rim / 2, rim / 2), size = Size(size.width - rim, size.height - rim),
                    cornerRadius = CornerRadius(radius - rim / 2), style = Stroke(rim))
            }) {
            // Selection lens: a smaller pill of glass that slides between tabs.
            val indicator = if (palette.isDark) Color.White.copy(alpha = .14f) else Color.Black.copy(alpha = .065f)
            Box(Modifier.matchParentSize().padding(vertical = 5.dp).drawBehind {
                val itemWidth = size.width / tabs.size
                val inset = 5.dp.toPx()
                drawRoundRect(indicator, topLeft = Offset(indicatorFraction * itemWidth + inset, 0f),
                    size = Size(itemWidth - inset * 2, size.height), cornerRadius = CornerRadius(size.height / 2))
            })
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex
                    val color = if (isSelected) activeColor else idleColor
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            keyboard?.hide()
                            focus.clearFocus()
                            onSelect(if (item.tab == AppTab.FAVORITES) AppTab.HISTORY else item.tab)
                        }
                        .testTag("nav_${item.tab.name.lowercase(Locale.ROOT)}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(if (isSelected) item.selectedIcon else item.icon, contentDescription = null, tint = color,
                            modifier = Modifier.size(24.dp))
                        Text(item.label, color = color, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** Rebuilding a RuntimeShader every frame would allocate while scrolling; the effect only changes with size. */
private class GlassEffectCache {
    private var key: List<Float>? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    @RequiresApi(Build.VERSION_CODES.S)
    fun get(size: Size, pad: Float, radius: Float, blur: Float, bevel: Float, refraction: Float): androidx.compose.ui.graphics.RenderEffect {
        val k = listOf(size.width, size.height, pad, radius, blur, bevel, refraction)
        if (k != key || effect == null) { key = k; effect = glassEffect(size, pad, radius, blur, bevel, refraction) }
        return effect!!
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun glassEffect(size: Size, pad: Float, radius: Float, blur: Float, bevel: Float, refraction: Float) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lensEffect(size, pad, radius, blur, bevel, refraction)
    else PlatformRenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP).asComposeRenderEffect()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun lensEffect(size: Size, pad: Float, radius: Float, blur: Float, bevel: Float, refraction: Float) =
    RuntimeShader(LENS_AGSL).run {
        setFloatUniform("origin", pad, pad)
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("radius", radius)
        setFloatUniform("bevel", bevel)
        setFloatUniform("refraction", refraction)
        // Blur first, then bend the blurred image along the rim.
        PlatformRenderEffect.createChainEffect(
            PlatformRenderEffect.createRuntimeShaderEffect(this, "content"),
            PlatformRenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }

/**
 * Lens for a rounded rectangle: inside a rim of width `bevel` the sample point is pushed inwards along the
 * surface normal with a circular profile, so content near the edge looks magnified and bent like thick glass.
 * The red and blue channels are displaced slightly differently for a hint of dispersion.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refraction;

float sdRoundRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - origin - halfSize;
    float inside = -sdRoundRect(p, halfSize, radius);
    if (inside <= 0.0 || inside >= bevel) return content.eval(coord);
    float t = 1.0 - inside / bevel;
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float e = 0.75;
    float2 n = float2(
        sdRoundRect(p + float2(e, 0.0), halfSize, radius) - sdRoundRect(p - float2(e, 0.0), halfSize, radius),
        sdRoundRect(p + float2(0.0, e), halfSize, radius) - sdRoundRect(p - float2(0.0, e), halfSize, radius));
    n = n / max(length(n), 0.0001);
    float2 shift = -n * bend * refraction;
    half4 base = content.eval(coord + shift);
    half r = content.eval(coord + shift * 1.12).r;
    half b = content.eval(coord + shift * 0.88).b;
    return half4(r, base.g, b, base.a);
}
"""
