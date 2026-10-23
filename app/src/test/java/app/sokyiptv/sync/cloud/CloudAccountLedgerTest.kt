package app.sokyiptv.sync.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudAccountLedgerTest {
    private val movie = "a".repeat(64)
    private val episode = "b".repeat(64)
    private fun point(profile: String, hash: String, position: Long, time: Long,
                      kind: String = "movie") =
        CloudWatchPoint(profile, hash, kind, position, 60_000L, time)

    @Test fun twoUrlHashesWithSameProviderTitleAliasBecomeOneRecord() {
        val identity = "c".repeat(64)
        val first = point("Living Room", movie, 10_000L, 50_000L)
            .copy(aliases = listOf(identity))
        val second = point("Bedroom", episode, 30_000L, 60_000L)
            .copy(kind = "movie", aliases = listOf(identity))
        val results = CloudAccountLedger.merge(100_000L, listOf(first), listOf(second))
        assertEquals(1, results.size)
        assertEquals(30_000L, results.single().positionMillis)
        assertEquals(listOf(identity), results.single().aliases)
        assertTrue(CloudAccountLedger.covers(results, listOf(first)))
    }

    @Test fun migratingOldHashPreservesNewAliases() {
        val identity = "d".repeat(64)
        val legacy = point("", movie, 12_000L, 50_000L)
        val enriched = legacy.copy(aliases = listOf(identity))
        val merged = CloudAccountLedger.merge(100_000L, listOf(legacy), listOf(enriched))
        assertEquals(1, merged.size)
        assertEquals(listOf(identity), merged.single().aliases)
    }

    @Test fun sameGoogleAccountMergesAcrossDifferentProfileNames() {
        val records = CloudAccountLedger.merge(100_000L,
            listOf(point("Living Room", movie, 20_000L, 50_000L)),
            listOf(point("Kid TV", movie, 40_000L, 60_000L)))
        assertEquals(1, records.size)
        assertEquals("", records.single().profile)
        assertEquals(40_000L, records.single().positionMillis)
    }

    @Test fun recordBeyondBadDeclaredDurationIsNotDiscarded() {
        val records = CloudAccountLedger.merge(100_000L,
            listOf(point("Any", movie, 120_000L, 70_000L)))
        assertEquals(120_000L, records.single().positionMillis)
    }

    @Test fun olderDeviceCannotOverwriteNewerPoint() {
        val records = CloudAccountLedger.merge(100_000L,
            listOf(point("TV1", movie, 48_000L, 80_000L)),
            listOf(point("TV2", movie, 10_000L, 65_000L)))
        assertEquals(48_000L, records.single().positionMillis)
    }

    @Test fun differentMediaAndKindsStaySeparate() {
        val records = CloudAccountLedger.merge(100_000L,
            listOf(point("One", movie, 22_000L, 50_000L)),
            listOf(point("Two", episode, 55_000L, 60_000L, "ep")))
        assertEquals(2, records.size)
    }

    @Test fun coverCheckDetectsConcurrentLostUpdate() {
        val expected = listOf(point("", movie, 20_000L, 70_000L))
        assertFalse(CloudAccountLedger.covers(emptyList(), expected))
        assertTrue(CloudAccountLedger.covers(
            listOf(point("", movie, 30_000L, 80_000L)), expected))
        assertFalse(CloudAccountLedger.covers(
            listOf(point("", movie, 10_000L, 60_000L)), expected))
    }

    @Test fun equalTimestampTieIsDeterministic() {
        val records = CloudAccountLedger.merge(100_000L,
            listOf(point("TV1", movie, 5_000L, 50_000L)),
            listOf(point("TV2", movie, 10_000L, 50_000L)))
        assertEquals(10_000L, records.single().positionMillis)
    }
}
