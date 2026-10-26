package app.sokyiptv.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Regression guard for scalable Continue Watching resolution. */
class ResumeLookupPlanTest {
    @Test fun deduplicatesIdsWithoutDiscardingMovieVsEpisodeNamespaces() {
        val plan = ResumeLookupPlan.from(listOf(
            "ep:20", "movie:42", "ep:20", "movie:42", "movie:99", "ep:42",
        ))
        assertThat(plan.movieIds).containsExactly(42L, 99L).inOrder()
        assertThat(plan.episodeIds).containsExactly(20L, 42L).inOrder()
    }

    @Test fun excludesMalformedProviderAndNonPlaybackKeys() {
        val plan = ResumeLookupPlan.from(listOf(
            "ep:-1", "movie:0", "movie:abc", "ep:12:more", "series:100",
            "live:4", "", "movie:", ":9", "ep:1",
        ))
        assertThat(plan.movieIds).isEmpty()
        assertThat(plan.episodeIds).containsExactly(1L)
    }

    @Test fun completeRecentPageIsAlwaysBelowLegacySqliteBindLimit() {
        val keys = (1..300).map { if (it % 2 == 0) "movie:$it" else "ep:$it" }
        val plan = ResumeLookupPlan.from(keys)
        assertThat(plan.movieIds.size).isEqualTo(150)
        assertThat(plan.episodeIds.size).isEqualTo(150)
        assertThat(plan.movieIds.size + plan.episodeIds.size).isEqualTo(300)
    }
}
