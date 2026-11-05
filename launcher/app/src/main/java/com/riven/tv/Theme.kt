package com.riven.tv

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.random.Random

object Ink {
    val base = Color(0xFF08080A)
    val ink = Color(0xFFF5F5F7)
    val ink2 = ink.copy(alpha = 0.68f)
    val ink3 = ink.copy(alpha = 0.40f)
    val line = Color.White.copy(alpha = 0.12f)
    val glass = Color.White.copy(alpha = 0.075f)
    val glassHi = Color.White.copy(alpha = 0.16f)
    val onInk = Color(0xFF0A0A0A)
    val danger = Color(0xFFFF8A8A)
}

val RivenEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Type pairings from Google Fonts (all SIL Open Font Licence): a display face for the clock,
 * titles and big numbers, and a text face for everything else.
 */
enum class Typeface(override val label: String, val display: Int, val text: Int, val displayStatic: Boolean = false) : Labeled {
    Signature("Bricolage Grotesque + Geist", R.font.font_bricolage, R.font.font_geist),
    Studio("Geist", R.font.font_geist, R.font.font_geist),
    Editorial("Instrument Serif + Geist", R.font.font_instrumentserif, R.font.font_geist, displayStatic = true),
    Soft("Fraunces + Outfit", R.font.font_fraunces, R.font.font_outfit),
    Future("Unbounded + Space Grotesk", R.font.font_unbounded, R.font.font_spacegrotesk),
    Gallery("Syne + Manrope", R.font.font_syne, R.font.font_manrope),
    Round("Outfit", R.font.font_outfit, R.font.font_outfit),
}

@OptIn(ExperimentalTextApi::class)
object Fonts {
    private val cache = HashMap<Pair<Int, Boolean>, FontFamily>()
    fun family(res: Int, static: Boolean = false): FontFamily = cache.getOrPut(res to static) {
        if (static) FontFamily(Font(res))
        else FontFamily((100..900 step 100).map { w ->
            Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        })
    }
    fun display(t: Typeface) = family(t.display, t.displayStatic)
    fun text(t: Typeface) = family(t.text)
}

fun riven(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    tracking: TextUnit = 0.em,
    color: Color = Ink.ink,
) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    // Big type (clock, titles) uses the pairing's display face; the rest uses its text face.
    fontFamily = Store.settings.typeface.let { if (size.value >= 20f) Fonts.display(it) else Fonts.text(it) },
    // Serif display faces need air: never pack them tighter than -0.015em.
    letterSpacing = if (size.value >= 20f && Store.settings.typeface.displayStatic && tracking.value < -0.015f) (-0.015).em else tracking,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * Riven is laid out on a fixed 960×540 dp canvas (half of 1920×1080) and scaled to the
 * panel, so it looks identical on 1080p and 4K.
 */
@Composable
fun RivenCanvas(content: @Composable BoxScope.() -> Unit) {
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val scale = config.screenWidthDp / 960f
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, 1f)) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/** Frosted panel: translucent fill, light-catching top edge, soft outline. */
fun Modifier.glass(shape: Shape, fill: Color = Ink.glass): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(
        BorderStroke(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.06f)))),
        shape,
    )

/**
 * Living background: soft light fields tinted by the focused app (or a fixed theme).
 * It is painted once per colour change into a tiny bitmap on a background thread, and the GPU
 * only has to stretch that one texture each frame — the big win on older TV chips.
 */
