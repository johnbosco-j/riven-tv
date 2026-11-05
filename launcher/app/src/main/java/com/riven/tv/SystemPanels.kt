package com.riven.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Riven's own screens for the TV's system features. Where Android lets a normal app do the job
 * (Wi-Fi on Android 8/9, Bluetooth, volume, info) Riven does it itself; where it needs system
 * permission (date, language, factory reset, picture modes) the row opens the TV's own screen.
 */

private fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
private fun gb(b: Long) = "%.1f GB".format(b / 1_073_741_824.0)

@Composable
private fun Systemwide(receiverActions: List<String>, onEvent: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = onEvent() }
        val f = IntentFilter().apply { receiverActions.forEach { addAction(it) } }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
}

@Composable
private fun PanelList(content: LazyListScope.() -> Unit) = LazyColumn(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    contentPadding = PaddingValues(bottom = 24.dp),
    content = content,
)

@Composable
private fun Note(text: String) = BasicText(
    text, style = riven(8.sp, color = Ink.ink3).copy(lineHeight = 13.sp),
    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
)

/* ───────────── Wi-Fi ───────────── */

private fun ssidOf(info: WifiInfo?): String? =
    info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }

private fun secured(r: ScanResult) = listOf("WEP", "PSK", "EAP", "SAE").any { r.capabilities.contains(it) }

private fun strength(level: Int) = when (WifiManager.calculateSignalLevel(level, 4)) { 3 -> "Strong"; 2 -> "Good"; 1 -> "Fair"; else -> "Weak" }

@SuppressLint("MissingPermission")
private fun scanList(wm: WifiManager): List<ScanResult> = runCatching { wm.scanResults }.getOrDefault(emptyList())
    .filter { it.SSID.isNotBlank() }
    .groupBy { it.SSID }.map { (_, v) -> v.maxBy { it.level } }
    .sortedByDescending { it.level }

