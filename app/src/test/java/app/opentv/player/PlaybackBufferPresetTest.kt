package app.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferPresetTest {
    @Test fun unknownOrMissingValueDefaultsToBalanced() {
        assertEquals(PlaybackBufferPreset.BALANCED, PlaybackBufferPreset.fromSaved(null))
        assertEquals(PlaybackBufferPreset.BALANCED, PlaybackBufferPreset.fromSaved("OLD_REMOVED_PRESET"))
    }

    @Test fun allPresetsHaveBoundedExoPlayerBuffers() {
        PlaybackBufferPreset.entries.forEach { preset ->
            assertTrue(preset.media3MinBufferMs <= preset.media3MaxBufferMs)
            assertTrue(preset.media3StartMs <= preset.media3MinBufferMs)
            assertTrue(preset.media3RebufferMs <= preset.media3MinBufferMs)
            assertTrue(preset.media3TargetBufferBytes in 12 * 1024 * 1024..48 * 1024 * 1024)
            assertTrue(preset.vlcNetworkCacheMs <= 12_000)
        }
    }

    @Test fun settingsRoundTripAndProfilesAreOrderedByLatency() {
        val all = PlaybackBufferPreset.entries.toList()
        for (preset in all) assertEquals(preset, PlaybackBufferPreset.fromSaved(preset.name))
        assertTrue(all.zipWithNext().all { (a, b) -> a.vlcNetworkCacheMs < b.vlcNetworkCacheMs })
        assertTrue(all.zipWithNext().all { (a, b) -> a.media3TargetBufferBytes <= b.media3TargetBufferBytes })
    }
}
