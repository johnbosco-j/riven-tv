package com.riven.tv

import android.view.KeyEvent as AKey
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/* ───────────── input ───────────── */

/**
 * Click for a TV remote: OK = click, hold OK = long click. Only fires on a key-up whose
 * key-down it saw, so the release after a long press never "clicks" whatever got focus next.
 */
@Composable
fun Modifier.tvClickable(
    interaction: MutableInteractionSource,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    return this
        .onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != AKey.KEYCODE_DPAD_CENTER && code != AKey.KEYCODE_ENTER && code != AKey.KEYCODE_NUMPAD_ENTER) {
                return@onPreviewKeyEvent false
            }
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) { down = true; longFired = false }
                    else if (down && !longFired && onLongClick != null) { longFired = true; onLongClick() }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (down && !longFired) onClick()
                    down = false; longFired = false
                    true
                }
                else -> false
            }
        }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/* ───────────── cards ───────────── */

private val CardShape = RoundedCornerShape(11.dp)

/** Focus animation shared by all cards: springy lift, ring, and a one-shot light sheen. */
@Composable
private fun rememberFocus(focused: Boolean, fx: Motion): Pair<State<Float>, Animatable<Float, AnimationVector1D>> {
    val t = animateFloatAsState(
        if (focused) 1f else 0f,
        when (fx) {
            Motion.Full -> spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
            Motion.Balanced -> spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            Motion.Lightest -> tween(110)
        },
        label = "lift",
    )
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused && fx == Motion.Full) { sheen.snapTo(0f); sheen.animateTo(1f, tween(1000, easing = RivenEase)) }
    }
    return t to sheen
}

/**
 * The card's lift, shadow, gloss, sheen and ring. The animated values are read only while
 * drawing, so a focus animation repaints the card without recomposing or re-measuring it.
 */
private fun Modifier.cardFrame(t: State<Float>, sheen: State<Float>, ring: Boolean = true, tilt: State<Float>? = null): Modifier = this
    .fillMaxWidth()
    .aspectRatio(16f / 9f)
    .graphicsLayer {
        val v = t.value
        val k = 1f + 0.12f * v
        scaleX = k; scaleY = k
        translationY = -5.dp.toPx() * v
        rotationZ = tilt?.value ?: 0f
        // Shadows are costly on older TV GPUs: only the focused card gets one, and none in Lightest.
        shadowElevation = if (Store.settings.motion == Motion.Lightest) 0f else 18.dp.toPx() * v
        shape = CardShape
        clip = true
    }
    .drawWithContent {
        drawContent()
        val v = t.value
        // glossy top edge
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.Transparent))
        // sheen sweeping across on focus (Full effects)
        val sh = sheen.value
        if (sh in 0.001f..0.999f) {
            val x = size.width * (-0.6f + 2.2f * sh)
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.34f), 1f to Color.Transparent,
                    start = Offset(x - size.width * 0.25f, 0f), end = Offset(x + size.width * 0.25f, size.height),
                )
            )
        }
        if (ring) {
            val w = maxOf(0.5.dp.toPx(), 1.75.dp.toPx() * v)
            drawRoundRect(
                lerp(Color.White.copy(alpha = 0.10f), Color.White, v),
                topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx() - w / 2), style = Stroke(w),
            )
        }
    }

