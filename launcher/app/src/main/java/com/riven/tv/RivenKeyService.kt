package com.riven.tv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional: when switched on in Settings › Accessibility, the remote's Home button opens Riven
 * even while the stock launcher is still the system Home (no ADB needed). The Settings/dashboard
 * button (keycode 83 on Thomson remotes) opens TV settings. Every other key passes through.
 */
class RivenKeyService : AccessibilityService() {

    /** Packages of the TV's other Home screens (stock launcher etc.). */
    private var stockHomes: Set<String> = emptySet()
    private var lastBounce = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        stockHomes = otherHomes(this)
    }

    /**
     * Second line of defence: some TVs handle the Home key before accessibility services see it.
     * If the stock home screen still comes to the front, open Riven straight over it.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in stockHomes || isDefaultHome(this)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBounce < 800) return
        lastBounce = now
        openRiven()
    }

    private fun openRiven() = startActivity(
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    )

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                if (event.action == KeyEvent.ACTION_UP) openRiven()
                return true
            }
            KeyEvent.KEYCODE_NOTIFICATION -> {
                if (event.action == KeyEvent.ACTION_UP) Apps.openSettings(this)
                return true
            }
        }
        return false
    }

    override fun onInterrupt() = Unit

    companion object {
        fun otherHomes(ctx: Context): Set<String> {
            @Suppress("DEPRECATION")
            return ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
                .filter { it != ctx.packageName && it != "android" && it != "com.android.settings" && it != "com.android.tv.settings" }
                .toSet()
        }

        fun component(ctx: Context) = ComponentName(ctx, RivenKeyService::class.java)

        fun isOn(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
        }

        /** True when the system already sends Home to Riven (e.g. after ./riven.sh replace-stock). */
        fun isDefaultHome(ctx: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val ri = ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            return ri?.activityInfo?.packageName == ctx.packageName
        }

        /**
         * Full edition: open Accessibility to switch on the Home-button service.
         * Lite edition: ask Android which app should be Home (pick Riven → Always).
         */
        fun openSettings(ctx: Context) {
            if (BuildConfig.HOME_SERVICE) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Apps.toast(ctx, "Open Settings › Accessibility › Riven Home button") }
                return
            }
            val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (homeSettings.resolveActivity(ctx.packageManager) != null && runCatching { ctx.startActivity(homeSettings) }.isSuccess) return
            Apps.toast(ctx, "If Android asks which Home app to use, choose Riven, then Always")
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