@Composable
fun Backdrop(focusColor: Color, modifier: Modifier = Modifier) {
    val s = Store.settings
    val (t1, t2) = s.backdrop.colors ?: (focusColor to lerp(focusColor, Color.Black, 0.65f))
    val night = s.backdrop == Backdrop.Night
    val fx = s.motion
    val bmp by produceState<ImageBitmap?>(null, t1, t2, night) {
        // Small debounce: fast D-pad scrolling shouldn't render a bitmap per card passed.
        delay(if (value == null) 0 else 90)
        value = withContext(Dispatchers.Default) { renderBackdrop(t1.toArgb(), t2.toArgb(), night) }
    }
    val drift = if (fx == Motion.Full) {
        rememberInfiniteTransition(label = "drift")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    } else null

    Box(modifier.fillMaxSize()) {
        if (bmp == null) Box(Modifier.fillMaxSize().background(Ink.base))
        Crossfade(
            bmp,
            animationSpec = if (fx == Motion.Lightest) snap() else tween(if (fx == Motion.Full) 900 else 450, easing = RivenEase),
            label = "bg",
        ) { b ->
            if (b != null) Image(
                b, null,
                Modifier.fillMaxSize().graphicsLayer {
                    // Full effects: the light drifts and breathes slowly (a transform, not a repaint).
                    val d = drift?.value ?: 0f
                    val k = 1.06f + 0.05f * d
                    scaleX = k; scaleY = k
                    translationX = size.width * (0.02f - 0.04f * d)
                },
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Low,
            )
        }
        if (night) NightSky(twinkle = fx == Motion.Full)
        if (fx == Motion.Full) {
            val grain = rememberGrain()
            Box(Modifier.fillMaxSize().drawBehind { drawRect(grain, alpha = 0.4f) })
        }
    }
}

/** 320×180 glow, drawn with the platform canvas off the main thread. */
private fun renderBackdrop(c1: Int, c2: Int, night: Boolean): ImageBitmap {
    val w = 320; val h = 180
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    cv.drawColor(Ink.base.toArgb())
    fun glow(color: Int, alpha: Float, cx: Float, cy: Float, r: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val inner = (color and 0x00FFFFFF) or (a shl 24)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(inner, inner and 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
    glow(c1, 0.55f, w * 0.74f, h * 0.12f, w * 0.62f)
    glow(c2, 0.50f, w * 0.12f, h * 1.05f, w * 0.55f)
    glow(c1, 0.14f, w * 0.45f, h * 0.35f, w * 0.40f)
    // dark floor so text and cards always read
    val floor = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x26080808, 0x59080808, 0xEB080808.toInt()), floatArrayOf(0f, 0.45f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), floor)
    if (night) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(w * 0.84f, h * 0.38f, w * 0.14f, intArrayOf(0x30DDE6FF, 0x00DDE6FF), null, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
    return bmp.asImageBitmap()
}

@Composable
private fun NightSky(twinkle: Boolean) {
    val stars = remember {
        val rnd = Random(7)
        List(120) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.75f, 0.4f + rnd.nextFloat() * 1.1f, rnd.nextFloat() * 6.28f) }
    }
    val tw = if (twinkle) rememberInfiniteTransition(label = "tw")
        .animateFloat(0f, 6.28f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "t") else null
    Canvas(Modifier.fillMaxSize()) {
        val ph = tw?.value ?: 0f
        for (st in stars) {
            val a = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(st[3] + ph))
            drawCircle(Color.White.copy(alpha = a * 0.8f), st[2] * size.width / 960f, Offset(st[0] * size.width, st[1] * size.height))
        }
        // Moon sits in the empty space right of the title, clear of buttons and text.
        val m = Offset(size.width * 0.84f, size.height * 0.38f); val mr = size.width * 0.028f
        // True crescent: cut the shadow out instead of painting a dark disc over the sky.
        val bite = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(m.x + mr * 0.42f, m.y - mr * 0.18f), mr * 0.92f))
        }
        clipPath(bite, androidx.compose.ui.graphics.ClipOp.Difference) { drawCircle(Color(0xFFF2F4FF), mr, m) }
    }
}

fun DrawScope.blob(c: Color, center: Offset, radius: Float) =
    drawRect(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = center, radius = radius))

@Composable
fun rememberGrain(): ShaderBrush = remember {
    val n = 128
    val px = IntArray(n * n) {
        val v = Random.nextInt(256)
        (Random.nextInt(36) shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}