@Composable
fun AppCard(
    app: AppEntry,
    width: Dp,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
    moving: Boolean = false,
    ring: Boolean = true,
    onBounds: ((Rect) -> Unit)? = null,
    onBlur: () -> Unit = {},
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val s = Store.settings
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(focused) {
        if (focused) { onFocus(); coords[0]?.takeIf { it.isAttached }?.let { onBounds?.invoke(it.boundsInRoot()) } } else onBlur()
    }
    val (t, sheen) = rememberFocus(focused, s.motion)
    val mono = s.icons == IconStyle.Mono
    val tilt = if (moving) {
        rememberInfiniteTransition(label = "wobble")
            .animateFloat(-0.9f, 0.9f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "w")
    } else null
    val alwaysNames = s.names == NameMode.Always

    val sized = if (width != Dp.Unspecified) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(modifier.then(sized).tvClickable(interaction, onLongClick, onClick)) {
        Box(
            Modifier
                .onGloballyPositioned { c -> coords[0] = c; if (focused) onBounds?.invoke(c.boundsInRoot()) }
                .cardFrame(t, sheen.asState(), ring, tilt)
        ) {
            AppArt(app, mono && !focused)
            if (isNew) {
                BasicText(
                    "NEW",
                    style = riven(5.5.sp, FontWeight.Bold, 0.14.em, Ink.onInk),
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .background(Ink.ink, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (moving && width != Dp.Unspecified) MoveArrows(width)
        Spacer(Modifier.height(10.dp))
        BasicText(
            app.label,
            style = riven(9.5.sp, FontWeight.Medium, 0.em, Ink.ink),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer {
                val v = t.value
                alpha = if (alwaysNames) 0.68f + 0.32f * v else v
                translationY = 3.dp.toPx() * v
            },
        )
    }
}

/** The app's own art, in full colour: banner if it has one, otherwise icon on its colour. */
@Composable
fun AppArt(app: AppEntry, mono: Boolean = false) {
    val filter = if (mono) Grayscale else null
    if (app.banner != null) {
        Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = filter)
    } else {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(lerp(app.color, Color.White, 0.12f), lerp(app.color, Color.Black, 0.55f)),
                    center = Offset(0f, 0f), radius = 600f,
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            app.icon?.let { Image(it, null, Modifier.fillMaxHeight(0.52f).aspectRatio(1f), colorFilter = filter) }
        }
    }
}

@Composable
private fun MoveArrows(width: Dp) {
    Box(Modifier.width(width).height(0.dp)) {
        val y = (-width * 9f / 16f / 2f) - 8.dp
        BasicText("‹", style = riven(18.sp), modifier = Modifier.offset(x = (-14).dp, y = y))
        BasicText("›", style = riven(18.sp), modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = y))
    }
}

/** A Play Store download in progress, waiting in line on the home row. */
@Composable
fun InstallCard(item: InstallItem, width: Dp, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onBlur: () -> Unit = {}, onBounds: ((Rect) -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(focused) {
        if (focused) { onFocus(); coords[0]?.takeIf { it.isAttached }?.let { onBounds?.invoke(it.boundsInRoot()) } } else onBlur()
    }
    val (tState, sheen) = rememberFocus(focused, Store.settings.motion)
    val t = tState.value
    val p by animateFloatAsState(item.progress, tween(500), label = "p")

    val sized = if (width != Dp.Unspecified) Modifier.width(width) else Modifier.fillMaxWidth()
    Column(modifier.then(sized).tvClickable(interaction) {}) {
        Box(
            Modifier.onGloballyPositioned { c -> coords[0] = c; if (focused) onBounds?.invoke(c.boundsInRoot()) }
                .cardFrame(tState, sheen.asState(), onBounds == null).background(Ink.glass),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                if (p > 0.001f) "INSTALLING" else "WAITING",
                style = riven(5.5.sp, FontWeight.Medium, 0.22.em, Ink.ink2),
                modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
            )
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                item.icon?.let { Image(it, null, Modifier.size(20.dp), alpha = 0.85f) }
                Canvas(Modifier.fillMaxSize()) {
                    val w = 2.5.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.15f), style = Stroke(w))
                    drawArc(Color.White, -90f, 360f * p, false, style = Stroke(w, cap = StrokeCap.Round))
                }
            }
            BasicText(
                "${(p * 100).toInt()}%",
                style = riven(7.sp, FontWeight.SemiBold, 0.em, Ink.ink),
                modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp),
            )
        }
        Spacer(Modifier.height(9.dp + 3.dp * t))
        BasicText("${item.label} · Play Store", style = riven(9.5.sp, FontWeight.Medium, 0.em, Ink.ink2),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A starter app (from [Catalog]) that isn't installed yet. OK → Play Store. */
@Composable
fun GetCard(spec: TileSpec, width: Dp, modifier: Modifier = Modifier, onFocus: () -> Unit, onBlur: () -> Unit = {}, onBounds: ((Rect) -> Unit)? = null, onLongClick: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(focused) {
        if (focused) { onFocus(); coords[0]?.takeIf { it.isAttached }?.let { onBounds?.invoke(it.boundsInRoot()) } } else onBlur()
    }
    val (tState, sheen) = rememberFocus(focused, Store.settings.motion)
    val t = tState.value
    val sized = if (width != Dp.Unspecified) Modifier.width(width) else Modifier.fillMaxWidth()

    Column(modifier.then(sized).tvClickable(interaction, onLongClick, onClick)) {
        Box(
            Modifier.onGloballyPositioned { c -> coords[0] = c; if (focused) onBounds?.invoke(c.boundsInRoot()) }
                .cardFrame(tState, sheen.asState(), ring = false),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    lerp(Color.White.copy(alpha = 0.3f), Color.White, t),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("+", style = riven(20.sp, FontWeight.Light))
                Spacer(Modifier.height(4.dp))
                BasicText(spec.title, style = riven(9.sp, FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(9.dp + 3.dp * t))
        BasicText("Get from Play Store", style = riven(9.5.sp, FontWeight.Medium, 0.em, Ink.ink3))
    }
}

/* ───────────── small pieces ───────────── */

@Composable
fun Pill(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(260, easing = RivenEase), label = "pill")
    val bg = if (focused) Ink.ink else if (selected) Ink.glassHi else Color.Transparent
    Box(
        modifier
            .graphicsLayer { val s = 1f + 0.06f * t; scaleX = s; scaleY = s }
            .background(bg, CircleShape)
            .tvClickable(interaction, null, onClick)
            .padding(horizontal = 15.dp, vertical = 7.dp)
    ) {
        BasicText(
            label,
            style = riven(9.5.sp, FontWeight.Medium, 0.em, if (focused) Ink.onInk else if (selected) Ink.ink else Ink.ink2),
        )
    }
}

@Composable
fun WifiGlyph(online: Boolean, modifier: Modifier = Modifier) {
    val c = if (online) Ink.ink2 else Ink.ink3
    Canvas(modifier.size(13.dp)) {
        val w = 1.dp.toPx(); val cx = size.width / 2; val by = size.height * 0.86f
        for ((i, r) in listOf(0.92f, 0.62f, 0.32f).withIndex()) {
            val rad = size.width * r / 2 + size.width * 0.12f * (2 - i)
            drawArc(c, 225f, 90f, false, topLeft = Offset(cx - rad, by - rad), size = androidx.compose.ui.geometry.Size(rad * 2, rad * 2),
                style = Stroke(w, cap = StrokeCap.Round))
        }
        drawCircle(c, radius = w * 1.1f, center = Offset(cx, by))
        if (!online) drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), w)
    }
}

