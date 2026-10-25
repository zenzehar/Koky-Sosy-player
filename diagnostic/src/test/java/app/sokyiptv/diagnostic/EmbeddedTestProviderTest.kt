package app.sokyiptv.diagnostic

import org.junit.Assert.*
import org.junit.Test

class EmbeddedTestProviderTest {
    @Test fun oneOffBuildHasACompleteXtreamAccount() {
        val p = EmbeddedTestProvider.create()
        assertEquals("XTREAM", p.kind)
        assertTrue(p.url.startsWith("http://") || p.url.startsWith("https://"))
        assertTrue(p.username.isNotEmpty())
        assertTrue(p.password.isNotEmpty())
        assertEquals("SokyIPTV/0.1 (Android)", p.userAgent)
        assertEquals("Manual Xtream provider",
            ManualProviderInput.create(p.url,p.username,p.password,p.userAgent).name)
    }
}
