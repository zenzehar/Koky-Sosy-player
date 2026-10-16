/* GPL-3.0-or-later: bounded network buffering tuned for constrained Android TV hardware. */
package app.opentv.player

/**
 * Network buffer *duration*, not a reservation of RAM or a promise of bytes downloaded.
 * ExoPlayer also has an independent allocator ceiling; codec memory is separate.
 */
enum class PlaybackBufferPreset(
    val vlcNetworkCacheMs: Int,
    val media3MinBufferMs: Int,
    val media3MaxBufferMs: Int,
    val media3StartMs: Int,
    val media3RebufferMs: Int,
    val media3TargetBufferBytes: Int,
) {
    FAST(900, 4_000, 12_000, 350, 850, 12 * 1024 * 1024),
    BALANCED(1_800, 8_000, 30_000, 900, 1_700, 20 * 1024 * 1024),
    STABLE(3_500, 12_000, 45_000, 1_600, 3_000, 32 * 1024 * 1024),
    LARGE(6_500, 18_000, 60_000, 2_600, 4_500, 48 * 1024 * 1024);

    init {
        require(media3MinBufferMs <= media3MaxBufferMs)
        require(media3StartMs <= media3MinBufferMs)
        require(media3RebufferMs <= media3MinBufferMs)
        require(vlcNetworkCacheMs in 250..10_000)
    }

    companion object {
        fun fromSaved(value: String?): PlaybackBufferPreset =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
