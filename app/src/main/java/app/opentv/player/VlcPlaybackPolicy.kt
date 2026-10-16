/* GPL-3.0-or-later: deterministic embedded LibVLC compatibility policy. */
package app.opentv.player

/**
 * NATIVE deliberately mirrors standalone VLC: no app-specific HTTP header,
 * forced hardware decoder, reconnect flag or cache override. The LibVLC defaults
 * handle media choice. An explicitly configured per-provider User-Agent is still honoured.
 *
 * TUNED retains the legacy Koky & Sosy options and applies the selected VLC cache
 * duration. It exists as an A/B setting for providers that require special headers.
 */
enum class VlcPlaybackMode {
    NATIVE, TUNED;

    companion object {
        fun fromSaved(raw: String?): VlcPlaybackMode =
            entries.firstOrNull { it.name == raw } ?: NATIVE
    }
}

object VlcPlaybackPolicy {
    fun engineArguments(mode: VlcPlaybackMode, networkCacheMillis: Int): ArrayList<String> =
        arrayListOf<String>().apply {
            add("--no-video-title-show")
            if (mode == VlcPlaybackMode.TUNED) {
                add("--network-caching=${networkCacheMillis.coerceIn(500, 15_000)}")
            }
        }

    fun mediaArguments(
        mode: VlcPlaybackMode,
        userAgent: String,
        defaultUserAgent: String,
    ): List<String> = buildList {
        // Keep a custom UA configured by the user; only omit the generic app default.
        if (userAgent.isNotBlank() &&
            (mode == VlcPlaybackMode.TUNED || userAgent != defaultUserAgent)
        ) add(":http-user-agent=$userAgent")
        if (mode == VlcPlaybackMode.TUNED) add(":http-reconnect")
    }

    fun overrideHardwareDecoder(mode: VlcPlaybackMode): Boolean =
        mode == VlcPlaybackMode.TUNED
}
