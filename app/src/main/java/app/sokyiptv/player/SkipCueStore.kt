/* GPL-3.0-or-later. Locally learned cues never contain stream URLs. */
package app.sokyiptv.player

import android.content.Context
import java.security.MessageDigest

class SkipCueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "soky_player_skip_cues_v1", Context.MODE_PRIVATE)
    private fun key(mediaKey: String) = MessageDigest.getInstance("SHA-256")
        .digest(mediaKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun read(mediaKey: String): SkipCues {
        val k = key(mediaKey)
        return SkipCues(prefs.getLong(k + "_intro_start", -1L),
            prefs.getLong(k + "_intro_end", -1L),
            prefs.getLong(k + "_credits_start", -1L))
    }
    fun markIntroStart(mediaKey: String, atMs: Long) {
        if (atMs !in 0L..900_000L) return
        val k = key(mediaKey)
        prefs.edit().putLong(k + "_intro_start", atMs)
            .putLong(k + "_intro_end", -1L).apply()
    }
    fun markIntroEnd(mediaKey: String, atMs: Long) {
        val k = key(mediaKey)
        val start = prefs.getLong(k + "_intro_start", -1L)
        if (start < 0 || atMs - start !in 8_000L..240_000L) return
        prefs.edit().putLong(k + "_intro_end", atMs).apply()
    }
    fun markCredits(mediaKey: String, atMs: Long, durationMs: Long) {
        if (durationMs < 60_000L || atMs < durationMs * 65 / 100 ||
            durationMs - atMs !in 8_000L..1_200_000L) return
        prefs.edit().putLong(key(mediaKey) + "_credits_start", atMs).apply()
    }
    fun fillFromChapters(mediaKey: String, found: SkipCues) {
        val existing = read(mediaKey)
        val k = key(mediaKey)
        val editor = prefs.edit()
        if (existing.introStartMs < 0L && found.hasIntro())
            editor.putLong(k + "_intro_start", found.introStartMs)
                .putLong(k + "_intro_end", found.introEndMs)
        if (existing.creditsStartMs < 0L && found.hasCredits())
            editor.putLong(k + "_credits_start", found.creditsStartMs)
        editor.apply()
    }
    fun clear(mediaKey: String) {
        val k = key(mediaKey)
        prefs.edit().remove(k + "_intro_start").remove(k + "_intro_end")
            .remove(k + "_credits_start").apply()
    }
}
