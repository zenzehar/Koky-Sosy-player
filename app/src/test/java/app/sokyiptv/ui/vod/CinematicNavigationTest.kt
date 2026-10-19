package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Test

class CinematicNavigationTest {
    private fun move(level: CinematicFocusLevel, up: Boolean, episode: Boolean = true) =
        moveCinematicFocus(level, episode, up)

    @Test fun episodesHaveExactlyThreeLevelsOnePressApart() {
        val seek = CinematicFocusLevel.SEEK
        val controls = CinematicFocusLevel.CONTROLS
        val auto = CinematicFocusLevel.AUTOPLAY
        assertEquals(controls, move(seek, up = true))
        assertEquals(auto, move(controls, up = true))
        assertEquals(auto, move(auto, up = true))
        assertEquals(controls, move(auto, up = false))
        assertEquals(seek, move(controls, up = false))
        assertEquals(seek, move(seek, up = false))
    }

    @Test fun moviesSkipAutoplayLevel() {
        val seek = CinematicFocusLevel.SEEK
        val controls = CinematicFocusLevel.CONTROLS
        assertEquals(controls, move(seek, up = true, episode = false))
        assertEquals(controls, move(controls, up = true, episode = false))
        assertEquals(seek, move(controls, up = false, episode = false))
    }
}
