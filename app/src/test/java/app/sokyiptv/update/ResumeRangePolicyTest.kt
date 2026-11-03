package app.sokyiptv.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRangePolicyTest {
    private val expected = 200_000_000L
    @Test fun appendsOnlyAtExactOffset() {
        assertTrue(ResumeRangePolicy.canAppend(4_096L, expected, 206,
            "bytes 4096-199999999/200000000", "asset-v1", "asset-v1"))
    }
    @Test fun rangeStartMismatchMustNotAppend() {
        assertFalse(ResumeRangePolicy.canAppend(4_096L, expected, 206,
            "bytes 0-199999999/200000000", "asset-v1", "asset-v1"))
    }
    @Test fun changedAssetMustNotAppend() {
        assertFalse(ResumeRangePolicy.canAppend(4_096L, expected, 206,
            "bytes 4096-199999999/200000000", "asset-v1", "asset-v2"))
        assertFalse(ResumeRangePolicy.canAppend(4_096L, expected, 206,
            "bytes 4096-199999999/199999999", "asset-v1", "asset-v1"))
    }
    @Test fun nonPartialResponseMustRestartNotAppend() {
        assertFalse(ResumeRangePolicy.canAppend(4_096L, expected, 200,
            null, "asset-v1", "asset-v1"))
        assertFalse(ResumeRangePolicy.canAppend(0L, expected, 206,
            "bytes 0-199999999/200000000", null, null))
    }
}
