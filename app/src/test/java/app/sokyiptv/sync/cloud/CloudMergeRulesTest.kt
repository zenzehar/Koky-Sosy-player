package app.sokyiptv.sync.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudMergeRulesTest {
    private val hash = "a".repeat(64)
    private fun point(
        timestamp: Long = 100000L, position: Long = 60000L, duration: Long = 120000L,
        kind: String = "movie", profile: String = "Me",
    ) = CloudWatchPoint(profile, hash, kind, position, duration, timestamp)

    @Test fun rejectsInvalidAndFutureCloudRecords() {
        val now = 100000L
        assertTrue(CloudMergeRules.valid(point(), now))
        assertFalse(CloudMergeRules.valid(point(timestamp = 0L), now))
        assertFalse(CloudMergeRules.valid(point(timestamp = now + 5 * 60000L + 1), now))
        assertFalse(CloudMergeRules.valid(point(position = -1L), now))
        assertFalse(CloudMergeRules.valid(point(duration = -1L), now))
        assertFalse(CloudMergeRules.valid(point(position = 400000L), now))
        assertFalse(CloudMergeRules.valid(point(kind = "live"), now))
        assertFalse(CloudMergeRules.valid(point(profile = ""), now))
        assertFalse(CloudMergeRules.valid(point(profile = "X".repeat(200)), now))
        assertFalse(CloudMergeRules.valid(point().copy(mediaHash = "not-a-hash"), now))
    }

    @Test fun neverOverwritesEqualOrNewerLocalTimestamp() {
        assertTrue(CloudMergeRules.shouldImport(null, 100))
        assertTrue(CloudMergeRules.shouldImport(99, 100))
        assertFalse(CloudMergeRules.shouldImport(100, 100))
        assertFalse(CloudMergeRules.shouldImport(101, 100))
    }
}
