package app.sokyiptv.player

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** No training, video surface, or prior episodes required for trusted remote markers. */
class CommunitySkipLookupTest {
    private val lookup = CommunitySkipLookup(OkHttpClient())

    @Test fun firstEpisodeTrustedIntroAndOutroAreAvailableImmediately() {
        val intro = lookup.checkSegment(31_000L, 88_000L, .95, "SkipDB",
            outro = false, duration = 2_600_000L)
        val outro = lookup.checkSegment(2_510_000L, 2_590_000L, .94, "IntroDB",
            outro = true, duration = 2_600_000L)
        assertNotNull(intro)
        assertNotNull(outro)
        assertEquals(88_000L, intro!!.endMs)
        assertEquals("IntroDB", outro!!.source)
    }

    @Test fun invalidBoundsCannotSkipOrAccidentallyExitAnEpisode() {
        assertNull(lookup.checkSegment(51_000, 49_000, .99, "SkipDB", false, 2_600_000))
        assertNull(lookup.checkSegment(400_000, 890_000, .99, "SkipDB", false, 2_600_000))
        assertNull(lookup.checkSegment(1_000_000, 1_090_000, .99, "SkipDB", true, 2_600_000))
        assertNull(lookup.checkSegment(2_650_000, 2_690_000, .99, "SkipDB", true, 2_600_000))
    }
}
