package com.riven.tv

import android.view.KeyEvent as AKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private enum class Section(val label: String, val title: String, val blurb: String, val tv: Boolean) {
    Wifi("Wi-Fi", "Wi-Fi", "Join networks without leaving Riven.", true),
    Bluetooth("Bluetooth", "Bluetooth", "Remotes, speakers, headphones and controllers.", true),
    Inputs("Inputs & channels", "Inputs & channels", "HDMI devices, AV and Live TV.", true),
    Sound("Sound", "Sound", "Volume and audio output.", true),
    Display("Picture & display", "Picture & display", "What this screen and its chip can show.", true),
    Network("Network", "Network", "Connection details.", true),
    Storage("Storage & memory", "Storage & memory", "Space, memory and a quick clean-up.", true),
    AppsS("Apps", "Apps", "Everything installed on this TV.", true),
    System("System", "System", "Date, language, keyboard, accounts, reset.", true),
    AboutTv("About this TV", "About this TV", "Model, Android version, hardware.", true),
    Home("Home", "Home", "What's on your home screen and how it behaves.", false),
    Look("Look", "Look", "Background, fonts, app icons and effects.", false),
    Saver("Screensaver", "Screensaver", "Starts by itself when the remote has been idle.", false),
    Remote("Remote buttons", "Remote buttons", "What your remote's buttons do while Riven is on screen.", false),
    Hidden("Hidden apps", "Hidden apps", "Hidden apps stay installed but leave Home and All apps.", false),
    About("About Riven", "About", "", false),
}

