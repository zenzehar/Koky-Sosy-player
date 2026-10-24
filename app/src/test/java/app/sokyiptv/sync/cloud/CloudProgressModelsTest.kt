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

    @Test fun numericXtreamIdMatchesAcrossContainerExtensions() {
        val a = "https://panel.test:443/series/alice/secret/89242.mp4"
        val b = "https://panel.test:443/series/bob/other/89242.mkv"
        assertEquals(CloudMediaKeys.hash("ep", a), CloudMediaKeys.hash("ep", b))
        assertNotEquals(CloudMediaKeys.previousHash("ep", a), CloudMediaKeys.previousHash("ep", b))
        assertNotEquals(CloudMediaKeys.hash("ep", a), CloudMediaKeys.hash("ep",
            "https://panel.test:443/series/bob/other/89243.mkv"))
        assertNotEquals(CloudMediaKeys.hash("ep", a), CloudMediaKeys.hash("ep",
            "https://another.test:443/series/bob/other/89242.mkv"))
    }

    @Test fun differentProviderPortalHostsShareSameCredentialFreeAliasWhenIdAndTitleMatch() {
        val a = CloudMediaKeys.catalogueAlias("ep", "71234", "Episode 13")
        val b = CloudMediaKeys.catalogueAlias("ep", "71234", "EPISODE   13")
        assertEquals(a, b)
        assertNotEquals(a, CloudMediaKeys.catalogueAlias("ep", "71235", "Episode 13"))
        assertNotEquals(a, CloudMediaKeys.catalogueAlias("ep", "71234", "Episode 14"))
        assertEquals(64, a?.length)
        assertEquals(null, CloudMediaKeys.catalogueAlias("ep", "not-a-numeric-id", "Episode 13"))
    }

    @Test fun episodeLocatorIsStableAcrossDevicesButDistinctAcrossSeries() {
        val a = CloudMediaKeys.episodeLocator("3112", "بنت كلي", "90123", 1, 13)
        val b = CloudMediaKeys.episodeLocator("3112", "بنت كلي", "90123", 1, 13)
        val otherEpisode = CloudMediaKeys.episodeLocator("3112", "بنت كلي", "90124", 1, 14)
        val otherSeries = CloudMediaKeys.episodeLocator("3113", "بنت كلي", "90123", 1, 13)
        assertEquals(a, b)
        assertNotEquals(a?.episodeFingerprint, otherEpisode?.episodeFingerprint)
        assertNotEquals(a?.seriesFingerprint, otherSeries?.seriesFingerprint)
        assertTrue(CloudMediaKeys.validEpisodeLocator(a!!))
        assertEquals(null, CloudMediaKeys.episodeLocator("3112", "بنت كلي", "90123", 0, 13))
    }

    @Test fun legacyCloudPointWithoutEpisodeLocatorStillDeserializes() {
        val raw = """{"profile":"","kind":"ep","mediaHash":"${"a".repeat(64)}",
            "positionMillis":25000,"durationMillis":1800000,"updatedAtMillis":12345}"""
        val restored = Json.decodeFromString<CloudWatchPoint>(raw)
        assertEquals(null, restored.episodeLocator)
    }

    @Test fun episodeLocatorSerializesWithoutProviderNameOrCredentials() {
        val locator = CloudMediaKeys.episodeLocator("3112", "بنت كلي", "90123", 1, 13)!!
        val point = CloudWatchPoint("", "a".repeat(64), "ep", 75_000, 100_000, 92_000,
            episodeLocator = locator)
        val json = Json.encodeToString(point)
        assertFalse(json.contains("بنت كلي"))
        assertFalse(json.contains("3112"))
        assertFalse(json.contains("90123"))
        assertEquals(locator, Json.decodeFromString<CloudWatchPoint>(json).episodeLocator)
    }

    @Test fun cloudPayloadContainsNoProviderCredentialsOrUrls() {
        val point = CloudWatchPoint(
            profile = "Me",
            mediaHash = CloudMediaKeys.hash("movie", "https://panel.test/movie/login/password/7"),
            kind = "movie",
            positionMillis = 42000,
            durationMillis = 100000,
            updatedAtMillis = 1000,
            aliases = listOfNotNull(CloudMediaKeys.catalogueAlias("movie", "7", "Some Film")),
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
