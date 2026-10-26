/* GPL-3.0-or-later: truthful episode runtime display. */
package app.sokyiptv.ui.vod

/** A provider runtime is metadata. A saved playback length is only an estimate
 * for the *same* episode, never evidence about other episodes in a season.
 * Neither value is used to seek or to decide completion. */
internal object EpisodeRuntimeLabel {
    fun fromSources(providerSeconds: Int?, savedPlaybackMs: Long?): String {
        val provider = providerSeconds?.takeIf { it in 60..86_400 }
        if (provider != null) return "${provider / 60}m"
        val observed = savedPlaybackMs?.takeIf { it in 60_000L..86_400_000L }
        if (observed != null) return "≈${(observed + 30_000L) / 60_000L}m"
        return "Duration unavailable"
    }
}
