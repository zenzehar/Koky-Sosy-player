package app.sokyiptv.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the authenticated POST /exchange body contract used by the LAN protocol. */
class SyncExchangeWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun activeProfileSnapshotRoundTripsWithoutProviderCredentials() {
        val original = SyncBundle(positions = listOf(
            SyncPosition(
                profile = "active", streamUrl = "", kind = "ep",
                positionMillis = 75_000, durationMillis = 1_800_000,
                updatedAtMillis = 1_234_567, identityHash = "a".repeat(64),
                logicalHash = "b".repeat(64), identityAliases = listOf("c".repeat(64)),
            ),
        ))
        val payload = json.encodeToString(original)
        assertEquals(payload.length, payload.toByteArray(Charsets.UTF_8).size)
        assertFalse(payload.contains("username"))
        assertFalse(payload.contains("password"))
        assertEquals(original, json.decodeFromString<SyncBundle>(payload))
    }

    @Test fun olderGetOnlySnapshotCanStillBeParsed() {
        val older = """{"version":3,"generatedAtMillis":123,"positions":[{"profile":"active","streamUrl":"","kind":"movie","positionMillis":1000,"durationMillis":10000,"updatedAtMillis":1234}]}"""
        val result = json.decodeFromString<SyncBundle>(older)
        assertEquals(1, result.positions.size)
        assertEquals("movie", result.positions.single().kind)
        assertTrue(result.positions.single().identityHash.isBlank())
    }
}
