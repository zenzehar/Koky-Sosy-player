package app.sokyiptv.player

import android.content.Context
import java.security.MessageDigest

/** Explicit episode-time markers, persisted per provider and series, never auto-generated. */
enum class SkipMark { INTRO_START, INTRO_END, CREDITS_START, CREDITS_END }

data class ManualSeriesSkips(
    val introStartMs: Long = -1L,
    val introEndMs: Long = -1L,
    val creditsStartMs: Long = -1L,
    val creditsEndMs: Long = -1L,
) {
    fun introValid() = introStartMs >= 0L && introEndMs > introStartMs + 999L
    fun creditsValid() = creditsStartMs >= 0L && creditsEndMs > creditsStartMs + 999L
    fun showIntro(nowMs: Long) =
        introValid() && nowMs >= introStartMs && nowMs < introEndMs - 2_000L
    fun showCredits(nowMs: Long) =
        creditsValid() && nowMs >= creditsStartMs && nowMs < creditsEndMs
    fun mark(mark: SkipMark, atMs: Long): ManualSeriesSkips {
        val at = atMs.coerceAtLeast(0L)
        return when (mark) {
            SkipMark.INTRO_START -> copy(introStartMs = at)
            SkipMark.INTRO_END -> copy(introEndMs = at)
            SkipMark.CREDITS_START -> copy(creditsStartMs = at)
            SkipMark.CREDITS_END -> copy(creditsEndMs = at)
        }
    }
    fun clearIntro() = copy(introStartMs = -1L, introEndMs = -1L)
    fun clearCredits() = copy(creditsStartMs = -1L, creditsEndMs = -1L)
    fun display(): String = "Intro: " + range(introStartMs, introEndMs) +
        " | Credits: " + range(creditsStartMs, creditsEndMs)
    private fun range(a: Long, b: Long): String {
        if (a < 0L && b < 0L) return "not set"
        return clock(a) + " – " + clock(b) +
            if (a >= 0L && b > a + 999L) "" else " (incomplete)"
    }
    companion object {
        fun clock(ms: Long): String {
            if (ms < 0L) return "unset"
            val seconds = ms / 1000L
            return if (seconds >= 3600L) "%d:%02d:%02d".format(
                seconds / 3600L, seconds % 3600L / 60L, seconds % 60L
            ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
        }
    }
}

/** User-entered markers only. The raw title, provider URL and credentials are never stored. */
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
        val value = prefs.getString(key(series), null)?.split(",")
            ?: return ManualSeriesSkips()
        if (value.size != 4) return ManualSeriesSkips()
        val values = value.map { it.toLongOrNull() ?: return ManualSeriesSkips() }
        return ManualSeriesSkips(values[0], values[1], values[2], values[3])
    }
    fun write(series: String?, marks: ManualSeriesSkips) {
        if (series.isNullOrBlank()) return
        prefs.edit().putString(key(series), listOf(
            marks.introStartMs, marks.introEndMs,
            marks.creditsStartMs, marks.creditsEndMs
        ).joinToString(",")).apply()
    }
}
