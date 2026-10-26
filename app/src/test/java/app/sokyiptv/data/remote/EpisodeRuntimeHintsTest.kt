package app.sokyiptv.data.remote

import app.sokyiptv.data.model.Episode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [28])
class EpisodeRuntimeHintsTest {
    private fun episode(id: Long, providerId: String) = Episode(
        id = id, sourceId = 21L, seriesId = "a", episodeId = providerId,
        season = 1, episodeNumber = id.toInt(), title = "Episode", plot = null,
        durationSeconds = null, stillUrl = null, streamUrl = "https://example.invalid/e.mkv",
    )

    @Test fun durationSurvivesResetOfProgressAndChangingRoomIds() {
        val app = RuntimeEnvironment.getApplication()
        val one = episode(100L, "ep-one-" + System.nanoTime())
        val other = episode(200L, "ep-two-" + System.nanoTime())
        EpisodeRuntimeHints.save(app, one, 2886)
        assertThat(EpisodeRuntimeHints.lookupMillis(app, one)).isEqualTo(2_886_000L)
        assertThat(EpisodeRuntimeHints.lookupMillis(app, episode(500L, one.episodeId)))
            .isEqualTo(2_886_000L)
        // Reset Progress deletes a different Room table and cannot touch these preferences.
        assertThat(EpisodeRuntimeHints.lookupMillis(app, other)).isNull()
    }
}
