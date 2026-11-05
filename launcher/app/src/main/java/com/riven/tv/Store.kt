package com.riven.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/* ───────────── user-facing options ───────────── */

interface Labeled { val label: String }

enum class Backdrop(override val label: String, val colors: Pair<Color, Color>?) : Labeled {
    Reactive("Follows focused app", null),
    Graphite("Graphite", Color(0xFF78787F) to Color(0xFF26262C)),
    Aurora("Aurora", Color(0xFF1EC8A5) to Color(0xFF5B4BFF)),
    Dusk("Dusk", Color(0xFFFF646E) to Color(0xFF783CFF)),
    Ocean("Ocean", Color(0xFF0EA5E9) to Color(0xFF14288C)),
    Ember("Ember", Color(0xFFFF8A00) to Color(0xFFE52E71)),
    Night("Night sky", Color(0xFF3A5BC7) to Color(0xFF0B1433)),
    Black("Pure black", Color.Black to Color.Black),
}

enum class IconStyle(override val label: String) : Labeled { Colour("Original colour"), Mono("Monochrome") }
enum class CardSize(override val label: String, val columns: Int) : Labeled {
    Compact("Compact · 6 across", 6), Regular("Regular · 5 across", 5), Large("Large · 4 across", 4)
}
enum class DockSize(override val label: String, val count: Int) : Labeled {
    Four("4 apps", 4), Five("5 apps", 5), Six("6 apps", 6)
}
enum class NameMode(override val label: String) : Labeled { Always("Always"), Focused("Focused only") }
enum class ClockFormat(override val label: String) : Labeled { System("Follow TV"), H12("12-hour"), H24("24-hour") }
enum class Motion(override val label: String) : Labeled {
    Full("Full — every effect"), Balanced("Balanced"), Lightest("Lightest — fastest")
}
enum class SaverAfter(override val label: String, val minutes: Int) : Labeled {
    Off("Never", 0), M1("1 minute", 1), M2("2 minutes", 2), M5("5 minutes", 5),
    M10("10 minutes", 10), M15("15 minutes", 15), M30("30 minutes", 30)
}
enum class SaverStyle(override val label: String) : Labeled { Aurora("Aurora"), Clock("Clock"), Showcase("Showcase") }
/** What a colour key on the remote does while Riven is in front. */
enum class RemoteAction(override val label: String) : Labeled {
    AllApps("All apps"), Customize("Settings"), Inputs("Inputs"), Screensaver("Screensaver"),
    Settings("TV settings"), LiveTV("Live TV"), Wifi("Wi-Fi settings"), PlayStore("Play Store"),
    YouTube("YouTube"), Nothing("Do nothing"),
}

enum class OnOff(override val label: String) : Labeled { On("On"), Off("Off") }

data class Settings(
    val autoAdd: OnOff = OnOff.On,
    val names: NameMode = NameMode.Focused,
    val dock: DockSize = DockSize.Five,
    val stats: OnOff = OnOff.Off,
    val typeface: Typeface = Typeface.Signature,
    val size: CardSize = CardSize.Regular,
    val clock: ClockFormat = ClockFormat.System,
    val backdrop: Backdrop = Backdrop.Reactive,
    val icons: IconStyle = IconStyle.Colour,
    val motion: Motion = Motion.Full,
    val saverAfter: SaverAfter = SaverAfter.M5,
    val saverStyle: SaverStyle = SaverStyle.Aurora,
    val red: RemoteAction = RemoteAction.AllApps,
    val green: RemoteAction = RemoteAction.Customize,
    val yellow: RemoteAction = RemoteAction.Inputs,
    val blue: RemoteAction = RemoteAction.Screensaver,
)

/**
 * Everything the user can change, persisted in SharedPreferences and exposed as Compose state.
 * Shared by the launcher and the screensaver.
 */
object Store {
    private lateinit var prefs: SharedPreferences

    var settings by mutableStateOf(Settings()); private set
    /** Packages on the home row, in the user's order. */
    var home by mutableStateOf(listOf<String>()); private set
    var hidden by mutableStateOf(setOf<String>()); private set
    /** Installed since the user last opened them → "NEW" badge. */
    var fresh by mutableStateOf(setOf<String>()); private set
    var dismissed by mutableStateOf(setOf<String>()); private set
    /** Last remote key seen, for the key tester in Settings › Remote. */
    var lastKey by mutableStateOf(""); internal set
    /** True while a text field has focus, so number keys type instead of opening apps. */
    var typing = false

