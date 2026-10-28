package app.sokyiptv.player

import android.content.Context
import java.security.MessageDigest

/** Exactly two user-defined timestamps per provider/series; no inferred segments. */
enum class SkipMark { INTRO_END, CREDITS_START }

data class ManualSeriesSkips(
    /** Target of Skip Intro, presented only in the first 10 seconds. */
    val introEndMs: Long = -1L,
    /** When to offer Skip Credits / Next Episode until playback ends. */
    val creditsStartMs: Long = -1L,
) {
    fun introValid() = introEndMs > 0L
    fun creditsValid() = creditsStartMs >= 0L

    /** Show immediately at video start for at most ten seconds, never seek backwards. */
    fun showIntro(nowMs: Long) =
        introValid() && nowMs >= 0L && nowMs < INTRO_PROMPT_MS && nowMs < introEndMs

    /**
     * Shared series marks may be outside the duration of another episode.
     * Never seek to the outro instead of the intro, or seek beyond the media.
     */
    fun safeIntroTarget(durationMs: Long): Long? =
        introEndMs.takeIf {
            introValid() && durationMs > 0L && it < durationMs - 2_000L &&
                (!creditsValid() || it < creditsStartMs)
        }

    /** Never offer an outro before the intro mark or beyond the episode. */
    fun showCredits(nowMs: Long, durationMs: Long): Boolean =
        creditsValid() && durationMs > 0L && creditsStartMs < durationMs &&
            (!introValid() || creditsStartMs > introEndMs) &&
            nowMs >= creditsStartMs && nowMs < durationMs

    /** Legacy one-argument predicate retained for existing callers. */
    fun showCredits(nowMs: Long): Boolean =
        creditsValid() && (!introValid() || creditsStartMs > introEndMs) &&
            nowMs >= creditsStartMs

    fun mark(mark: SkipMark, atMs: Long): ManualSeriesSkips {
        val at = atMs.coerceAtLeast(0L)
        return when (mark) {
            SkipMark.INTRO_END -> copy(introEndMs = at)
            SkipMark.CREDITS_START -> copy(creditsStartMs = at)
        }
    }

    fun clearIntro() = copy(introEndMs = -1L)
    fun clearCredits() = copy(creditsStartMs = -1L)

    fun display(): String =
        "Intro ends: " + clock(introEndMs) + " | Credits/outro starts: " + clock(creditsStartMs)

    companion object {
        const val INTRO_PROMPT_MS = 10_000L
        fun clock(ms: Long): String {
            if (ms < 0L) return "unset"
            val seconds = ms / 1000L
            return if (seconds >= 3600L) "%d:%02d:%02d".format(
                seconds / 3600L, seconds % 3600L / 60L, seconds % 60L
            ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
        }
    }
}

/**
 * Single storage namespace, preserving existing v64-v66 series markers:
 * the previous 4-item record was [intro start, intro end, credits start, credits end].
 * Old intro end + credits start are retained; unused start/end are discarded.
 * New records store ONLY the two meaningful timestamps.
 */
class ManualSeriesSkipStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "soky_series_user_markers_v1", Context.MODE_PRIVATE
    )
    private fun key(series: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(series.toByteArray(Charsets.UTF_8))
        return "series_" + digest.joinToString("") { "%02x".format(it) }
    }
    fun read(series: String?): ManualSeriesSkips {
        if (series.isNullOrBlank()) return ManualSeriesSkips()
        val parts = prefs.getString(key(series), null)?.split(",")
            ?: return ManualSeriesSkips()
        val values = parts.map { it.toLongOrNull() ?: return ManualSeriesSkips() }
        return when (values.size) {
            2 -> ManualSeriesSkips(values[0], values[1])
            4 -> ManualSeriesSkips(values[1], values[2])
            else -> ManualSeriesSkips()
        }
    }
    fun write(series: String?, marks: ManualSeriesSkips) {
        if (series.isNullOrBlank()) return
        prefs.edit().putString(key(series), listOf(
            marks.introEndMs, marks.creditsStartMs
        ).joinToString(",")).apply()
    }
}
