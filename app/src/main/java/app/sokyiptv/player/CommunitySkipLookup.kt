/*
 * GPL-3.0-or-later. SponsorBlock-like FIRST-EPISODE community timestamp lookup.
 * Read-only, optional network metadata: no stream, provider URL, password or
 * profile data is sent. Responses without a confident exact title/episode match
 * never cause a jump. All matching is confined to this episode and player.
 */
package app.sokyiptv.player

import android.content.Context
import app.sokyiptv.data.model.Episode
import app.sokyiptv.data.parser.VodTitleCleaner
import app.sokyiptv.data.remote.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class CommunitySegment(
    val startMs: Long,
    val endMs: Long,
    val confidence: Double,
    val source: String,
)
data class CommunitySkipResult(
    val intro: CommunitySegment? = null,
    val credits: CommunitySegment? = null,
)

/** A completely separate, 5s-capped HTTP client; never contends with IPTV video. */
class CommunitySkipLookup(http: OkHttpClient) {
    private val client = http.newBuilder()
        .callTimeout(5L, TimeUnit.SECONDS)
        .connectTimeout(3L, TimeUnit.SECONDS)
        .readTimeout(4L, TimeUnit.SECONDS)
        .build()

    suspend fun findForEpisode(
        imdbId: String?,
        seriesName: String,
        seriesYear: Int?,
        season: Int,
        episode: Int,
        durationMs: Long,
    ): CommunitySkipResult = withContext(Dispatchers.IO) {
        if (season < 0 || episode < 1) return@withContext CommunitySkipResult()
        val id = imdbId?.takeIf { it.matches(Regex("tt[0-9]{6,12}")) }
            ?: findByExactTitle(seriesName, seriesYear)
            ?: return@withContext CommunitySkipResult()
        val skipdb = get("https://api.skipdb.tv/api/segments",
            mapOf("imdb_id" to id, "season" to season.toString(),
                "episode" to episode.toString()) +
                if (durationMs > 60_000L) mapOf(
                    "duration" to (durationMs / 1_000L).toString(),
                    "adjust" to "conservative") else emptyMap())
        val community = skipdb?.optJSONObject("segments")
        var opening = parseSkipDb(community?.optJSONObject("intro"), false, durationMs)
        var ending = parseSkipDb(community?.optJSONObject("outro"), true, durationMs)
        if (opening == null || ending == null) {
            // Independent community provider: read-only fallback. Missing/low
            // confidence data stays missing rather than inventing a fixed length.
            val introdb = get("https://api.introdb.app/segments",
                mapOf("imdb_id" to id, "season" to season.toString(),
                    "episode" to episode.toString()))
            if (introdb != null &&
                introdb.optString("imdb_id") == id &&
                introdb.optInt("season", -1) == season &&
                introdb.optInt("episode", -1) == episode) {
                if (opening == null && !introdb.optBoolean("no_intro", false))
                    opening = parseIntroDb(introdb.optJSONObject("intro"), false, durationMs)
                if (ending == null && !introdb.optBoolean("no_outro", false))
                    ending = parseIntroDb(introdb.optJSONObject("outro"), true, durationMs)
            }
        }
        CommunitySkipResult(opening, ending)
    }

    private fun get(base: String, params: Map<String, String>): JSONObject? {
        return runCatching {
            val builder = base.toHttpUrl().newBuilder()
            for ((key, value) in params) builder.addQueryParameter(key, value)
            val request = Request.Builder().get().url(builder.build())
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                // Bounded response size, including when Content-Length is omitted.
                val bytes = java.io.ByteArrayOutputStream()
                val input = body.byteStream()
                val chunk = ByteArray(2048)
                while (bytes.size() <= 65_536) {
                    val count = input.read(chunk)
                    if (count <= 0) break
                    bytes.write(chunk, 0, count)
                }
                if (bytes.size() > 65_536) null
                else JSONObject(bytes.toString(Charsets.UTF_8.name()))
            }
        }.getOrNull()
    }

    private fun findByExactTitle(title: String, year: Int?): String? {
        val cleaned = VodTitleCleaner.clean(title)
            .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "").trim()
        val normalized = simplify(cleaned)
        if (normalized.length !in 3..75) return null
        val results = get("https://api.skipdb.tv/api/titles/search",
            mapOf("q" to cleaned)) ?: return null
        val all = mutableListOf<JSONObject>()
        for (key in listOf("local", "results")) {
            val array: JSONArray = results.optJSONArray(key) ?: continue
            for (i in 0 until minOf(array.length(), 25)) {
                array.optJSONObject(i)?.let { all.add(it) }
            }
        }
        val matching = all.filter { row ->
            val rowName = row.optString("name").ifBlank {
                row.optString("title")
            }
            val rowYear = row.optInt("year", 0)
            simplify(rowName) == normalized && (year == null || rowYear == 0 ||
                kotlin.math.abs(rowYear - year) <= 1) &&
                row.optString("media_type").let { it.isBlank() || it == "series" || it == "tv" }
        }.mapNotNull { row ->
            row.optString("imdb_id").ifBlank { row.optString("imdbId") }
                .takeIf { it.matches(Regex("tt[0-9]{6,12}")) }
        }.distinct()
        // Ambiguous names are not safe for episode-specific seeking.
        return matching.singleOrNull()
    }

    private fun simplify(raw: String): String = raw.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    private fun parseSkipDb(o: JSONObject?, outro: Boolean, duration: Long): CommunitySegment? {
        o ?: return null
        if (o.optString("match") == "out-of-range") return null
        val confidence = o.optDouble("confidence", 0.0)
        // Untested release variants need higher agreement than exact/shifted.
        val required = if (o.optString("match") == "agnostic") .85 else .70
        if (!confidence.isFinite() || confidence < required) return null
        return checkSegment(o.optLong("start_ms", -1L),
            o.optLong("end_ms", -1L), confidence, "SkipDB", outro, duration)
    }

    private fun parseIntroDb(o: JSONObject?, outro: Boolean, duration: Long): CommunitySegment? {
        o ?: return null
        val confidence = o.optDouble("confidence", 0.0)
        if (!confidence.isFinite() || confidence < .80) return null
        return checkSegment(o.optLong("start_ms", -1L),
            o.optLong("end_ms", -1L), confidence, "IntroDB", outro, duration)
    }

    internal fun checkSegment(
        start: Long, end: Long, confidence: Double, source: String,
        outro: Boolean, duration: Long,
    ): CommunitySegment? {
        if (start < 0L || end <= start || end - start < 5_000L) return null
        if (outro) {
            if (end - start > 900_000L) return null
            if (duration > 60_000L && (start > duration ||
                    start < duration * 60L / 100L)) return null
        } else {
            if (start > 480_000L || end > 510_000L || end - start > 300_000L)
                return null
            if (duration > 60_000L && end > duration - 15_000L) return null
        }
        return CommunitySegment(start, end, confidence, source)
    }
}