    /** Shown in the greeting: "Good evening, <name>". */
    var name by mutableStateOf(""); private set
    private var known = setOf<String>()
    private var firstRun = true

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("riven", Context.MODE_PRIVATE)
        firstRun = !prefs.contains("known")
        home = prefs.list("home")
        hidden = prefs.list("hidden").toSet()
        fresh = prefs.list("fresh").toSet()
        dismissed = prefs.list("dismissed").toSet()
        known = prefs.list("known").toSet()
        name = prefs.getString("name", "").orEmpty()
        val d = Settings()
        settings = Settings(
            autoAdd = prefs.enum("autoAdd", d.autoAdd),
            names = prefs.enum("names", d.names),
            dock = prefs.enum("dock", d.dock),
            stats = prefs.enum("stats", d.stats),
            typeface = prefs.enum("typeface", d.typeface),
            size = prefs.enum("size", d.size),
            clock = prefs.enum("clock", d.clock),
            backdrop = prefs.enum("backdrop", d.backdrop),
            icons = prefs.enum("icons", d.icons),
            motion = prefs.enum("motion", if (lowEnd(ctx)) Motion.Balanced else d.motion),
            saverAfter = prefs.enum("saverAfter", d.saverAfter),
            saverStyle = prefs.enum("saverStyle", d.saverStyle),
            red = prefs.enum("red", d.red), green = prefs.enum("green", d.green),
            yellow = prefs.enum("yellow", d.yellow), blue = prefs.enum("blue", d.blue),
        )
    }

    fun update(s: Settings) {
        settings = s
        prefs.edit()
            .putString("autoAdd", s.autoAdd.name).putString("names", s.names.name)
            .putString("dock", s.dock.name).putString("stats", s.stats.name).putString("typeface", s.typeface.name)
            .putString("size", s.size.name).putString("clock", s.clock.name)
            .putString("backdrop", s.backdrop.name).putString("icons", s.icons.name)
            .putString("motion", s.motion.name).putString("saverAfter", s.saverAfter.name)
            .putString("saverStyle", s.saverStyle.name)
            .putString("red", s.red.name).putString("green", s.green.name)
            .putString("yellow", s.yellow.name).putString("blue", s.blue.name)
            .apply()
    }

    /**
     * Called whenever the installed app list changes. On first run the home row is seeded
     * from [Catalog]; afterwards any newly installed app is appended (if auto-add is on)
     * and marked NEW. Uninstalled apps drop off.
     */
    fun reconcile(installed: List<AppEntry>) {
        val pkgs = installed.map { it.pkg }.toSet()
        if (pkgs.isEmpty()) return
        if (firstRun) {
            // Starter apps first, then everything else on the TV (remove any with Hold OK).
            home = (Catalog.tiles.mapNotNull { Apps.resolve(it, installed) } + installed.map { it.pkg }).distinct()
            firstRun = false
            prefs.edit().putBoolean("seededAll", true).apply()
        } else if (!prefs.getBoolean("seededAll", false)) {
            home = (home + installed.map { it.pkg }.filter { it !in hidden }).distinct()
            prefs.edit().putBoolean("seededAll", true).apply()
        } else {
            val added = pkgs - known
            if (added.isNotEmpty()) {
                fresh = fresh + added
                if (settings.autoAdd == OnOff.On) {
                    home = home + installed.filter { it.pkg in added && it.pkg !in hidden && it.pkg !in home }
                        .map { it.pkg }
                }
            }
        }
        home = home.filter { it in pkgs }
        fresh = fresh intersect pkgs
        known = pkgs
        save()
    }

    fun saveName(n: String) { name = n.take(24); prefs.edit().putString("name", name).apply() }
    fun opened(pkg: String) { if (pkg in fresh) { fresh = fresh - pkg; save() } }
    fun addHome(pkg: String) { if (pkg !in home) { home = home + pkg; save() } }
    fun removeHome(pkg: String) { home = home - pkg; save() }
    fun hide(pkg: String) { hidden = hidden + pkg; home = home - pkg; save() }
    fun unhide(pkg: String) { hidden = hidden - pkg; save() }
    fun dismiss(pkg: String) { dismissed = dismissed + pkg; save() }

    fun move(pkg: String, delta: Int) {
        val i = home.indexOf(pkg); val j = i + delta
        if (i < 0 || j !in home.indices) return
        home = home.toMutableList().apply { add(j, removeAt(i)) }
        save()
    }

    fun resetHome(installed: List<AppEntry>) {
        home = (Catalog.tiles.mapNotNull { Apps.resolve(it, installed) } + installed.map { it.pkg }.filter { it !in hidden }).distinct()
        dismissed = emptySet()
        save()
    }

    private fun save() {
        prefs.edit()
            .putString("home", home.joinToString(","))
            .putString("hidden", hidden.joinToString(","))
            .putString("fresh", fresh.joinToString(","))
            .putString("dismissed", dismissed.joinToString(","))
            .putString("known", known.joinToString(","))
            .apply()
    }

    /** Older / low-memory TVs (like 2019 Android TV 8 sets) get calmer motion by default. */
    private fun lowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return false
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || mi.totalMem < 2_300_000_000L || android.os.Build.VERSION.SDK_INT < 28
    }

    private fun SharedPreferences.list(key: String) =
        getString(key, "").orEmpty().split(",").filter { it.isNotBlank() }

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, def: E): E =
        getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
