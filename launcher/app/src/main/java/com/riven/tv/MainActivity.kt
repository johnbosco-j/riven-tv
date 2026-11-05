package com.riven.tv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var repo: AppRepository
    private val homeSignal = mutableIntStateOf(0)
    private val lastInput = mutableLongStateOf(SystemClock.uptimeMillis())
    private val ambient = mutableStateOf(false)
    private val resumed = mutableStateOf(false)
    private val remote = mutableStateOf<RemoteEvent?>(null)
    private var firstFocus = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        repo = AppRepository(applicationContext, lifecycleScope)
        setContent {
            RivenCanvas {
                RivenApp(repo, homeSignal.intValue, remote.value, onPreviewSaver = { ambient.value = true })

                // Idle → screensaver.
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(5000)
                        val m = Store.settings.saverAfter.minutes
                        if (m > 0 && resumed.value && !ambient.value &&
                            SystemClock.uptimeMillis() - lastInput.longValue > m * 60_000L
                        ) ambient.value = true
                    }
                }
                AnimatedVisibility(ambient.value, enter = fadeIn(tween(1400)), exit = fadeOut(tween(600))) {
                    val byPkg = repo.apps.associateBy { it.pkg }
                    Ambient(Store.settings.saverStyle, Store.home.mapNotNull { byPkg[it] })
                }
            }
        }
    }

    override fun onStart() { super.onStart(); repo.start() }
    override fun onStop() { repo.stop(); super.onStop() }

    override fun onResume() {
        super.onResume()
        resumed.value = true
        lastInput.longValue = SystemClock.uptimeMillis()
        repo.refreshSoon()
    }

    override fun onPause() { resumed.value = false; super.onPause() }

    /** The first time the window gets focus, hand it to the first tile (not the tab bar). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && firstFocus) {
            firstFocus = false
            // The system hands out its own default focus right after this on older TVs (Android 8),
            // so ask again a moment later to make sure the first tile wins.
            homeSignal.intValue++
            window.decorView.postDelayed({ homeSignal.intValue++ }, 350)
            window.decorView.postDelayed({ homeSignal.intValue++ }, 1000)
        }
    }

    /** Home pressed while Riven is already in front. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ambient.value = false
        homeSignal.intValue++
    }

    /** Any key wakes the screensaver (and is swallowed so it doesn't also act on the UI). */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput.longValue = SystemClock.uptimeMillis()
        if (ambient.value) {
            if (event.action == KeyEvent.ACTION_UP) ambient.value = false
            return true
        }
        // Thomson remotes: the Settings/dashboard button sends keycode 83 (KEYCODE_NOTIFICATION),
        // which only the stock Google TV home answered. Riven answers it while it's in front.
        if (event.keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
            if (event.action == KeyEvent.ACTION_UP) Apps.openSettings(this)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Store.lastKey = "${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")} · ${event.keyCode}"
        }
        // Remote shortcuts (Source, Menu, Info, Guide, 0–9, colour keys, Exit). Handled on key-up,
        // with the key-down swallowed too so nothing underneath reacts.
        if (Remote.handles(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) remote.value = RemoteEvent(event.keyCode, SystemClock.uptimeMillis())
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

enum class Tab(val label: String) { Home("Home"), Apps("Apps"), Customize("Settings") }

@Composable
private fun RivenApp(repo: AppRepository, homeSignal: Int, remote: RemoteEvent?, onPreviewSaver: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(Tab.Home) }
    var focusColor by remember { mutableStateOf(Color(0xFF4B5BFF)) }
    var sheet by remember { mutableStateOf<RowItem?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var contentFocus by remember { mutableIntStateOf(0) }   // bump → current page grabs focus
    var inputs by remember { mutableStateOf(false) }
    var homeSheet by remember { mutableStateOf(false) }
    var homeScrolled by remember { mutableStateOf(false) }
    var focusedItem by remember { mutableStateOf<RowItem?>(null) }
    var grabOnEnter by remember { mutableStateOf(true) }   // false when the page was opened by focusing its tab

    fun goTab(t: Tab, grab: Boolean) { grabOnEnter = grab; tab = t; if (grab) contentFocus++ }

    LaunchedEffect(remote) {
        val e = remote ?: return@LaunchedEffect
        val overlay = sheet != null || inputs || homeSheet
        when (val cmd = Remote.command(e.code)) {
            is Remote.Cmd.Run -> if (!overlay) when (cmd.action) {
                RemoteAction.AllApps -> goTab(Tab.Apps, true)
                RemoteAction.Customize -> goTab(Tab.Customize, true)
                RemoteAction.Inputs -> inputs = true
                RemoteAction.Screensaver -> onPreviewSaver()
                RemoteAction.Settings -> Apps.openSettings(ctx)
                RemoteAction.LiveTV -> Apps.openLiveTv(ctx)
                RemoteAction.Wifi -> Apps.openWifi(ctx)
                RemoteAction.PlayStore -> Apps.open(ctx, "com.android.vending")
                RemoteAction.YouTube -> listOf("com.google.android.youtube.tv", "com.google.android.youtube").any { Apps.open(ctx, it) }
                RemoteAction.Nothing -> Unit
            }
            is Remote.Cmd.OpenNth -> if (!overlay && !Store.typing) {
                val byPkg = repo.apps.associateBy { it.pkg }
                Store.home.mapNotNull { byPkg[it] }.getOrNull(cmd.n - 1)?.let { Apps.open(ctx, it.pkg) }
            }
            Remote.Cmd.Options -> if (!overlay) focusedItem?.takeIf { it !is RowItem.Installing }?.let { sheet = it }
            Remote.Cmd.Exit -> when {
                inputs -> { inputs = false; contentFocus++ }
                sheet != null -> { sheet = null; contentFocus++ }
                moving != null -> moving = null
                else -> goTab(Tab.Home, true)
            }
            null -> Unit
        }
    }

    LaunchedEffect(homeSignal) {
        sheet = null; inputs = false; moving = null; grabOnEnter = true; tab = Tab.Home; contentFocus++
    }
    // Back on Apps/Customize returns Home; on Home it does nothing (a launcher never exits).
    BackHandler(enabled = sheet == null && !inputs && !homeSheet && moving == null) {
        if (tab != Tab.Home) goTab(Tab.Home, true)
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(focusColor)

        AnimatedContent(
            tab,
            transitionSpec = {
                (fadeIn(tween(420, easing = RivenEase)) + scaleIn(tween(520, easing = RivenEase), initialScale = 0.985f))
                    .togetherWith(fadeOut(tween(200)))
            },
            label = "page",
        ) { t ->
            when (t) {
                Tab.Home -> HomeScreen(
                    repo, contentFocus, grabOnEnter, moving,
                    onMoveDone = { moving = null; Apps.toast(ctx, "Home order saved") },
                    onColor = { focusColor = it },
                    onOptions = { sheet = it },
                    onFocusedItem = { focusedItem = it },
                    onInputs = { inputs = true },
                    onSetHome = { homeSheet = true },
                    onScrolled = { homeScrolled = it },
                )
                Tab.Apps -> AppsScreen(
                    repo, contentFocus, grabOnEnter, onColor = { focusColor = it }, onOptions = { sheet = it },
                    onFocusedItem = { focusedItem = it },
                )
                Tab.Customize -> CustomizeScreen(
                    repo, contentFocus, grabOnEnter, onPreviewSaver,
                    onExit = { goTab(Tab.Home, true) },
                )
            }
        }

        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(64.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.base.copy(alpha = 0.92f))))
        )
        TopBar(tab, hidden = tab == Tab.Home && homeScrolled, onTab = { if (it != tab) goTab(it, false) })
        Footer(tab, Modifier.align(Alignment.BottomStart))

        if (inputs) InputsSheet(onClose = { inputs = false; contentFocus++ })
        if (homeSheet) SetHomeSheet(onClose = { homeSheet = false; contentFocus++ })

        sheet?.let { target ->
            OptionsSheet(
                target, fromHome = tab == Tab.Home,
                onMove = { moving = it },
                onClose = { sheet = null; contentFocus++ },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(tab: Tab, hidden: Boolean, onTab: (Tab) -> Unit) {
    val tabReq = remember { Tab.entries.associateWith { FocusRequester() } }
    val ctx = LocalContext.current
    val now = rememberNow()
    val online by produceState(true) { while (true) { value = Apps.isOnline(ctx); delay(5000) } }

    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(300), label = "bar")
    val onHome = tab == Tab.Home   // Home draws its own clock and status buttons
    Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 26.dp).height(36.dp).graphicsLayer { this.alpha = alpha }) {
        if (!onHome) Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Bottom) {
            BasicText(clockText(ctx, now), style = riven(23.sp, FontWeight.Light, (-0.03).em))
            Spacer(Modifier.width(9.dp))
            BasicText(dateText(now), style = riven(9.sp, color = Ink.ink2), modifier = Modifier.padding(bottom = 3.dp))
        }
        Row(
            Modifier.align(Alignment.Center).glass(CircleShape).padding(3.dp)
                // Coming up from the page lands on the current tab, not whichever is nearest.
                .focusProperties { enter = { tabReq.getValue(tab) } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.forEach { t ->
                // Focusing a tab switches to it, like a TV top bar; ▼ drops into the page.
                Pill(t.label, selected = t == tab, modifier = Modifier.focusRequester(tabReq.getValue(t)), onFocus = { onTab(t) }) { onTab(t) }
            }
        }
        if (!onHome) Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            WifiGlyph(online)
            Spacer(Modifier.width(9.dp))
            Monogram()
        }
    }
}

