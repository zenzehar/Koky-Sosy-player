/*
 * Soky IPTV Pro cloud progress format.
 * GPL-3.0-or-later. This format never serialises provider URLs or credentials.
 */
package app.sokyiptv.sync.cloud

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class CloudEpisodeLocator(
    /** Hash of parent series provider ID and series name, no credentials or URLs. */
    val seriesFingerprint: String,
    /** Hash of the episode's provider ID within that series. */
    val episodeFingerprint: String,
    val season: Int,
    val episodeNumber: Int,
)

@Serializable
data class CloudWatchPoint(
    val profile: String,
    val mediaHash: String,
    val kind: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
    /** Optional credential-free portable keys for the same catalogue media on other TVs. */
    val aliases: List<String> = emptyList(),
    val episodeLocator: CloudEpisodeLocator? = null,
)

@Serializable
data class CloudWatchSnapshot(
    val version: Int = 2,
    val deviceId: String,
    val generatedAtMillis: Long,
    val positions: List<CloudWatchPoint>,
)

object CloudMediaKeys {
    private fun normalizedLabel(label: String): String =
        label.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Fingerprint for the SERIES parent of a saved episode. */
    fun seriesFingerprint(seriesId: String, seriesName: String): String? {
        val id = seriesId.trim()
        val name = normalizedLabel(seriesName)
        if (!id.matches(Regex("[0-9]{1,18}")) || name.length < 2) return null
        return digest("ep", "cloud-series-v1:$id:$name")
    }

    fun episodeFingerprint(seriesId: String, seriesName: String, episodeId: String): String? {
        val parent = seriesFingerprint(seriesId, seriesName) ?: return null
        val episode = episodeId.trim()
        if (!episode.matches(Regex("[0-9]{1,18}"))) return null
        return digest("ep", "cloud-episode-v1:$parent:$episode")
    }

    fun episodeLocator(
        seriesId: String, seriesName: String, episodeId: String,
        season: Int, number: Int,
    ): CloudEpisodeLocator? {
        if (season < 1 || number < 1) return null
        return CloudEpisodeLocator(
            seriesFingerprint(seriesId, seriesName) ?: return null,
            episodeFingerprint(seriesId, seriesName, episodeId) ?: return null,
            season, number,
        )
    }

    /**
     * Stable fallback when a provider serves the SAME catalogue from different portal URLs.
     * A numeric provider ID alone is unsafe (unrelated providers can reuse it), so include
     * the normalized catalogue title. Never upload the ID or title in plaintext.
     */
    fun catalogueAlias(kind: String, providerId: String, title: String): String? {
        if (kind != "movie" && kind != "ep") return null
        val id = providerId.trim()
        if (!id.matches(Regex("[0-9]{1,18}"))) return null
        val normalizedTitle = title.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (normalizedTitle.length < 2) return null
        return digest(kind, "catalogue-id-title-v1:$id:$normalizedTitle")
    }

    /** SHA-256 of media identity, never a plaintext URL or login credential. */
    private fun digest(kind: String, identifier: String): String {
        require(kind == "movie" || kind == "ep")
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((kind + "\u0000" + identifier).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Identifies old v1 cloud files for a safe, gradual migration. */
    fun legacyHash(kind: String, streamUrl: String): String = digest(kind, streamUrl)

    /**
     * Xtream links are generally /movie/username/password/stream-id.ext and
     * /series/username/password/episode-id.ext. The two credential segments
     * are different for each user/device; the server and stream ID are stable.
     * Strip ONLY for this known URL format. Never guess identities by title.
     *
     * Other URL formats retain their exact v1 hash, preventing false matches.
     */
    /** v0.1.11.20 identity, retained as an alias while migrating saved snapshots. */
    fun previousHash(kind: String, streamUrl: String): String =
        digest(kind, portableIdentity(kind, streamUrl))

    fun hash(kind: String, streamUrl: String): String =
        digest(kind, canonicalIdentity(kind, streamUrl))

    /**
     * Xtream provider IDs are numeric and stable even if one catalogue uses mp4
     * and another uses mkv. Keep the exact server, route and numeric media ID;
     * never fall back to title matching or normalize unrelated URL schemes.
     */
    private fun canonicalIdentity(kind: String, url: String): String {
        val identity = portableIdentity(kind, url)
        if (identity == url) return url
        val match = Regex("""^(https?://[^/]+)/(movie|series)/([0-9]+)\.[a-zA-Z0-9]{2,6}$""")
            .matchEntire(identity) ?: return identity
        return "${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}"
    }

    fun portableIdentity(kind: String, url: String): String {
        val pattern = Regex("""(?i)^(https?://[^/?#]+)/(movie|series)/[^/?#]+/[^/?#]+/([^/?#]+)$""")
        val match = pattern.matchEntire(url.trim()) ?: return url
        val route = match.groupValues[2].lowercase()
        if ((kind == "movie" && route != "movie") ||
            (kind == "ep" && route != "series")) return url
        val server = match.groupValues[1].lowercase()
        val streamId = match.groupValues[3]
        return "$server/$route/$streamId"
    }
}