@Composable
fun CustomizeScreen(repo: AppRepository, focusSignal: Int, grabOnEnter: Boolean, onPreviewSaver: () -> Unit, onExit: () -> Unit) {
    var section by remember { mutableStateOf(Section.Wifi) }
    var inPanel by remember { mutableStateOf(false) }
    val navReq = remember { Section.entries.associateWith { FocusRequester() } }
    val panelReq = remember { FocusRequester() }
    val enteredWith = remember { focusSignal }
    LaunchedEffect(focusSignal) {
        if (focusSignal == enteredWith && !grabOnEnter) return@LaunchedEffect   // opened from the tab bar
        withFrameNanos { }; runCatching { navReq.getValue(section).requestFocus() }
    }

    BackHandler {
        if (inPanel) runCatching { navReq.getValue(section).requestFocus() } else onExit()
    }

    Row(
        Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 86.dp, bottom = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // nav: TV group, then Riven group; scrolls with the D-pad
        Column(
            Modifier.width(200.dp).fillMaxHeight().glass(RoundedCornerShape(18.dp))
                // ▶ from the menu always lands on the panel's first row, not whichever row is level with it.
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == AKey.KEYCODE_DPAD_RIGHT && !inPanel) {
                        runCatching { panelReq.requestFocus() }.isSuccess
                    } else false
                }
                .verticalScroll(rememberScrollState()).padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(true to "TV", false to "RIVEN").forEach { (tv, header) ->
                BasicText(header, style = riven(7.sp, tracking = 0.3.em, color = Ink.ink3), modifier = Modifier.padding(start = 12.dp, top = if (tv) 2.dp else 12.dp, bottom = 4.dp))
                Section.entries.filter { it.tv == tv }.forEach { sec ->
                    NavItem(sec.label, sec == section, Modifier.focusRequester(navReq.getValue(sec))) { section = sec }
                }
            }
        }
        // panel
        Box(
            Modifier.weight(1f).fillMaxHeight().glass(RoundedCornerShape(18.dp))
                .onFocusChanged { inPanel = it.hasFocus }
                .focusRequester(panelReq)
                .focusGroup()
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            AnimatedContent(section, transitionSpec = { fadeIn(tween(260)).togetherWith(fadeOut(tween(120))) }, label = "sec") { sec ->
                when (sec) {
                    Section.About -> About()
                    else -> Column {
                        BasicText(sec.title, style = riven(22.sp, FontWeight.SemiBold, (-0.03).em))
                        Spacer(Modifier.height(4.dp))
                        BasicText(sec.blurb, style = riven(9.sp, color = Ink.ink2))
                        Spacer(Modifier.height(14.dp))
                        when (sec) {
                            Section.Home -> HomeOptions(repo)
                            Section.Look -> LookOptions()
                            Section.Saver -> SaverOptions(onPreviewSaver)
                            Section.Remote -> RemoteOptions()
                            Section.Wifi -> WifiPanel()
                            Section.Bluetooth -> BluetoothPanel()
                            Section.Inputs -> InputsPanel()
                            Section.Sound -> SoundPanel()
                            Section.Display -> DisplayPanel()
                            Section.Network -> NetworkPanel()
                            Section.Storage -> StoragePanel()
                            Section.AppsS -> AppsPanel(repo)
                            Section.System -> SystemPanel()
                            Section.AboutTv -> AboutTvPanel()
                            Section.Hidden -> HiddenOptions(repo)
                            Section.About -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeOptions(repo: AppRepository) {
    val ctx = LocalContext.current
    val s = Store.settings
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NameField()
        val homeState by produceState("", Unit) {
            while (true) {
                value = when {
                    RivenKeyService.isDefaultHome(ctx) -> "Riven is Home"
                    RivenKeyService.isOn(ctx) -> "On (accessibility)"
                    else -> "Off — OK to turn on"
                }
                kotlinx.coroutines.delay(3000)
            }
        }
        Action("Home button opens Riven", homeState) { RivenKeyService.openSettings(ctx) }
        Choice("Dock", s.dock, DockSize.entries) { Store.update(s.copy(dock = it)) }
        Choice("Add new installs to Home", s.autoAdd, OnOff.entries) { Store.update(s.copy(autoAdd = it)) }
        Choice("App names", s.names, NameMode.entries) { Store.update(s.copy(names = it)) }
        Choice("Card size", s.size, CardSize.entries) { Store.update(s.copy(size = it)) }
        Choice("Clock", s.clock, ClockFormat.entries) { Store.update(s.copy(clock = it)) }
        Choice("Memory & storage line", s.stats, OnOff.entries) { Store.update(s.copy(stats = it)) }
        Action("Reset home layout", "OK") { Store.resetHome(repo.apps); Apps.toast(ctx, "Home layout reset") }
    }
}

@Composable
private fun LookOptions() {
    val s = Store.settings
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Choice("Background", s.backdrop, Backdrop.entries, swatch = { b ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val c = b.colors
                if (c == null) Box(Modifier.size(9.dp).background(
                    Brush.sweepGradient(listOf(Color(0xFFFF3B3B), Color(0xFFFFB020), Color(0xFF2FBF71), Color(0xFF3AA0FF), Color(0xFFC04BFF), Color(0xFFFF3B3B))),
                    CircleShape,
                ))
                else { Box(Modifier.size(9.dp).background(c.first, CircleShape)); Box(Modifier.size(9.dp).background(c.second, CircleShape)) }
            }
        }) { Store.update(s.copy(backdrop = it)) }
        Choice("Fonts", s.typeface, Typeface.entries) { Store.update(s.copy(typeface = it)) }
        Choice("App icons", s.icons, IconStyle.entries) { Store.update(s.copy(icons = it)) }
        Choice("Effects", s.motion, Motion.entries) { Store.update(s.copy(motion = it)) }
    }
}

@Composable
private fun SaverOptions(onPreview: () -> Unit) {
    val ctx = LocalContext.current
    val s = Store.settings
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Choice("Start after", s.saverAfter, SaverAfter.entries) { Store.update(s.copy(saverAfter = it)) }
        Choice("Style", s.saverStyle, SaverStyle.entries) { Store.update(s.copy(saverStyle = it)) }
        Action("Preview now", "OK", onClick = onPreview)
        Action("Use as the TV's screensaver too", "OK") { Apps.openScreensaverSettings(ctx) }
    }
}

@Composable
private fun RemoteOptions() {
    val s = Store.settings
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Choice("Red button", s.red, RemoteAction.entries, swatch = { Dot(Color(0xFFE5484D)) }) { Store.update(s.copy(red = it)) }
        Choice("Green button", s.green, RemoteAction.entries, swatch = { Dot(Color(0xFF30A46C)) }) { Store.update(s.copy(green = it)) }
        Choice("Yellow button", s.yellow, RemoteAction.entries, swatch = { Dot(Color(0xFFF5D90A)) }) { Store.update(s.copy(yellow = it)) }
        Choice("Blue button", s.blue, RemoteAction.entries, swatch = { Dot(Color(0xFF3E63DD)) }) { Store.update(s.copy(blue = it)) }
        Spacer(Modifier.height(8.dp))
        BasicText(
            "SOURCE  Inputs     MENU  App options     INFO  Customize     GUIDE  Live TV\n" +
                "1 – 9  Open that app on Home     0  All apps     EXIT  Close / back to Home\n" +
                "SETTINGS key opens TV settings. Netflix, YouTube, Prime Video and Google Play keys are handled by the TV.",
            style = riven(8.sp, color = Ink.ink2).copy(lineHeight = 14.sp),
            modifier = Modifier.padding(horizontal = 13.dp),
        )
        Spacer(Modifier.height(10.dp))
        Action("Key tester — press any button", Store.lastKey.ifEmpty { "waiting…" }) {}
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(9.dp).background(c, CircleShape))

/** "Your name", shown in the greeting. OK opens the TV keyboard. */
@Composable
private fun NameField() {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(220, easing = RivenEase), label = "name")
    val fg = lerp(Ink.ink, Ink.onInk, t)
    var text by remember { mutableStateOf(Store.name) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focused) { Store.typing = focused }
    Row(
        Modifier.fillMaxWidth().background(Ink.ink.copy(alpha = t), RoundedCornerShape(10.dp)).padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Your name", style = riven(10.5.sp, color = fg))
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(24); Store.saveName(text) },
            singleLine = true,
            interactionSource = interaction,
            textStyle = riven(10.sp, color = fg).copy(textAlign = TextAlign.End),
            cursorBrush = SolidColor(fg),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
            keyboardActions = KeyboardActions(onDone = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.width(200.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) BasicText("Add your name", style = riven(10.sp, color = lerp(Ink.ink3, Ink.onInk.copy(alpha = 0.5f), t)))
                    inner()
                }
            },
        )
    }
}

