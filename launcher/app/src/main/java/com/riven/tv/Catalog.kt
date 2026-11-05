package com.riven.tv

/**
 * Apps Riven puts on the home row the first time it runs. After that the user owns the
 * order (Hold OK → Move / Remove, or Customize → Reset home layout).
 *
 * [packages] are tried in order; if none is installed, any app whose name contains one of
 * [keywords] is used. Entries that aren't installed show as a "Get" card that opens the
 * Play Store; once installed, the app lands on the home row by itself.
 */
data class TileSpec(
    val title: String,
    val packages: List<String>,
    val keywords: List<String> = emptyList(),
)

object Catalog {
    val tiles = listOf(
        TileSpec("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube"), listOf("youtube")),
        TileSpec("JioHotstar", listOf("in.startv.hotstar"), listOf("hotstar")),
        TileSpec("JioTV+", listOf("com.jio.media.jiotvplus"), listOf("jiotv")),
        // TODO: the two Tamil channel apps — package is the ?id=… part of the Play Store URL.
        // TileSpec("Tamil One", listOf("com.example.tamil1")),
        // TileSpec("Tamil Two", listOf("com.example.tamil2")),
        TileSpec("aha", emptyList(), listOf("aha")),
        TileSpec("Sun NXT", emptyList(), listOf("sun nxt")),
        TileSpec("Play Store", listOf("com.android.vending")),
    )

    fun greeting(hour: Int) = when {
        hour < 5 -> "Late night"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    const val AUTHOR = "Johnbosco J"
    const val STUDIO = "Riven"
}
