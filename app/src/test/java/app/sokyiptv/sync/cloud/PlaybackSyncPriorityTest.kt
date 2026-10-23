package app.sokyiptv.sync.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSyncPriorityTest {
    @Test fun pauseCloudWhileOneVideoScreenIsOpen() {
        assertFalse(PlaybackSyncPriority.active)
        try {
            assertTrue(PlaybackSyncPriority.begin())
            assertTrue(PlaybackSyncPriority.active)
        } finally {
            assertTrue(PlaybackSyncPriority.end())
        }
        assertFalse(PlaybackSyncPriority.active)
    }

    @Test fun switchingScreensNeverReEnablesCloudPrematurely() {
        assertFalse(PlaybackSyncPriority.active)
        try {
            assertTrue(PlaybackSyncPriority.begin())
            assertFalse(PlaybackSyncPriority.begin())
            assertTrue(PlaybackSyncPriority.active)
            assertFalse(PlaybackSyncPriority.end())
            assertTrue(PlaybackSyncPriority.active)
        } finally {
            assertTrue(PlaybackSyncPriority.end())
        }
        assertFalse(PlaybackSyncPriority.active)
    }
}
