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
    /** The stable key is kept on the device; only its SHA-256 digest leaves it. */
    fun hash(kind: String, streamUrl: String): String {
        require(kind == "movie" || kind == "ep")
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((kind + "\u0000" + streamUrl).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
