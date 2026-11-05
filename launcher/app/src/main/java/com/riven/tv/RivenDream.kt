package com.riven.tv

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The system screensaver. On Google TV pick it with:
 *   adb shell settings put secure screensaver_components com.riven.tv/.RivenDream
 * (scripts/riven.sh screensaver does this and remembers the old value for restore).
 */
class RivenDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    private lateinit var repo: AppRepository

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        Store.init(this)
        repo = AppRepository(this, lifecycleScope)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        repo.refreshSoon(0)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@RivenDream)
            setViewTreeSavedStateRegistryOwner(this@RivenDream)
            setContent {
                RivenCanvas {
                    val byPkg = repo.apps.associateBy { it.pkg }
                    val showcase = Store.home.mapNotNull { byPkg[it] }.ifEmpty { repo.apps.filter { it.banner != null } }
                    Ambient(Store.settings.saverStyle, showcase)
                }
            }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}
