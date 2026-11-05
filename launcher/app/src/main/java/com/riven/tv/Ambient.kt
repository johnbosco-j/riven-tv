package com.riven.tv

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

fun clockText(ctx: Context, now: LocalDateTime): String {
    val h24 = when (Store.settings.clock) {
        ClockFormat.H24 -> true
        ClockFormat.H12 -> false
        ClockFormat.System -> DateFormat.is24HourFormat(ctx)
    }
    return now.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm"))
}

fun dateText(now: LocalDateTime): String = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))

@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000L) }
    }
    return now
}

/**
 * Riven Ambient — used both by the in-launcher idle screensaver and the system DreamService.
 *  Aurora:   slow drifting light fields + clock
 *  Clock:    pure black + clock (OLED/burn-in friendly)
 *  Showcase: your home apps' artwork, one at a time, slowly zooming
 * The clock wanders a little every 20 s so nothing stays burnt into the panel.
 */
@Composable
fun Ambient(style: SaverStyle, showcase: List<AppEntry>) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val inf = rememberInfiniteTransition(label = "aurora")
    val a = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(34000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(41000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(47000, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    val wander = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            wander.animateTo(Offset(Random.nextFloat() * 60f, -Random.nextFloat() * 40f), tween(6000))
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).drawBehind {
            if (style == SaverStyle.Clock) return@drawBehind
            val k = if (style == SaverStyle.Showcase) 0.4f else 0.75f
            val w = size.width; val h = size.height
            blob(Color(0xFF5B4BFF).copy(alpha = k), Offset(w * (0.12f + 0.20f * a.value), h * (0.05f + 0.20f * a.value)), w * 0.52f)
            blob(Color(0xFF13C4A3).copy(alpha = k), Offset(w * (0.88f - 0.22f * b.value), h * (0.45f - 0.15f * b.value)), w * 0.50f)
            blob(Color(0xFFFF4D8D).copy(alpha = k), Offset(w * (0.50f + 0.20f * c.value), h * (1.05f - 0.18f * c.value)), w * 0.45f)
        }
    ) {
        if (style == SaverStyle.Showcase && showcase.isNotEmpty()) {
            var idx by remember { mutableIntStateOf(0) }
            LaunchedEffect(showcase.size) { while (true) { delay(9000); idx = (idx + 1) % showcase.size } }
            Crossfade(showcase[idx % showcase.size], animationSpec = tween(1600), label = "show") { app ->
                val zoom = remember { Animatable(0.96f) }
                LaunchedEffect(app.pkg) { zoom.animateTo(1.05f, tween(9000, easing = LinearEasing)) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.offset(y = (-34).dp).width(450.dp).aspectRatio(16f / 9f)
                            .graphicsLayer {
                                scaleX = zoom.value; scaleY = zoom.value
                                shape = RoundedCornerShape(20.dp); clip = true
                                shadowElevation = 40.dp.toPx()
                            }
                    ) { AppArt(app) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 58.dp)
                .offset { IntOffset(wander.value.x.dp.roundToPx(), wander.value.y.dp.roundToPx()) }
        ) {
            BasicText(clockText(ctx, now), style = riven(130.sp, FontWeight.ExtraLight, (-0.06).em, Color.White))
            Spacer(Modifier.height(12.dp))
            BasicText(dateText(now), style = riven(15.sp, FontWeight.Light, 0.em, Color.White.copy(alpha = 0.75f)))
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 60.dp, bottom = 64.dp), horizontalAlignment = Alignment.End) {
            BasicText("RIVEN", style = riven(8.sp, tracking = 0.3.em, color = Color.White.copy(alpha = 0.55f)))
            Spacer(Modifier.height(5.dp))
            BasicText(
                "MADE BY ${Catalog.AUTHOR.uppercase()}",
                style = riven(6.5.sp, FontWeight.Medium, 0.2.em, Color.White.copy(alpha = 0.35f)),
            )
        }
    }
}
