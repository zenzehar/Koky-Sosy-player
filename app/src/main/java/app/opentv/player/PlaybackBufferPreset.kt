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
    FAST(1_500, 5_000, 15_000, 600, 1_200, 12 * 1024 * 1024),
    BALANCED(5_000, 12_000, 35_000, 1_800, 4_500, 32 * 1024 * 1024),
    STABLE(8_000, 18_000, 50_000, 2_800, 7_000, 40 * 1024 * 1024),
    LARGE(12_000, 25_000, 65_000, 4_000, 10_000, 48 * 1024 * 1024);

    init {
        require(media3MinBufferMs <= media3MaxBufferMs)
        require(media3StartMs <= media3MinBufferMs)
        require(media3RebufferMs <= media3MinBufferMs)
        require(vlcNetworkCacheMs in 500..15_000)
    }

    companion object {
        fun fromSaved(value: String?): PlaybackBufferPreset =
            entries.firstOrNull { it.name == value } ?: BALANCED
    }
}
