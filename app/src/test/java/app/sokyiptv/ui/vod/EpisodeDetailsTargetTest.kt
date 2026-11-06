package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeDetailsTargetTest {
    @Test fun usesCurrentPlayingEpisodeNotAContentCategory() {
        assertEquals(147L, EpisodeDetailsTarget.episodeId("ep:147"))
        assertNull(EpisodeDetailsTarget.episodeId("movie:147"))
        assertNull(EpisodeDetailsTarget.episodeId("ep:invalid"))
        assertNull(EpisodeDetailsTarget.episodeId("ep:-1"))
        assertNull(EpisodeDetailsTarget.episodeId("ep:"))
    }
}