@Composable
private fun Footer(tab: Tab, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Credit()
        Row {
            if (tab == Tab.Customize) {
                Hint("▲ ▼", "Move"); Hint("◀ ▶", "Change"); Hint("BACK", "Return")
            } else {
                Hint("OK", "Open"); Hint("HOLD OK", "Options"); Hint("BACK", "Home")
            }
        }
    }
}


data class RemoteEvent(val code: Int, val at: Long)

/**
 * Remote buttons Riven answers while it's in front (RM-L1670-style Thomson remotes and most others).
 * Netflix / YouTube / Prime Video / Google Play buttons are handled by the TV itself.
 */
object Remote {
    sealed interface Cmd {
        data class Run(val action: RemoteAction) : Cmd
        data class OpenNth(val n: Int) : Cmd
        data object Options : Cmd
        data object Exit : Cmd
    }

    private val digits = (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
    private val fixed = setOf(
        KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE,
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TV_INPUT_HDMI_1, KeyEvent.KEYCODE_LAST_CHANNEL,
    )

    fun handles(code: Int) = code in fixed || (code in digits && !Store.typing)

    fun command(code: Int): Cmd? {
        val s = Store.settings
        return when (code) {
            KeyEvent.KEYCODE_TV_INPUT -> Cmd.Run(RemoteAction.Inputs)            // SOURCE
            KeyEvent.KEYCODE_MENU -> Cmd.Options                                 // MENU → app options
            KeyEvent.KEYCODE_INFO -> Cmd.Run(RemoteAction.Customize)             // INFO
            KeyEvent.KEYCODE_GUIDE -> Cmd.Run(RemoteAction.LiveTV)               // GUIDE
            KeyEvent.KEYCODE_LAST_CHANNEL -> Cmd.Run(RemoteAction.AllApps)       // LIST on some remotes
            KeyEvent.KEYCODE_TV_INPUT_HDMI_1 -> Cmd.Run(RemoteAction.Inputs)
            KeyEvent.KEYCODE_PROG_RED -> Cmd.Run(s.red)
            KeyEvent.KEYCODE_PROG_GREEN -> Cmd.Run(s.green)
            KeyEvent.KEYCODE_PROG_YELLOW -> Cmd.Run(s.yellow)
            KeyEvent.KEYCODE_PROG_BLUE -> Cmd.Run(s.blue)
            KeyEvent.KEYCODE_ESCAPE -> Cmd.Exit                                  // EXIT
            KeyEvent.KEYCODE_0 -> Cmd.Run(RemoteAction.AllApps)
            in digits -> Cmd.OpenNth(code - KeyEvent.KEYCODE_0)                  // 1–9 → nth app on Home
            else -> null
        }
    }
}
