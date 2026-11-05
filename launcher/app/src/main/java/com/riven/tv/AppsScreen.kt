package com.riven.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/* ───────────── All apps ───────────── */

@Composable
fun AppsScreen(
    repo: AppRepository,
    focusSignal: Int,
    grabOnEnter: Boolean,
    onColor: (Color) -> Unit,
    onOptions: (RowItem) -> Unit,
    onFocusedItem: (RowItem?) -> Unit,
) {
    val ctx = LocalContext.current
    val apps = repo.apps.filter { it.pkg !in Store.hidden }
    val first = remember { FocusRequester() }
    val enteredWith = remember { focusSignal }
    LaunchedEffect(focusSignal) {
        if (focusSignal == enteredWith && !grabOnEnter) return@LaunchedEffect   // opened from the tab bar
        withFrameNanos { }; runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(top = 86.dp)) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            BasicText("All apps", style = riven(32.sp, FontWeight.SemiBold, (-0.04).em))
            Spacer(Modifier.height(6.dp))
            BasicText(
                "${apps.size} apps · hold OK to add to Home, hide or uninstall",
                style = riven(9.sp, color = Ink.ink2),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 26.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(apps, key = { it.pkg }) { app ->
                AppCard(
                    app = app,
                    width = androidx.compose.ui.unit.Dp.Unspecified,
                    modifier = if (app == apps.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                    isNew = app.pkg in Store.fresh,
                    onFocus = { onColor(app.color); onFocusedItem(RowItem.App(app)) },
                    onBlur = { onFocusedItem(null) },
                    onLongClick = { onOptions(RowItem.App(app)) },
                    onClick = { Apps.open(ctx, app.pkg) },
                )
            }
        }
    }
}

/* ───────────── Options sheet (hold OK) ───────────── */

data class SheetAction(val label: String, val hint: String = "", val danger: Boolean = false, val run: () -> Unit)

@Composable
fun OptionsSheet(target: RowItem, fromHome: Boolean, onMove: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val actions: List<SheetAction> = when (target) {
        is RowItem.App -> {
            val pkg = target.app.pkg
            val onHome = pkg in Store.home
            listOfNotNull(
                SheetAction("Open", "OK") { Apps.open(ctx, pkg) },
                if (onHome && fromHome) SheetAction("Move", "◀ ▶") { onMove(pkg) } else null,
                if (onHome) SheetAction("Remove from Home") { Store.removeHome(pkg) }
                else SheetAction("Add to Home") { Store.addHome(pkg); Apps.toast(ctx, "Added to Home") },
                SheetAction("Hide app") { Store.hide(pkg); Apps.toast(ctx, "Hidden — undo in Settings › Hidden apps") },
                SheetAction("App info") { Apps.appInfo(ctx, pkg) },
                SheetAction("Uninstall", danger = true) { Apps.uninstall(ctx, pkg) },
            )
        }
        is RowItem.Get -> listOf(
            SheetAction("Get from Play Store", "OK") { Apps.openStore(ctx, target.spec.packages.first()) },
            SheetAction("Don't suggest this") { Store.dismiss(target.spec.title) },
        )
        is RowItem.Installing -> emptyList()
    }
    ActionSheet(
        title = when (target) { is RowItem.App -> target.app.label; is RowItem.Get -> target.spec.title; else -> "" },
        subtitle = if (target is RowItem.App && target.app.pkg in Store.home) "On Home" else "Not on Home",
        art = { if (target is RowItem.App) AppArt(target.app) else Box(Modifier.fillMaxSize().background(Ink.glassHi)) },
        actions = actions,
        onClose = onClose,
    )
}

/** Inputs: HDMI ports under the names set in the TV's settings, AV, Live TV. */
@Composable
fun InputsSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val inputs = remember { Apps.inputs(ctx) }
    ActionSheet(
        title = "Inputs",
        subtitle = "Switch to a connected device",
        art = { Box(Modifier.fillMaxSize().background(Ink.glassHi), contentAlignment = Alignment.Center) { InputsGlyph(Ink.ink) } },
        actions = inputs.map { SheetAction(it.label, run = it.open) },
        onClose = onClose,
    )
}

/** Two ways to make the remote's Home button open Riven. */
@Composable
fun SetHomeSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val actions = buildList {
        if (BuildConfig.HOME_SERVICE) add(SheetAction("Turn on “Riven Home button”", "Best") { RivenKeyService.openSettings(ctx) })
        add(SheetAction("Choose Riven as Android's Home app") {
            Apps.toast(ctx, "If Android asks which Home app to use, choose Riven, then Always")
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
    ActionSheet(
        title = "Set as Home",
        subtitle = if (BuildConfig.HOME_SERVICE) "Accessibility › Riven Home button › On" else "Pick Riven, then Always",
        art = { Box(Modifier.fillMaxSize().background(Ink.glassHi), contentAlignment = Alignment.Center) { HomeGlyph(Ink.ink) } },
        actions = actions,
        onClose = onClose,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ActionSheet(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    actions: List<SheetAction>,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .padding(end = 48.dp)
                .width(262.dp)
                .glass(RoundedCornerShape(18.dp), Color(0xFF1C1C22).copy(alpha = 0.82f))
                .padding(17.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }   // keep focus inside the sheet
                .focusGroup(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(66.dp).height(37.dp).graphicsLayer { shape = RoundedCornerShape(8.dp); clip = true }) { art() }
                Spacer(Modifier.width(10.dp))
                Column {
                    BasicText(title, style = riven(15.sp, FontWeight.SemiBold, (-0.02).em), maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    BasicText(subtitle, style = riven(8.sp, color = Ink.ink2))
                }
            }
            Spacer(Modifier.height(13.dp))
            actions.forEachIndexed { i, a ->
                SheetRow(a, if (i == 0) Modifier.focusRequester(first) else Modifier) { onClose(); a.run() }
            }
        }
    }
}

@Composable
private fun SheetRow(a: SheetAction, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val t by animateFloatAsState(if (focused) 1f else 0f, tween(220, easing = RivenEase), label = "row")
    val fg = if (a.danger) lerp(Ink.danger, Color(0xFFB00020), t) else lerp(Ink.ink, Ink.onInk, t)
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { val s = 1f + 0.02f * t; scaleX = s; scaleY = s }
            .background(Ink.ink.copy(alpha = t), RoundedCornerShape(9.dp))
            .tvClickable(interaction, null, onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(a.label, style = riven(10.5.sp, color = fg))
        BasicText(a.hint, style = riven(8.sp, color = fg.copy(alpha = 0.5f)))
    }
}
