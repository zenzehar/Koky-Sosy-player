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

    @Test fun xtreamCredentialDifferencesProduceSameSafeMediaId() {
        val first = "http://provider.example:8080/series/user-one/password-one/76543.mp4"
        val second = "http://provider.example:8080/series/user-two/password-two/76543.mp4"
        assertEquals(CloudMediaKeys.hash("ep", first), CloudMediaKeys.hash("ep", second))
        assertNotEquals(CloudMediaKeys.legacyHash("ep", first), CloudMediaKeys.legacyHash("ep", second))
        assertNotEquals(
            CloudMediaKeys.hash("ep", first),
            CloudMediaKeys.hash("ep", "http://another-provider.example:8080/series/user-two/password-two/76543.mp4")
        )
        assertNotEquals(
            CloudMediaKeys.hash("ep", first),
            CloudMediaKeys.hash("ep", "http://provider.example:8080/series/user-two/password-two/76544.mp4")
        )
    }

    @Test fun unknownOrQueryBearingMediaRemainsExactToPreventWrongMatches() {
        val a = "http://provider.example/stream?stream_id=5&token=a"
        val b = "http://provider.example/stream?stream_id=5&token=b"
        assertNotEquals(CloudMediaKeys.hash("movie", a), CloudMediaKeys.hash("movie", b))
        val xtream = "http://provider.example/movie/user/pass/5.mp4"
        assertNotEquals(CloudMediaKeys.hash("movie", xtream), CloudMediaKeys.hash("movie", xtream + "?variant=other"))
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
