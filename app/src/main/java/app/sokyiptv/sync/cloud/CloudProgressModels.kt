/*
 * Soky IPTV Pro cloud progress format.
 * GPL-3.0-or-later. This format never serialises provider URLs or credentials.
 */
package app.sokyiptv.sync.cloud

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class CloudWatchPoint(
    val profile: String,
    val mediaHash: String,
    val kind: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
data class CloudWatchSnapshot(
    val version: Int = 1,
    val deviceId: String,
    val generatedAtMillis: Long,
    val positions: List<CloudWatchPoint>,
)

object CloudMediaKeys {
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
    fun hash(kind: String, streamUrl: String): String =
        digest(kind, portableIdentity(kind, streamUrl))

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
