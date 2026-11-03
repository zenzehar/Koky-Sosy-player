package app.sokyiptv.core

import android.app.Application
import app.sokyiptv.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class BrowseCustomizationsTest {
    private lateinit var store: BrowseCustomizations

    private fun channel(id: String, group: String = id) = Channel(
        sourceId = 5, streamId = id, groupKey = group, name = "Channel " + id,
        categoryId = "live", logoUrl = null, epgChannelId = null, number = null,
        streamUrl = "https://example.invalid/" + id
    )

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("soky_browse_customizations_v1", 0).edit().clear().commit()
        store = BrowseCustomizations(ctx)
    }

    @Test fun customOrderSurvivesSwitchingToAlphabeticalAndBack() {
        val original = listOf("a", "b", "c")
        assertTrue(store.moveFavourite("movie", "b", -1, original))
        assertEquals(listOf("b", "a", "c"), store.customSort("movie", original) { it })
        store.setSort("movie", true, BrowseSort.NAME_ASC)
        assertEquals(BrowseSort.NAME_ASC, store.sort("movie", true))
        store.setSort("movie", true, BrowseSort.CUSTOM)
        assertEquals(listOf("b", "a", "c"), store.customSort("movie", original) { it })
        assertEquals(BrowseSort.DEFAULT, store.sort("series", false))
    }

    @Test fun customSortIsAvailableOnlyToFavourites() {
        assertFalse(BrowseSort.options("live", false).contains(BrowseSort.CUSTOM))
        assertTrue(BrowseSort.options("live", true).contains(BrowseSort.CUSTOM))
        store.setSort("live", false, BrowseSort.CUSTOM)
        assertEquals(BrowseSort.DEFAULT, store.sort("live", false))
    }

    @Test fun recentlyWatchedIsPerProfileDeduplicatedAndQualityIndependent() {
        val hd = channel("100hd", "BBCONE")
        val sd = channel("100sd", "BBCONE")
        val other = channel("200", "OTHER")
        store.recordLive(1, hd)
        store.recordLive(1, other)
        store.recordLive(1, sd)
        assertEquals(listOf(store.orderKey(hd), store.orderKey(other)), store.recentLive(1))
        assertTrue(store.recentLive(2).isEmpty())
    }

    @Test fun missingFavouriteDoesNotGetErasedByReorder() {
        val original = listOf("a", "b", "c")
        assertTrue(store.moveFavourite("series", "b", -1, original))
        assertEquals(listOf("b", "a", "c"), store.favouriteOrder("series"))
        assertTrue(store.moveFavourite("series", "b", 1, listOf("b", "a")))
        assertTrue(store.favouriteOrder("series").contains("c"))
    }
}
