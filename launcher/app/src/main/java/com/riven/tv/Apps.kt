package com.riven.tv

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app, with its real (full-colour) banner/icon and a dominant colour. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val color: Color,
    val updated: Long,
)

/** A Play Store (or any installer) download in progress. */
data class InstallItem(
    val id: Int,
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val progress: Float,
)

/**
 * How Riven knows what's installed — the same three mechanisms every Android launcher uses:
 *  1. PackageManager queries for MAIN + LEANBACK_LAUNCHER / LAUNCHER activities,
 *  2. PACKAGE_ADDED / REMOVED / CHANGED broadcasts to refresh when something changes,
 *  3. PackageInstaller sessions (via LauncherApps on Android 10+) to show installs in progress.
 */
class AppRepository(private val ctx: Context, private val scope: CoroutineScope) {

    var apps by mutableStateOf(emptyList<AppEntry>()); private set
    var installs by mutableStateOf(emptyList<InstallItem>()); private set

    private val cache = HashMap<String, AppEntry>()
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val installer get() = ctx.packageManager.packageInstaller
    private var refreshJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refreshSoon()
    }

    private val sessions = object : PackageInstaller.SessionCallback() {
        override fun onCreated(id: Int) = track(id)
        override fun onBadgingChanged(id: Int) = track(id)
        override fun onActiveChanged(id: Int, active: Boolean) = track(id)
        override fun onProgressChanged(id: Int, progress: Float) = track(id)
        override fun onFinished(id: Int, success: Boolean) {
            installs = installs.filterNot { it.id == id }
            if (success) refreshSoon()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (Build.VERSION.SDK_INT >= 29) {
            launcherApps.registerPackageInstallerSessionCallback(ctx.mainExecutor, sessions)
        } else {
            installer.registerSessionCallback(sessions, Handler(Looper.getMainLooper()))
        }
        allSessions().forEach { track(it.sessionId) }
        refreshSoon(0)
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= 29) launcherApps.unregisterPackageInstallerSessionCallback(sessions)
        else installer.unregisterSessionCallback(sessions)
    }

    fun refreshSoon(delayMs: Long = 400) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            val list = withContext(Dispatchers.IO) { load() }
            apps = list
            Store.reconcile(list)
            installs = installs.filterNot { inst -> list.any { it.pkg == inst.pkg } }
        }
    }

    private fun allSessions(): List<PackageInstaller.SessionInfo> =
        if (Build.VERSION.SDK_INT >= 29) launcherApps.allPackageInstallerSessions else installer.allSessions

    private fun track(id: Int) {
        val info = allSessions().firstOrNull { it.sessionId == id } ?: return
        val pkg = info.appPackageName ?: return
        // Updates to apps already installed aren't "new"; only queue fresh installs.
        if (pkg == ctx.packageName || apps.any { it.pkg == pkg }) return
        val item = InstallItem(
            id = id,
            pkg = pkg,
            label = info.appLabel?.toString() ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            icon = info.appIcon?.asImageBitmap(),
            progress = info.progress.coerceIn(0f, 1f),
        )
        installs = installs.filterNot { it.id == id } + item
    }

    private fun load(): List<AppEntry> {
        val pm = ctx.packageManager
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        val out = LinkedHashMap<String, AppEntry>()
        for (q in queries) {
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || pkg in out) continue
                val updated = runCatching {
                    @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).lastUpdateTime
                }.getOrDefault(0L)
                val cached = cache[pkg]
                if (cached != null && cached.updated == updated) { out[pkg] = cached; continue }

                val banner = runCatching {
                    (ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm))
                        ?.toBitmap(320, 180)
                }.getOrNull()
                val icon = runCatching { ri.loadIcon(pm).toBitmap(144, 144) }.getOrNull()
                val entry = AppEntry(
                    pkg = pkg,
                    label = ri.loadLabel(pm).toString(),
                    banner = banner?.asImageBitmap(),
                    icon = icon?.asImageBitmap(),
                    color = vivid(banner ?: icon),
                    updated = updated,
                )
                cache[pkg] = entry
                out[pkg] = entry
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    /** Dominant colour of the art, lifted so it glows nicely behind the UI. */
    private fun vivid(bmp: Bitmap?): Color {
        if (bmp == null) return Color(0xFF6B6B76)
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val rgb = (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb ?: return Color(0xFF6B6B76)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.45f)
        hsl[2] = hsl[2].coerceIn(0.42f, 0.62f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}

/** Opening things. */
object Apps {

    fun resolve(spec: TileSpec, apps: List<AppEntry>): String? =
        spec.packages.firstOrNull { p -> apps.any { it.pkg == p } }
            ?: apps.firstOrNull { a ->
                spec.keywords.any { k -> Regex("(^|\\W)${Regex.escape(k)}(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(a.label) }
            }?.pkg

    fun open(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        return start(ctx, intent).also { if (it) Store.opened(pkg) }
    }

    fun openStore(ctx: Context, pkg: String) {
        if (!start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))) {
            toast(ctx, "Play Store isn't available")
        }
    }

    fun appInfo(ctx: Context, pkg: String) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    fun uninstall(ctx: Context, pkg: String) {
        @Suppress("DEPRECATION")
        if (!start(ctx, Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")))) appInfo(ctx, pkg)
    }

    fun openSettings(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_SETTINGS))) toast(ctx, "Settings unavailable")
    }

    fun openScreensaverSettings(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_DREAM_SETTINGS))) {
            toast(ctx, "This TV hides that menu. Run ./riven.sh screensaver from your Mac.")
        }
    }

    /** Live TV apps, Thomson's first (com.smartdevice.livetv, per CleanYourTV's Thomson SPPL notes). */
    private val liveTvApps = listOf(
        "com.smartdevice.livetv", "com.mediatek.wwtv.tvcenter", "com.android.tv", "com.google.android.tv", "com.droidlogic.tvinput",
    )

    data class TvInput(val label: String, val open: () -> Unit)

    /** HDMI / AV / tuner inputs, under the names given in the TV's own settings. */
    fun inputs(ctx: Context): List<TvInput> {
        val tim = ctx.getSystemService(TvInputManager::class.java) ?: return emptyList()
        val list = runCatching { tim.tvInputList }.getOrDefault(emptyList())
            .filter { it.parentId == null || it.isPassthroughInput }
            .sortedWith(compareBy({ !it.isPassthroughInput }, { it.type }))
        val out = list.map { info ->
            val label = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx)).toString()
            val uri = if (info.isPassthroughInput) TvContract.buildChannelUriForPassthroughInput(info.id)
                      else TvContract.buildChannelsUriForInput(info.id)
            TvInput(label) { if (!start(ctx, Intent(Intent.ACTION_VIEW, uri))) openInputs(ctx) }
        }.distinctBy { it.label }
        return out + TvInput("Live TV") { liveTvApps.any { open(ctx, it) } || run { toast(ctx, "No Live TV app found"); true } }
    }

    fun openLiveTv(ctx: Context) { if (liveTvApps.none { open(ctx, it) }) openInputs(ctx) }

    fun openWifi(ctx: Context) { if (!start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))) openSettings(ctx) }

    fun network(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "Offline"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Online"
        }
    }

    /** "1.2 GB memory free · 5.4 GB storage free" */
    fun stats(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val free = StatFs(Environment.getDataDirectory().path).availableBytes
        fun gb(b: Long) = "%.1f GB".format(b / 1_073_741_824.0)
        return "${gb(mi.availMem)} memory free · ${gb(free)} storage free"
    }

    /** Packages never touched by "Free up memory" (Play services, voice, remote, keyboards). */
    private val keepAlive = listOf("com.google.android.gms", "com.google.android.gsf", "com.google.android.katniss",
        "com.google.android.tv.remote", "inputmethod", "com.android.", "android", "com.mediatek", "com.mstar")

    /** Closes apps idling in the background to give the TV memory back. Returns MB freed. */
    fun freeMemory(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
        fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        @Suppress("DEPRECATION")
        for (ai in ctx.packageManager.getInstalledApplications(0)) {
            val pkg = ai.packageName
            if (pkg == ctx.packageName || keepAlive.any { pkg == it || pkg.startsWith(it) || pkg.contains(it) && it == "inputmethod" }) continue
            val system = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (system && !updatedSystem) continue
            runCatching { am.killBackgroundProcesses(pkg) }
        }
        Thread.sleep(700)
        return ((avail() - before) / 1_048_576).coerceAtLeast(0)
    }

    fun openSetting(ctx: Context, action: String) { if (!start(ctx, Intent(action))) openSettings(ctx) }

    /** Fallback for TVs without a TvInputManager list. */
    fun openInputs(ctx: Context) {
        if (start(ctx, Intent("com.android.tv.action.VIEW_INPUTS"))) return
        val tvApps = liveTvApps
        if (tvApps.any { open(ctx, it) }) return
        toast(ctx, "Open your TV's input app from Apps")
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
