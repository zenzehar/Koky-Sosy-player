/* GPL-3.0-or-later. Small, local, device-bound learned video signatures. */
package app.sokyiptv.player

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** No URLs, provider tokens, account names, cloud sync or network access. */
class PassiveSkipMemory(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "soky_passive_skip_signatures_v1", Context.MODE_PRIVATE)

    private fun id(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun history(group: String, exceptEpisode: String): List<EpisodeScenes> {
        val entries = prefs.getString("series_" + id(group), "").orEmpty()
        return entries.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { record ->
                runCatching {
                    val parts = record.split(';')
                    if (parts.size != 3) return@runCatching null
                    val episode = parts[0]
                    if (episode == id(exceptEpisode)) return@runCatching null
                    val total = parts[1].toLongOrNull() ?: return@runCatching null
                    val samples = parts[2].split(',')
                        .take(350).mapNotNull { item ->
                            val fields = item.split(':')
                            if (fields.size != 3) return@mapNotNull null
                            val time = fields[0].toLongOrNull() ?: return@mapNotNull null
                            val hash = fields[1].toULongOrNull(16)?.toLong()
                                ?: return@mapNotNull null
                            val credits = fields[2] == "1"
                            SceneSample(time, hash, credits)
                        }
                    EpisodeScenes(episode, total, samples)
                }.getOrNull()
            }.filterNotNull().takeLast(8).toList()
    }

    fun persist(group: String, episodeKey: String, totalMs: Long, samples: List<SceneSample>) {
        // Never store invalid samples or sequences that were mostly buffering.
        if (group.isBlank() || samples.size < 8) return
        val key = "series_" + id(group)
        val self = id(episodeKey)
        val old = prefs.getString(key, "").orEmpty().lineSequence().filter { it.isNotBlank() }
            .filterNot { it.startsWith(self + ";") }.takeLast(7).toMutableList()
        val filtered = samples.distinctBy { it.atMs / 2_500L }
            .sortedBy { it.atMs }.take(350)
        val content = filtered.joinToString(",") { s ->
            s.atMs.toString() + ":" + s.hash.toULong().toString(16) + ":" +
                if (s.creditsLike) "1" else "0"
        }
        old.add(self + ";" + totalMs.coerceAtLeast(0L) + ";" + content)
        prefs.edit().putString(key, old.joinToString("\n")).apply()
    }
}
