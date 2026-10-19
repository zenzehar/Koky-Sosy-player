package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcPlaybackPolicyTest {
    private val generic = "SokyIPTV/0.1 (Android)"

    @Test fun nativeModeUsesLibVlcDefaultsWithoutAppOverrides() {
        assertEquals(VlcPlaybackMode.NATIVE, VlcPlaybackMode.fromSaved(null))
        assertEquals(VlcPlaybackMode.NATIVE, VlcPlaybackMode.fromSaved("invalid"))
        val engine = VlcPlaybackPolicy.engineArguments(VlcPlaybackMode.NATIVE, 8_000)
        assertFalse(engine.any { it.contains("network-caching") })
        assertTrue(VlcPlaybackPolicy.mediaArguments(VlcPlaybackMode.NATIVE, generic, generic).isEmpty())
        assertFalse(VlcPlaybackPolicy.overrideHardwareDecoder(VlcPlaybackMode.NATIVE))
    }

    @Test fun userExplicitCustomAgentSurvivesNativeCompatibilityMode() {
        val args = VlcPlaybackPolicy.mediaArguments(
            VlcPlaybackMode.NATIVE, "ProviderRequired/2.0", generic,
        )
        assertEquals(listOf(":http-user-agent=ProviderRequired/2.0"), args)
    }

    @Test fun tunedModeRetainsManualBufferAndReconnect() {
        assertEquals(VlcPlaybackMode.TUNED, VlcPlaybackMode.fromSaved("TUNED"))
        val args = VlcPlaybackPolicy.engineArguments(VlcPlaybackMode.TUNED, 5_000)
        assertTrue(args.contains("--network-caching=5000"))
        val media = VlcPlaybackPolicy.mediaArguments(VlcPlaybackMode.TUNED, generic, generic)
        assertTrue(media.contains(":http-user-agent=$generic"))
        assertTrue(media.contains(":http-reconnect"))
        assertTrue(VlcPlaybackPolicy.overrideHardwareDecoder(VlcPlaybackMode.TUNED))
    }

    @Test fun tunedCacheIsCappedForLowMemoryDevices() {
        val args = VlcPlaybackPolicy.engineArguments(VlcPlaybackMode.TUNED, Int.MAX_VALUE)
        assertTrue(args.contains("--network-caching=15000"))
    }
}
