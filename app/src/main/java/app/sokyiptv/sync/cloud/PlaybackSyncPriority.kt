package app.sokyiptv.sync.cloud

import java.util.concurrent.atomic.AtomicInteger

/**
 * Playback always has priority over optional Google Drive traffic.
 * Guard reference counts because a TV can transition between video screens.
 *
 * Never touches the VLC/Media3 engine or media URLs.
 */
internal object PlaybackSyncPriority {
    private val activeScreens = AtomicInteger(0)

    val active: Boolean get() = activeScreens.get() > 0

    /** Returns true on first active playback screen. */
    fun begin(): Boolean = activeScreens.incrementAndGet() == 1

    /** Returns true only on an actual transition from one player to none. */
    fun end(): Boolean {
        while (true) {
            val old = activeScreens.get()
            if (old <= 0) return false
            if (activeScreens.compareAndSet(old, old - 1)) return old == 1
        }
    }
}