/** Join a network directly (Android 8/9). Returns false where Android needs its own screen. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun join(wm: WifiManager, r: ScanResult, password: String): Boolean {
    if (Build.VERSION.SDK_INT >= 29 || r.capabilities.contains("EAP")) return false
    val quoted = "\"${r.SSID}\""
    val saved = runCatching { wm.configuredNetworks }.getOrNull()?.firstOrNull { it.SSID == quoted }
    val id = if (saved != null && password.isEmpty()) saved.networkId else {
        val c = WifiConfiguration().apply {
            SSID = quoted
            when {
                r.capabilities.contains("WEP") -> {
                    wepKeys[0] = "\"$password\""; wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                }
                secured(r) -> preSharedKey = "\"$password\""
                else -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
        }
        wm.addNetwork(c)
    }
    if (id == -1) return false
    wm.disconnect()
    val ok = wm.enableNetwork(id, true)
    wm.reconnect()
    return ok
}

@SuppressLint("MissingPermission")
@Composable
fun WifiPanel() {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    if (wm == null) { Action("Wi-Fi", "Not available on this TV") {}; return }
    var enabled by remember { mutableStateOf(wm.isWifiEnabled) }
    var info by remember { mutableStateOf<WifiInfo?>(wm.connectionInfo) }
    var results by remember { mutableStateOf(scanList(wm)) }
    val locPerm = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
    var hasLoc by remember { mutableStateOf(hasPerm(ctx, locPerm)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasLoc = it }
    var joining by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Systemwide(listOf(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION)) {
        enabled = wm.isWifiEnabled; info = wm.connectionInfo; results = scanList(wm)
    }
    LaunchedEffect(enabled, hasLoc) {
        while (enabled && hasLoc) { @Suppress("DEPRECATION") runCatching { wm.startScan() }; results = scanList(wm); delay(15_000) }
    }

    val current = ssidOf(info)
    PanelList {
        item {
            Choice("Wi-Fi", if (enabled) OnOff.On else OnOff.Off, OnOff.entries) { v ->
                @Suppress("DEPRECATION")
                val ok = Build.VERSION.SDK_INT < 29 && runCatching { wm.setWifiEnabled(v == OnOff.On) }.getOrDefault(false)
                if (ok) enabled = v == OnOff.On else Apps.openWifi(ctx)
            }
        }
        item {
            Action("Connected to", current?.let { "$it · ${strength(info?.rssi ?: -100)} · ${info?.linkSpeed ?: 0} Mbps" } ?: if (enabled) "Not connected" else "Wi-Fi is off") {}
        }
        if (status.isNotEmpty()) item { Note(status) }
        if (enabled && !hasLoc) item {
            Action("Show networks nearby", "Allow") { ask.launch(locPerm) }
        }
        if (enabled && hasLoc) {
            item { Note("NETWORKS NEARBY") }
            items(results, key = { it.SSID }) { r ->
                val isCurrent = r.SSID == current
                Column {
                    Action(r.SSID, (if (isCurrent) "Connected · " else "") + strength(r.level) + if (secured(r)) " · Secured" else " · Open") {
                        if (isCurrent) return@Action
                        if (secured(r)) { joining = if (joining == r.SSID) null else r.SSID; password = "" }
                        else status = if (join(wm, r, "")) "Joining ${r.SSID}…" else "Open the TV's Wi-Fi screen to join ${r.SSID}".also { Apps.openWifi(ctx) }
                    }
                    if (joining == r.SSID) PasswordRow(password, { password = it }) {
                        status = if (join(wm, r, password)) "Joining ${r.SSID}… (wrong password? try again)" else
                            "Android needs its own screen for this network".also { Apps.openWifi(ctx) }
                        joining = null
                    }
                }
            }
        }
        item { Action("All Wi-Fi settings", "Open") { Apps.openWifi(ctx) } }
    }
}

@Composable
private fun PasswordRow(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { Store.typing = true }
    DisposableEffect(Unit) { onDispose { Store.typing = false } }
    Row(
        Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 6.dp)
            .background(Ink.glassHi, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Password", style = riven(9.sp, color = Ink.ink2))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = riven(10.sp),
            cursorBrush = SolidColor(Ink.ink),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }, onDone = { onSubmit() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        BasicText("OK on keyboard to join", style = riven(7.5.sp, color = Ink.ink3))
    }
}

/* ───────────── Inputs & channels ───────────── */

@Composable
fun InputsPanel() {
    val ctx = LocalContext.current
    val inputs = remember { Apps.inputs(ctx) }
    PanelList {
        item { Note("SWITCH TO") }
        items(inputs, key = { it.label }) { inp -> Action(inp.label, "Switch") { inp.open() } }
        item { Note("HDMI names come from the TV's own input settings; rename them there and Riven follows.") }
        item { Action("Channel scan & input settings", "Open") { Apps.openLiveTv(ctx) } }
    }
}

/* ───────────── Bluetooth ───────────── */

