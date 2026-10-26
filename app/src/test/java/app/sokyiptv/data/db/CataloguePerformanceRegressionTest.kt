package app.sokyiptv.data.db

import androidx.room.Room
import app.sokyiptv.data.model.Channel
import app.sokyiptv.data.model.Episode
import app.sokyiptv.data.model.PlaybackPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room DAO regression tests: no migration, no provider connection required. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
class CataloguePerformanceRegressionTest {
    private lateinit var db: SokyIptvDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            SokyIptvDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun channel(id: Int, name: String = "Station $id") = Channel(
        sourceId = 9L,
        streamId = "provider-$id",
        name = name,
        categoryId = "general",
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.invalid/$id",
    )

    @Test fun chunkedImportsKeepAllRowsAndExistingUserChoices() = runBlocking {
        val dao = db.channels()
        val original = (1..605).map { channel(it) }
        dao.replaceCatalogue(9L, original, syncStamp = 10L)
        assertEquals(605, dao.countForSource(9L))
        val selected = dao.channelsInCategoryIncludingHidden(9L, "general")
            .first { it.streamId == "provider-3" }

        dao.setFavourite(selected.id, true)
        dao.setHidden(selected.id, true)
        dao.setCustomName(selected.id, "My Channel")
        dao.setSortIndex(selected.id, 44)
        dao.setEpgOverride(selected.id, "manual-guide")
        dao.setMatchedEpgId(selected.id, "auto-guide")

        val refreshed = (1..604).map { channel(it, "Renamed Station $it") }
        dao.replaceCatalogue(9L, refreshed, syncStamp = 11L)
        assertEquals(604, dao.countForSource(9L))
        val after = dao.byId(selected.id)!!
        assertEquals(selected.id, after.id)
        assertEquals("Renamed Station 3", after.name)
        assertTrue(after.favourite)
        assertTrue(after.hidden)
        assertEquals("My Channel", after.customName)
        assertEquals(44, after.sortIndex)
        assertEquals("manual-guide", after.epgOverrideId)
        assertEquals("auto-guide", after.matchedEpgId)

        val projected = dao.userStateForSource(9L)
        assertEquals(604, projected.size)
        assertTrue(projected.any { it.id == selected.id && it.favourite })
        assertFalse(projected.any { it.streamId == "provider-605" })
    }

    @Test fun epgMatchesCommitTogetherWithoutAlteringManualOverride() = runBlocking {
        val dao = db.channels()
        dao.replaceCatalogue(9L, listOf(channel(1), channel(2)), syncStamp = 10L)
        val records = dao.channelsInCategoryIncludingHidden(9L, "general")
            .associateBy { it.streamId }
        val first = records["provider-1"]!!
        val second = records["provider-2"]!!
        dao.setEpgOverride(first.id, "manual")
        dao.applyMatchedEpgChanges(listOf(first.id to "auto-A", second.id to "auto-B"))
        assertEquals("auto-A", dao.byId(first.id)?.matchedEpgId)
        assertEquals("auto-B", dao.byId(second.id)?.matchedEpgId)
        assertEquals("manual", dao.byId(first.id)?.epgOverrideId)
    }
    @Test fun onlySameProfileSameEpisodeSavedDurationReachesSeriesList() = runBlocking {
        val episodes = listOf(
            Episode(id = 201L, sourceId = 9L, seriesId = "show-A", episodeId = "e1",
                season = 1, episodeNumber = 1, title = "Episode 1", plot = null,
                durationSeconds = null, stillUrl = null, streamUrl = "https://example.invalid/e1"),
            Episode(id = 202L, sourceId = 9L, seriesId = "show-A", episodeId = "e2",
                season = 1, episodeNumber = 2, title = "Episode 2", plot = null,
                durationSeconds = null, stillUrl = null, streamUrl = "https://example.invalid/e2"),
            Episode(id = 203L, sourceId = 9L, seriesId = "show-B", episodeId = "e3",
                season = 1, episodeNumber = 1, title = "Other show", plot = null,
                durationSeconds = null, stillUrl = null, streamUrl = "https://example.invalid/e3"),
        )
        db.episodes().upsertAll(episodes)
        val positions = db.positions()
        positions.upsert(PlaybackPosition(1L, "ep:201", 660_000L, 2_886_000L, 100L))
        positions.upsert(PlaybackPosition(2L, "ep:202", 660_000L, 2_886_000L, 100L))
        positions.upsert(PlaybackPosition(1L, "ep:203", 660_000L, 3_000_000L, 100L))
        val showA = positions.observeEpisodeLengths(1L, 9L, "show-A").first()
        assertEquals(1, showA.size)
        assertEquals(201L, showA.single().episodeId)
        assertEquals(2_886_000L, showA.single().durationMs)
        val otherProfile = positions.observeEpisodeLengths(2L, 9L, "show-A").first()
        assertEquals(202L, otherProfile.single().episodeId)
    }

}
