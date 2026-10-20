package app.sokyiptv.sync.cloud

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProgressModelsTest {
    @Test fun mediaKeysMatchOnlyForIdenticalStreamsAndKind() {
        val url = "http://example.test/movie/private-user/private-password/123.mp4"
        val same = CloudMediaKeys.hash("movie", url)
        assertEquals(same, CloudMediaKeys.hash("movie", url))
        assertNotEquals(same, CloudMediaKeys.hash("ep", url))
        assertNotEquals(same, CloudMediaKeys.hash("movie", url + "?change=1"))
        assertEquals(64, same.length)
        assertTrue(same.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun cloudPayloadContainsNoProviderCredentialsOrUrls() {
        val point = CloudWatchPoint(
            profile = "Me",
            mediaHash = CloudMediaKeys.hash("movie", "https://panel.test/movie/login/password/7"),
            kind = "movie",
            positionMillis = 42000,
            durationMillis = 100000,
            updatedAtMillis = 1000,
        )
        val encoded = Json.encodeToString(CloudWatchSnapshot(
            deviceId = "test-device", generatedAtMillis = 1001, positions = listOf(point),
        ))
        assertFalse(encoded.contains("panel.test"))
        assertFalse(encoded.contains("login/password"))
        assertFalse(encoded.contains("streamUrl"))
        val decoded = Json.decodeFromString<CloudWatchSnapshot>(encoded)
        assertEquals(point, decoded.positions.single())
    }
}
