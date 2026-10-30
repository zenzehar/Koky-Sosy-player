package app.sokyiptv.ui.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMenuPolicyTest {
    @Test fun recoveryMenuHasFiveReachableChoicesOnRemoteUpAndDown() {
        var position = 0
        val visited = mutableListOf(PlaybackErrorMenuPolicy.action(position))
        repeat(4) {
            position = PlaybackErrorMenuPolicy.moved(position, 1)
            visited += PlaybackErrorMenuPolicy.action(position)
        }
        assertEquals(
            listOf(
                PlaybackErrorMenuPolicy.Action.RETRY,
                PlaybackErrorMenuPolicy.Action.MEDIA3,
                PlaybackErrorMenuPolicy.Action.EXTERNAL_VLC,
                PlaybackErrorMenuPolicy.Action.DIAGNOSTICS,
                PlaybackErrorMenuPolicy.Action.BACK,
            ), visited,
        )
    }

    @Test fun arrowsClampInsideFailureMenuInsteadOfEscapingToSeekbar() {
        assertEquals(0, PlaybackErrorMenuPolicy.moved(0, -1))
        assertEquals(4, PlaybackErrorMenuPolicy.moved(4, 1))
        assertEquals(2, PlaybackErrorMenuPolicy.moved(1, 1))
        assertEquals(0, PlaybackErrorMenuPolicy.moved(1, -1))
    }

    @Test fun pressDoesNotTriggerTwiceOnHoldOrKeyRelease() {
        assertTrue(PlaybackErrorMenuPolicy.shouldActivate(true, 0))
        assertFalse(PlaybackErrorMenuPolicy.shouldActivate(true, 1))
        assertFalse(PlaybackErrorMenuPolicy.shouldActivate(false, 0))
    }
}