private fun kind(d: BluetoothDevice): String = runCatching {
    when (d.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Speaker / headphones"
        BluetoothClass.Device.Major.PERIPHERAL -> "Remote / keyboard / mouse"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.TOY -> "Game controller"
        else -> "Device"
    }
}.getOrDefault("Device")

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPanel() {
    val ctx = LocalContext.current
    val bt: BluetoothAdapter? = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    if (bt == null) { PanelList { item { Action("Bluetooth", "Not available on this TV") {} } }; return }

    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    var granted by remember { mutableStateOf(perms.all { hasPerm(ctx, it) }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { v -> v } }
    val connectOk = Build.VERSION.SDK_INT < 31 || hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    var on by remember { mutableStateOf(runCatching { bt.isEnabled }.getOrDefault(false)) }
    var paired by remember { mutableStateOf(if (connectOk) runCatching { bt.bondedDevices.toList() }.getOrDefault(emptyList()) else emptyList()) }
    var found by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var searching by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (found.none { it.address == d.address }) found = found + d
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> searching = false
                    BluetoothAdapter.ACTION_STATE_CHANGED -> on = runCatching { bt.isEnabled }.getOrDefault(false)
                }
                paired = runCatching { bt.bondedDevices.toList() }.getOrDefault(paired)
                tick++
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { if (searching) bt.cancelDiscovery() }; runCatching { ctx.unregisterReceiver(r) } }
    }

    PanelList {
        item {
            Choice("Bluetooth", if (on) OnOff.On else OnOff.Off, OnOff.entries) { v ->
                @Suppress("DEPRECATION")
                val ok = Build.VERSION.SDK_INT < 33 && connectOk && runCatching { if (v == OnOff.On) bt.enable() else bt.disable() }.getOrDefault(false)
                if (!ok) Apps.openSetting(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
            }
        }
        if (on) {
            item { Note("PAIRED") }
            if (paired.isEmpty()) item { Note("No paired devices yet.") }
            items(paired, key = { "p" + it.address }) { d ->
                Action(runCatching { d.name }.getOrNull() ?: d.address, "${kind(d)} · OK to unpair") {
                    // removeBond is not public API, but works on Android TV 8–14.
                    runCatching { d.javaClass.getMethod("removeBond").invoke(d) }
                        .onFailure { Apps.openSetting(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            if (!granted) item { Action("Find new devices", "Allow") { ask.launch(perms) } }
            else item {
                Action(if (searching) "Searching… (stays on for 12 s)" else "Find new devices", if (searching) "Stop" else "Search") {
                    if (searching) { runCatching { bt.cancelDiscovery() }; searching = false }
                    else { found = emptyList(); searching = runCatching { bt.startDiscovery() }.getOrDefault(false) }
                }
            }
            items(found.filter { f -> paired.none { it.address == f.address } }, key = { "f" + it.address }) { d ->
                val bonding = runCatching { d.bondState == BluetoothDevice.BOND_BONDING }.getOrDefault(false)
                Action(runCatching { d.name }.getOrNull() ?: d.address, if (bonding) "Pairing…" else "${kind(d)} · OK to pair") {
                    runCatching { bt.cancelDiscovery(); d.createBond() }
                }
            }
            item { Note("To pair a remote or headphones, put them in pairing mode first. Some devices ask for a PIN on screen.") }
        }
        item { Action("All Bluetooth settings", "Open") { Apps.openSetting(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) } }
    }
    tick.let { }
}

/* ───────────── Sound ───────────── */

@Composable
fun SoundPanel() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var muted by remember { mutableStateOf(am.isStreamMute(AudioManager.STREAM_MUSIC)) }
    Systemwide(listOf("android.media.VOLUME_CHANGED_ACTION")) {
        vol = am.getStreamVolume(AudioManager.STREAM_MUSIC); muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
    }
    PanelList {
        item {
            Level("Volume", vol, max) { v ->
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        item {
            Choice("Mute", if (muted) OnOff.On else OnOff.Off, OnOff.entries) { v ->
                runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (v == OnOff.On) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0) }
                muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            }
        }
        item { Action("Sound mode, speakers & audio output", "Open") { Apps.openSetting(ctx, Settings.ACTION_SOUND_SETTINGS) } }
    }
}

/* ───────────── Picture & display ───────────── */

private data class Caps(val display: String, val hdr: String, val video: List<Pair<String, String>>)

@Suppress("DEPRECATION")
private fun readCaps(ctx: Context): Caps {
    val d: Display = ctx.getSystemService(WindowManager::class.java).defaultDisplay
    val m = d.mode
    val display = "${m.physicalWidth} × ${m.physicalHeight} · ${"%.0f".format(m.refreshRate)} Hz"
    val hdr = runCatching {
        d.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "HDR" }
        }?.distinct()?.joinToString(", ")
    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Not reported"
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    fun best(mime: String): String {
        val caps = codecs.filter { c -> c.supportedTypes.any { it.equals(mime, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
        if (caps.isEmpty()) return "Not supported"
        return when {
            caps.any { it.isSizeSupported(7680, 4320) } -> "Up to 8K"
            caps.any { it.isSizeSupported(3840, 2160) } -> "Up to 4K"
            caps.any { it.isSizeSupported(1920, 1080) } -> "Up to 1080p"
            else -> "Below 1080p"
        }
    }
    val video = listOf(
        "HEVC / H.265 (Netflix, Prime 4K)" to best("video/hevc"),
        "VP9 (YouTube 4K)" to best("video/x-vnd.on2.vp9"),
        "AV1 (newest YouTube)" to best("video/av01"),
        "H.264 (most apps)" to best("video/avc"),
    )
    return Caps(display, hdr, video)
}

@Composable
fun DisplayPanel() {
    val ctx = LocalContext.current
    val caps by produceState<Caps?>(null) { value = withContext(Dispatchers.Default) { runCatching { readCaps(ctx) }.getOrNull() } }
    PanelList {
        item { Action("Screen", caps?.display ?: "…") {} }
        item { Action("HDR", caps?.hdr ?: "…") {} }
        item { Note("VIDEO THIS TV'S CHIP CAN PLAY") }
        caps?.video?.forEach { (k, v) -> item { Action(k, v) {} } }
        item { Note("Video is decoded by the TV's chip, so these limits can't be raised by any app. \"Screen\" is the size Android draws menus at; video apps can still use the full panel.") }
        item { Action("Picture mode, brightness & colour", "Open") { Apps.openSetting(ctx, Settings.ACTION_DISPLAY_SETTINGS) } }
        item { Action("Screen saver", "Open") { Apps.openScreensaverSettings(ctx) } }
    }
}

/* ───────────── Network ───────────── */

@Composable
fun NetworkPanel() {
    val ctx = LocalContext.current
    val rows by produceState(listOf<Pair<String, String>>()) {
        while (true) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val type = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (cable)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> "Other"
            }
            val ip = lp?.linkAddresses?.map { it.address.hostAddress }?.firstOrNull { it?.contains('.') == true } ?: "—"
            val gw = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "—"
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.take(2)?.joinToString(", ").orEmpty().ifEmpty { "—" }
            val internet = if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "Working" else if (caps != null) "Limited" else "No"
            val down = caps?.linkDownstreamBandwidthKbps?.let { if (it > 0) "${it / 1000} Mbps link" else null } ?: "—"
            value = listOf("Connection" to type, "Internet" to internet, "IP address" to ip, "Router" to gw, "DNS" to dns, "Link speed" to down)
            delay(3000)
        }
    }
    PanelList {
        items(rows, key = { it.first }) { (k, v) -> Action(k, v) {} }
        item { Action("Network & internet settings", "Open") { Apps.openSetting(ctx, Settings.ACTION_WIRELESS_SETTINGS) } }
    }
}

/* ───────────── Storage & memory ───────────── */

@Composable
fun StoragePanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var freed by remember { mutableStateOf("") }
    val stats by produceState(Triple("…", "…", "…"), refresh) {
        value = withContext(Dispatchers.IO) {
            val st = StatFs(Environment.getDataDirectory().path)
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val cache = ctx.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            Triple(
                "${gb(st.totalBytes - st.availableBytes)} used of ${gb(st.totalBytes)} · ${gb(st.availableBytes)} free",
                "${gb(mi.totalMem - mi.availMem)} used of ${gb(mi.totalMem)} · ${gb(mi.availMem)} free",
                "${cache / 1024} KB",
            )
        }
    }
    PanelList {
        item { Action("Storage", stats.first) {} }
        item { Action("Memory (RAM)", stats.second) {} }
        item {
            Action("Free up memory", freed.ifEmpty { "Close idle apps" }) {
                freed = "Working…"
                scope.launch {
                    val mb = withContext(Dispatchers.IO) { Apps.freeMemory(ctx) }
                    freed = if (mb > 0) "Freed $mb MB" else "Already lean"
                    refresh++
                }
            }
        }
        item {
            Action("Clear Riven's cache", stats.third) {
                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; refresh++
            }
        }
        item { Note("Free up memory closes apps idling in the background. Apps you're using and Google services are left alone.") }
        item { Action("Storage settings", "Open") { Apps.openSetting(ctx, Settings.ACTION_INTERNAL_STORAGE_SETTINGS) } }
    }
}

/* ───────────── Apps ───────────── */

@Composable
fun AppsPanel(repo: AppRepository) {
    val ctx = LocalContext.current
    PanelList {
        item { Note("${repo.apps.size} APPS · OK opens Android's App info (force stop, clear data, uninstall, permissions)") }
        items(repo.apps, key = { it.pkg }) { app ->
            val version = remember(app.pkg) {
                @Suppress("DEPRECATION")
                runCatching { ctx.packageManager.getPackageInfo(app.pkg, 0).versionName }.getOrNull().orEmpty()
            }
            Action(app.label, version.take(18), leading = { app.icon?.let { Image(it, null, Modifier.size(14.dp)) } }) {
                Apps.appInfo(ctx, app.pkg)
            }
        }
        item { Action("All apps settings", "Open") { Apps.openSetting(ctx, Settings.ACTION_APPLICATION_SETTINGS) } }
    }
}

/* ───────────── System ───────────── */

@Composable
fun SystemPanel() {
    val ctx = LocalContext.current
    val rows = listOf(
        "Date & time" to Settings.ACTION_DATE_SETTINGS,
        "Language" to Settings.ACTION_LOCALE_SETTINGS,
        "Keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "Accounts & sign-in" to Settings.ACTION_SYNC_SETTINGS,
        "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "Privacy" to Settings.ACTION_PRIVACY_SETTINGS,
        "Security & unknown sources" to Settings.ACTION_SECURITY_SETTINGS,
        "Developer options" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "Restart, reset & system update" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "All TV settings" to Settings.ACTION_SETTINGS,
    )
    PanelList {
        items(rows, key = { it.first }) { (label, action) -> Action(label, "Open") { Apps.openSetting(ctx, action) } }
        item { Note("These need system permission, so they open the TV's own screen.") }
    }
}

/* ───────────── About this TV ───────────── */

@Composable
fun AboutTvPanel() {
    val ctx = LocalContext.current
    val rows = remember {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val st = StatFs(Environment.getDataDirectory().path)
        @Suppress("DEPRECATION") val m = ctx.getSystemService(WindowManager::class.java).defaultDisplay.mode
        val up = SystemClock.elapsedRealtime() / 60_000
        listOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Build" to Build.DISPLAY,
            "Processor" to "${Runtime.getRuntime().availableProcessors()} cores · ${Build.HARDWARE} · ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
            "Memory" to gb(mi.totalMem),
            "Storage" to gb(st.totalBytes),
            "Screen" to "${m.physicalWidth} × ${m.physicalHeight}",
            "Kernel" to (System.getProperty("os.version") ?: "—"),
            "Up time" to "${up / 60} h ${up % 60} min",
            "Riven" to "${BuildConfig.VERSION_NAME} (${if (BuildConfig.HOME_SERVICE) "full" else "lite"})",
        )
    }
    PanelList {
        items(rows, key = { it.first }) { (k, v) -> Action(k, v) {} }
        item { Action("Device info in TV settings", "Open") { Apps.openSetting(ctx, Settings.ACTION_DEVICE_INFO_SETTINGS) } }
    }
}

/** A 0..max value changed with ◀ ▶, drawn as a segmented bar. */
@Composable
internal fun Level(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    val opts = remember(max) { (0..max).map { n -> object : Labeled { override val label = n.toString() } } }
    Choice(label, opts[value.coerceIn(0, max)], opts, swatch = {
        Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            val segs = 20
            repeat(segs) { i ->
                val lit = i < (value * segs + max - 1) / max.coerceAtLeast(1)
                Box(Modifier.size(width = 4.dp, height = 9.dp).background(if (lit) Ink.ink else Ink.ink.copy(alpha = 0.18f), RoundedCornerShape(1.dp)))
            }
        }
    }, wrap = false) { onChange(opts.indexOf(it)) }
}