@Composable
private fun HiddenOptions(repo: AppRepository) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(repo.apps, key = { it.pkg }) { app ->
            val hidden = app.pkg in Store.hidden
            Action(app.label, if (hidden) "Hidden" else "Shown", leading = {
                app.icon?.let { Image(it, null, Modifier.size(14.dp)) }
            }) { if (hidden) Store.unhide(app.pkg) else Store.hide(app.pkg) }
        }
    }
}

@Composable
private fun About() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size(56.dp)
                .graphicsLayer { shadowElevation = 20.dp.toPx(); shape = RoundedCornerShape(15.dp); clip = true }
                .background(Brush.linearGradient(listOf(Color(0xFF2A2A30), Color(0xFF0D0D10))))
                .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.3f), Color.Transparent)), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(17.dp).rotate(45f).border(1.5.dp, Color.White)) }
        Spacer(Modifier.height(16.dp))
        BasicText("Riven", style = riven(28.sp, FontWeight.SemiBold, (-0.03).em))
        Spacer(Modifier.height(9.dp))
        Row {
            BasicText("Designed & built by ", style = riven(12.sp, color = Ink.ink2))
            BasicText(Catalog.AUTHOR, style = riven(12.sp, FontWeight.SemiBold))
        }
        Spacer(Modifier.height(5.dp))
        BasicText("Made by ${Catalog.STUDIO}", style = riven(9.sp, color = Ink.ink2))
        Spacer(Modifier.height(16.dp))
        BasicText("VERSION $version · ANDROID TV LAUNCHER", style = riven(7.sp, tracking = 0.3.em, color = Ink.ink3))
    }
}

/* ───────────── rows ───────────── */

@Composable
private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val bg = when { focused -> Ink.ink; selected -> Ink.glassHi; else -> Color.Transparent }
    Box(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp))
            .tvClickable(interaction, null) {}
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        BasicText(label, style = riven(11.sp, color = when { focused -> Ink.onInk; selected -> Ink.ink; else -> Ink.ink2 }))
    }
}

/** Option with a set of values. ◀ ▶ (or OK) cycle through them. */
@Composable
internal fun <E : Labeled> Choice(
    label: String,
    value: E,
    options: List<E>,
    swatch: (@Composable (E) -> Unit)? = null,
    wrap: Boolean = true,
    onChange: (E) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(220, easing = RivenEase), label = "choice")
    val i = options.indexOf(value)
    fun step(d: Int) {
        val j = if (wrap) (i + d + options.size) % options.size else (i + d).coerceIn(0, options.lastIndex)
        if (j != i) onChange(options[j])
    }
    val fg = lerp(Ink.ink, Ink.onInk, t)

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.ink.copy(alpha = t), RoundedCornerShape(10.dp))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AKey.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    AKey.KEYCODE_DPAD_RIGHT -> { step(+1); true }
                    else -> false
                }
            }
            .tvClickable(interaction, null) { step(+1) }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = riven(10.5.sp, color = fg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("◀", style = riven(7.sp, color = fg.copy(alpha = 0.5f * t)))
            Spacer(Modifier.width(8.dp))
            swatch?.let { it(value); Spacer(Modifier.width(7.dp)) }
            BasicText(value.label, style = riven(10.sp, color = lerp(Ink.ink2, Ink.onInk, t)))
            Spacer(Modifier.width(8.dp))
            BasicText("▶", style = riven(7.sp, color = fg.copy(alpha = 0.5f * t)))
        }
    }
}

@Composable
internal fun Action(label: String, trailing: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(220, easing = RivenEase), label = "action")
    val fg = lerp(Ink.ink, Ink.onInk, t)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ink.ink.copy(alpha = t), RoundedCornerShape(10.dp))
            .tvClickable(interaction, null, onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(9.dp)) }
            BasicText(label, style = riven(10.5.sp, color = fg))
        }
        BasicText(trailing, style = riven(10.sp, color = lerp(Ink.ink2, Ink.onInk.copy(alpha = 0.6f), t)))
    }
}

