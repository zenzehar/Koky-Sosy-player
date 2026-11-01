package app.sokyiptv.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression: the v84 switch to R8 was missing VideoLAN's mandatory JNI
 * keep rules. Native VLC looks up Java symbols that R8 cannot see.
 */
class LibVlcR8RulesTest {
    @Test fun minifiedBuildRetainsVideoLanJniClassAndMemberNames() {
        val file = sequenceOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
        ).firstOrNull { it.isFile }
        assertTrue("Cannot locate application ProGuard rules", file != null)
        val rules = file!!.readText()
        assertTrue(
            "JNI bound LibVLC classes must never be renamed or removed",
            rules.contains("-keep class org.videolan.libvlc.** { *; }"),
        )
        assertTrue(
            "MediaLibrary JNI classes must remain intact",
            rules.contains("-keep class org.videolan.medialibrary.** { *; }"),
        )
    }
}