@Composable
fun Monogram(size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).glass(CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.3f).rotate(45f).border(1.dp, Ink.ink))
    }
}

@Composable
fun Credit(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.5.dp).rotate(45f).border(0.75.dp, Ink.ink2))
        Spacer(Modifier.width(6.dp))
        BasicText("${Catalog.STUDIO} · made by ", style = riven(7.5.sp, tracking = 0.06.em, color = Ink.ink3))
        BasicText(Catalog.AUTHOR, style = riven(7.5.sp, FontWeight.Medium, 0.06.em, Ink.ink2))
    }
}

@Composable
fun Hint(keys: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            keys, style = riven(6.5.sp, color = Ink.ink2),
            modifier = Modifier.border(BorderStroke(0.5.dp, Ink.line), RoundedCornerShape(3.5.dp)).padding(horizontal = 4.5.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(4.dp))
        BasicText(label, style = riven(7.5.sp, tracking = 0.06.em, color = Ink.ink3))
        Spacer(Modifier.width(10.dp))
    }
}


/** Frosted pill with an icon, for Inputs / Wi-Fi / Settings. */
@Composable
fun FrostButton(label: String, icon: @Composable (Color) -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(260, easing = RivenEase), label = "frost")
    val fg = lerp(Ink.ink, Ink.onInk, t)
    Row(
        modifier
            .graphicsLayer { val k = 1f + 0.07f * t; scaleX = k; scaleY = k; shadowElevation = 12.dp.toPx() * t; shape = CircleShape }
            .glass(CircleShape, lerp(Ink.glass, Ink.ink, t))
            .tvClickable(interaction, null, onClick)
            .padding(start = 10.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(fg)
        Spacer(Modifier.width(6.dp))
        BasicText(label, style = riven(9.sp, FontWeight.Medium, 0.em, fg))
    }
}

