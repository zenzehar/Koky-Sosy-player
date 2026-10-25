package app.sokyiptv.diagnostic

import org.junit.Assert.*
import org.junit.Test

class ManualProviderInputTest {
    @Test fun acceptsServerWithOrWithoutSchemeAndPort() {
        val first = ManualProviderInput.create(
            "example.com:8080", "username", "somePass", ""
        )
        assertEquals("http://example.com:8080", first.url)
        assertEquals("SokyIPTV/0.1 (Android)", first.userAgent)
        val second = ManualProviderInput.create(
            "https://example.com:8443/player_api.php", "username", "somePass", "VLC/3.0"
        )
        assertEquals("https://example.com:8443", second.url)
        assertEquals("VLC/3.0", second.userAgent)
    }

    @Test fun preventsCredentialsBeingEmbeddedInServerUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("http://user:password@example.com:8080", "a", "b", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("http://example.com/player_api.php?username=a&password=b", "a", "b", "")
        }
    }

    @Test fun requiresCredentialsAndRejectsUnsafeHeaderValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("example.com", "", "password", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("example.com", "username", "", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("example.com", "username", "password", "Agent\r\nUnsafe")
        }
    }

    @Test fun onlyAcceptsKnownXtreamApiPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualProviderInput.create("http://example.com/admin", "u", "p", "")
        }
    }
}
