package app.sokyiptv.sync.cloud

import org.junit.Assert.*
import org.junit.Test

class AccountWatchLedgerTest {
    private val movie = "a".repeat(64)
    private val episode = "b".repeat(64)
    private fun point(profile: String, hash: String, pos: Long, stamp: Long,
                      kind: String = "movie", duration: Long = 100_000L) =
        CloudWatchPoint(profile, hash, kind, pos, duration, stamp)

    @Test fun sameGoogleAccountDoesNotRequireProfileMatch() {
        val merged = AccountWatchLedger.merge(1_000_000L,
            listOf(point("Lounge", movie, 10_000L, 800_000L)),
            listOf(point("Kids", movie, 42_000L, 900_000L)))
        assertEquals(1, merged.size)
        assertEquals("", merged.single().profile)
        assertEquals(42_000L, merged.single().positionMillis)
    }
    @Test fun newerPositionWinsEvenWhenPositionIsShorter() {
        val merged = AccountWatchLedger.merge(1_000_000L,
            listOf(point("TV1", movie, 80_000L, 800_000L)),
            listOf(point("TV2", movie, 15_000L, 900_000L)))
        assertEquals(15_000L, merged.single().positionMillis)
    }
    @Test fun underreportedDurationDoesNotFinishRemotePlayback() {
        val merged = AccountWatchLedger.merge(1_000_000L,
            listOf(point("A", movie, 120_000L, 900_000L, duration = 60_000L)))
        assertEquals(120_000L, merged.single().positionMillis)
        assertEquals(0L, merged.single().durationMillis)
    }
    @Test fun differentMediaAndKindNeverOverwriteEachOther() {
        val merged = AccountWatchLedger.merge(1_000_000L,
            listOf(point("A", movie, 12_000L, 800_000L)),
            listOf(point("B", episode, 24_000L, 900_000L, "ep")))
        assertEquals(2, merged.size)
    }
    @Test fun verificationDetectsOverwriteAndAcceptsNewer() {
        val expected = listOf(point("", movie, 12_000L, 900_000L))
        assertFalse(AccountWatchLedger.covers(emptyList(), expected))
        assertFalse(AccountWatchLedger.covers(
            listOf(point("", movie, 9_000L, 800_000L)), expected))
        assertTrue(AccountWatchLedger.covers(
            listOf(point("", movie, 15_000L, 950_000L)), expected))
    }
    @Test fun sameTimestampTieIsDeterministic() {
        val merged = AccountWatchLedger.merge(1_000_000L,
            listOf(point("A", movie, 5_000L, 900_000L)),
            listOf(point("B", movie, 10_000L, 900_000L)))
        assertEquals(10_000L, merged.single().positionMillis)
    }
}
