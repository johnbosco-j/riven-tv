package com.riven.tv

import android.view.KeyEvent as AKey
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TVs ask Compose to "pivot" lists (park the focused item ~30% down the screen). On Home that
 * pushed the clock off the top, so Home scrolls only as much as needed to keep a tile in view.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object GentleScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pad = containerSize * 0.10f
        val end = offset + size
        return when {
            size > containerSize - 2 * pad -> offset - pad
            offset < pad -> offset - pad
            end > containerSize - pad -> end - (containerSize - pad)
            else -> 0f
        }
    }
}

sealed interface RowItem {
    val key: String
    data class App(val app: AppEntry) : RowItem { override val key = "a:" + app.pkg }
    data class Installing(val item: InstallItem) : RowItem { override val key = "i:" + item.id }
    data class Get(val spec: TileSpec) : RowItem { override val key = "g:" + spec.title }
}

/**
 * Home, in the style of a modern TV home screen:
 *  - greeting, thin clock and date, with frosted Inputs / Wi-Fi / Settings buttons,
 *  - a top shelf naming the focused app over a glow in its colour,
 *  - a frosted dock band with your first apps,
 *  - the rest of your apps in a grid that slides up when you press ▼,
 *  - one floating glass highlight that springs from tile to tile.
 */
@Composable
fun HomeScreen(
    repo: AppRepository,
    focusSignal: Int,
    grabOnEnter: Boolean,
    moving: String?,
    onMoveDone: () -> Unit,
    onColor: (Color) -> Unit,
    onOptions: (RowItem) -> Unit,
    onFocusedItem: (RowItem?) -> Unit,
    onInputs: () -> Unit,
    onSetHome: () -> Unit,
    onScrolled: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val s = Store.settings
    val apps = repo.apps
    val items: List<RowItem> = remember(apps, repo.installs, Store.home, Store.dismissed) {
        val byPkg = apps.associateBy { it.pkg }
        Store.home.mapNotNull { byPkg[it] }.map { RowItem.App(it) } +
            repo.installs.map { RowItem.Installing(it) } +
            Catalog.tiles.filter { Apps.resolve(it, apps) == null && it.title !in Store.dismissed && it.packages.isNotEmpty() }
                .map { RowItem.Get(it) }
    }
    val dockCount = s.dock.count.coerceAtMost(items.size)
    val dock = items.take(dockCount)
    val cols = s.size.columns
    val rows = items.drop(dockCount).chunked(cols)

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var tileKey by remember { mutableStateOf<String?>(null) }   // non-null while a tile has focus
    val focused = items.firstOrNull { it.key == focusedKey } ?: items.firstOrNull()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── floating glass highlight ──
    val hl = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var hlReady by remember { mutableStateOf(false) }
    val hlAlpha = animateFloatAsState(
        if (tileKey != null && hlReady) 1f else 0f,
        tween(if (s.motion == Motion.Lightest) 80 else 200), label = "hlA",
    )
    val lift = with(density) { 5.dp.toPx() }
    // Per-tile callbacks are created once and reused, so moving focus doesn't rebuild every card.
    val boundCbs = remember { HashMap<String, (Rect) -> Unit>() }
    val focusCbs = remember { HashMap<String, () -> Unit>() }
    val blurCbs = remember { HashMap<String, () -> Unit>() }
    fun boundsFor(key: String): (Rect) -> Unit = boundCbs.getOrPut(key) {
        { r ->
            if (key == tileKey || key == focusedKey) {
                val target = Rect(
                    r.center.x - r.width * 0.56f, r.center.y - lift - r.height * 0.56f,
                    r.center.x + r.width * 0.56f, r.center.y - lift + r.height * 0.56f,
                )
                val spec: AnimationSpec<Rect> = when (Store.settings.motion) {
                    Motion.Full -> spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                    Motion.Balanced -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
                    Motion.Lightest -> tween(90)
                }
                scope.launch { if (!hlReady) { hl.snapTo(target); hlReady = true } else hl.animateTo(target, spec) }
            }
        }
    }
    fun focusFor(key: String) = focusCbs.getOrPut(key) { { focusedKey = key; tileKey = key } }
    fun blurFor(key: String) = blurCbs.getOrPut(key) { { if (tileKey == key) tileKey = null } }

    // While moving, keep focus (and the highlight) on the tile being carried.
    LaunchedEffect(Store.home, moving) {
        val m = moving ?: return@LaunchedEffect
        val k = "a:$m"
        focusedKey = k
        val idx = items.indexOfFirst { it.key == k }
        if (idx >= dockCount) list.animateScrollToItem(2 + (idx - dockCount) / cols)
        withFrameNanos { }
        runCatching { req(k).requestFocus() }
    }

    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 120 } }
    LaunchedEffect(scrolled) { onScrolled(scrolled) }

    // Focus the remembered tile (or the first) when asked.
    val enteredWith = remember { focusSignal }
    LaunchedEffect(focusSignal, items.isNotEmpty()) {
        if (items.isEmpty()) return@LaunchedEffect
        if (focusSignal == enteredWith && !grabOnEnter) return@LaunchedEffect   // opened from the tab bar
        val idx = items.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
        val listIndex = if (idx < dockCount) 0 else 2 + (idx - dockCount) / cols
        if (list.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) list.scrollToItem(listIndex)
        withFrameNanos { }
        runCatching { req(items[idx].key).requestFocus() }
    }
    LaunchedEffect(focused, tileKey) { onFocusedItem(if (tileKey != null) focused else null) }
    // Back in the dock → show the whole stage again (clock, greeting, title).
    LaunchedEffect(focusedKey) {
        if (dock.any { it.key == focusedKey } && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)) {
            list.animateScrollToItem(0)
        }
    }
    LaunchedEffect(focused) {
        when (focused) {
            is RowItem.App -> onColor(focused.app.color)
            is RowItem.Installing -> onColor(Color(0xFF7A7A85))
            is RowItem.Get -> onColor(Color(0xFF5B6BFF))
            null -> {}
        }
    }

    @Composable
    fun Tile(item: RowItem, width: Dp, modifier: Modifier) {
        val mod = modifier.focusRequester(req(item.key))
        val onFocus = focusFor(item.key)
        val onBlur = blurFor(item.key)
        val bounds = boundsFor(item.key)
        when (item) {
            is RowItem.App -> AppCard(
                app = item.app, width = width, modifier = mod,
                isNew = item.app.pkg in Store.fresh,
                moving = moving == item.app.pkg,
                ring = false, onBounds = bounds, onBlur = onBlur, onFocus = onFocus,
                onLongClick = { onOptions(item) },
                onClick = { Apps.open(ctx, item.app.pkg) },
            )
            is RowItem.Installing -> InstallCard(item.item, width, mod, onFocus, onBlur, bounds)
            is RowItem.Get -> GetCard(
                item.spec, width, mod, onFocus, onBlur, bounds,
                onLongClick = { onOptions(item) },
                onClick = { Apps.openStore(ctx, item.spec.packages.first()) },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides GentleScroll) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = 70.dp),
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                val m = moving ?: return@onPreviewKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                val handled = code in setOf(
                    AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_DPAD_RIGHT, AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_DPAD_DOWN,
                    AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_BACK,
                )
                if (handled && e.type == KeyEventType.KeyDown) {
                    when (code) {
                        AKey.KEYCODE_DPAD_LEFT -> Store.move(m, -1)
                        AKey.KEYCODE_DPAD_RIGHT -> Store.move(m, +1)
                        AKey.KEYCODE_DPAD_UP -> Store.move(m, -cols)
                        AKey.KEYCODE_DPAD_DOWN -> Store.move(m, +cols)
                        else -> onMoveDone()
                    }
                }
                handled
            },
        ) {
            // ── stage: greeting, clock, shelf, dock ──
            item(key = "stage") {
                Box(Modifier.fillMaxWidth().height(462.dp)) {
                    Header(onInputs, onSetHome)
                    Shelf(focused, moving, Modifier.padding(start = 48.dp, top = 168.dp))
                    Row(
                        Modifier
                            .padding(start = 40.dp, end = 40.dp, top = 286.dp)
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(24.dp), Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        dock.forEach { key(it.key) { Tile(it, Dp.Unspecified, Modifier.weight(1f)) } }
                        repeat(s.dock.count - dock.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // ── the rest of the apps ──
            if (rows.isNotEmpty() || repo.installs.isNotEmpty()) item(key = "more") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(if (rows.isEmpty()) "" else "More apps", style = riven(11.sp, FontWeight.SemiBold))
                    val installing = repo.installs.size
                    BasicText(
                        "%02d APPS".format(Store.home.size) + if (installing > 0) " · $installing INSTALLING" else "",
                        style = riven(7.5.sp, tracking = 0.2.em, color = Ink.ink3),
                    )
                }
            }
            rows.forEachIndexed { r, row ->
                item(key = "row$r") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        row.forEach { key(it.key) { Tile(it, Dp.Unspecified, Modifier.weight(1f)) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        }
        // The highlight floats above everything and springs to the focused tile.
        Canvas(Modifier.fillMaxSize()) {
            val a = hlAlpha.value   // read while drawing: fades redraw only this layer
            if (a <= 0.01f) return@Canvas
            val r = hl.value
            val cr = CornerRadius(13.dp.toPx())
            if (Store.settings.motion != Motion.Lightest) {
                drawRoundRect(Color.White.copy(alpha = 0.10f * a), Offset(r.left - 6.dp.toPx(), r.top - 6.dp.toPx()),
                    Size(r.width + 12.dp.toPx(), r.height + 12.dp.toPx()), CornerRadius(17.dp.toPx()), style = Stroke(6.dp.toPx()))
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f * a), Color.Transparent), r.top, r.top + r.height * 0.45f),
                    r.topLeft, r.size, cr,
                )
            }
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color.White.copy(alpha = 0.55f * a)), r.top, r.bottom),
                r.topLeft, r.size, cr, style = Stroke(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Header(onInputs: () -> Unit, onSetHome: () -> Unit) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val s = Store.settings
    val net by produceState("Wi-Fi") { while (true) { value = Apps.network(ctx); delay(5000) } }
    val stats by produceState("", s.stats) {
        while (s.stats == OnOff.On) { value = Apps.stats(ctx); delay(10_000) }
    }
    Column(Modifier.padding(start = 48.dp, top = 26.dp)) {
        val name = Store.name.trim()
        BasicText(
            Catalog.greeting(now.hour) + if (name.isNotEmpty()) ", $name" else "",
            style = riven(10.sp, FontWeight.Medium, 0.01.em, Ink.ink2),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(clockText(ctx, now), style = riven(46.sp, FontWeight.ExtraLight, (-0.04).em))
        Spacer(Modifier.height(6.dp))
        BasicText(dateText(now), style = riven(10.sp, color = Ink.ink2))
        if (s.stats == OnOff.On && stats.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            BasicText(stats, style = riven(7.5.sp, tracking = 0.04.em, color = Ink.ink3))
        }
    }
    Box(Modifier.fillMaxWidth().padding(end = 48.dp, top = 66.dp), contentAlignment = Alignment.TopEnd) {
        val needsHome by produceState(false) {
            while (true) { value = !RivenKeyService.isDefaultHome(ctx) && !RivenKeyService.isOn(ctx); delay(4000) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsHome) FrostButton("Set as Home", { HomeGlyph(it) }, onClick = onSetHome)
            FrostButton("Inputs", { InputsGlyph(it) }, onClick = onInputs)
            FrostButton(net, { if (net == "Ethernet") EthernetGlyph(it) else WifiIcon(it) }) { Apps.openWifi(ctx) }
            FrostButton("Settings", { GearGlyph(it) }) { Apps.openSettings(ctx) }
        }
    }
}

@Composable
private fun Shelf(focused: RowItem?, moving: String?, modifier: Modifier) {
    Column(modifier.width(640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                when (focused) {
                    is RowItem.Installing -> "INSTALLING · PLAY STORE"
                    is RowItem.Get -> "SUGGESTED"
                    else -> " "
                },
                style = riven(7.5.sp, FontWeight.Medium, 0.3.em, Ink.ink2),
            )
            if (focused is RowItem.App && focused.app.pkg in Store.fresh) {
                Spacer(Modifier.width(8.dp))
                BasicText(
                    "NEW", style = riven(6.5.sp, FontWeight.Bold, 0.12.em, Ink.onInk),
                    modifier = Modifier.background(Ink.ink, CircleShape).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = when (focused) {
                is RowItem.App -> focused.app.label
                is RowItem.Installing -> focused.item.label
                is RowItem.Get -> focused.spec.title
                null -> "Riven"
            },
            transitionSpec = {
                (fadeIn(tween(380, 80, RivenEase)) + slideInVertically(tween(520, easing = RivenEase)) { it / 5 })
                    .togetherWith(fadeOut(tween(150)))
            },
            label = "title",
        ) { title ->
            BasicText(
                title, style = riven(50.sp, FontWeight.SemiBold, (-0.04).em),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        BasicText(
            when {
                moving != null -> "Moving — ◀ ▶ ▲ ▼ to place it, OK to drop."
                focused is RowItem.Installing -> "Downloading. It joins your apps by itself when it's done."
                focused is RowItem.Get -> "Not installed yet. Press OK to get it from Play Store."
                focused == null -> "Install apps from Play Store and they appear here."
                else -> "Press OK to open · hold OK for options"
            },
            style = riven(10.sp, color = Ink.ink2),
        )
    }
}