@Composable
fun InputsGlyph(c: Color) = Canvas(Modifier.size(12.dp)) {
    val w = 1.1.dp.toPx()
    drawRoundRect(c, topLeft = Offset(size.width * 0.05f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.58f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = Stroke(w))
    drawLine(c, Offset(size.width * 0.3f, size.height * 0.92f), Offset(size.width * 0.7f, size.height * 0.92f), w, StrokeCap.Round)
}

@Composable
fun GearGlyph(c: Color) = Canvas(Modifier.size(12.dp)) {
    val w = 1.1.dp.toPx(); val ctr = Offset(size.width / 2, size.height / 2); val r = size.width * 0.22f
    drawCircle(c, r, ctr, style = Stroke(w))
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0); val cs = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        drawLine(c, Offset(ctr.x + cs * r * 1.55f, ctr.y + sn * r * 1.55f), Offset(ctr.x + cs * r * 2.1f, ctr.y + sn * r * 2.1f), w * 1.3f, StrokeCap.Round)
    }
}

@Composable
fun WifiIcon(c: Color) = Canvas(Modifier.size(12.dp)) {
    val w = 1.1.dp.toPx(); val cx = size.width / 2; val by = size.height * 0.85f
    for (k in listOf(0.95f, 0.62f, 0.3f)) {
        val rad = size.width * k * 0.55f
        drawArc(c, 225f, 90f, false, topLeft = Offset(cx - rad, by - rad), size = androidx.compose.ui.geometry.Size(rad * 2, rad * 2), style = Stroke(w, cap = StrokeCap.Round))
    }
    drawCircle(c, w, Offset(cx, by))
}

@Composable
fun HomeGlyph(c: Color) = Canvas(Modifier.size(12.dp)) {
    val w = 1.1.dp.toPx(); val sz = size.width
    val roof = androidx.compose.ui.graphics.Path().apply {
        moveTo(sz * 0.12f, sz * 0.5f); lineTo(sz * 0.5f, sz * 0.14f); lineTo(sz * 0.88f, sz * 0.5f)
        moveTo(sz * 0.24f, sz * 0.4f); lineTo(sz * 0.24f, sz * 0.88f); lineTo(sz * 0.76f, sz * 0.88f); lineTo(sz * 0.76f, sz * 0.4f)
    }
    drawPath(roof, c, style = Stroke(w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

@Composable
fun EthernetGlyph(c: Color) = Canvas(Modifier.size(12.dp)) {
    val w = 1.1.dp.toPx(); val z = size.width
    drawRoundRect(c, topLeft = Offset(z * 0.18f, z * 0.22f), size = androidx.compose.ui.geometry.Size(z * 0.64f, z * 0.46f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = Stroke(w))
    for (x in listOf(0.36f, 0.5f, 0.64f)) drawLine(c, Offset(z * x, z * 0.34f), Offset(z * x, z * 0.46f), w, StrokeCap.Round)
    drawLine(c, Offset(z * 0.5f, z * 0.68f), Offset(z * 0.5f, z * 0.88f), w, StrokeCap.Round)
}
