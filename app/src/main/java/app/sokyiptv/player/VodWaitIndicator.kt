/* GPL-3.0-or-later: non-invasive VOD wait-state presentation. */
package app.sokyiptv.player

/** Network fill and decoder-clock stalls are distinct: never mislabel the latter as VLC/network. */
internal object VodWaitIndicator {
    enum class Kind { NONE, BUFFERING, STALLED }

    fun show(
        ready: Boolean,
        failed: Boolean,
        ended: Boolean,
        paused: Boolean,
        suspended: Boolean,
        scrubbing: Boolean,
        state: VlcPlaybackHealth.State,
    ): Kind {
        if (!ready || failed || ended || paused || suspended || scrubbing) return Kind.NONE
        return when (state) {
            VlcPlaybackHealth.State.BUFFERING -> Kind.BUFFERING
            VlcPlaybackHealth.State.STALLED -> Kind.STALLED
            else -> Kind.NONE
        }
    }
}
