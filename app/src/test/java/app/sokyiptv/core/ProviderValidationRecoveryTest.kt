package app.sokyiptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProviderValidationRecoveryTest {
    @Test fun tvInternetIsNotDeclaredDownWhenProviderLoginTimesOut() {
        val failure = ProviderLoginPolicy.classify(
            SocketTimeoutException("provider login timed out")
        )
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, failure.reason)
        assertTrue(failure.message.contains("provider", ignoreCase = true))
        assertTrue(failure.message.contains("Other internet services may still work"))
        assertTrue(ProviderValidationRecovery.shouldRetryOnce(failure.reason))
    }

    @Test fun providerRateLimitIsTreatedAsTransientNotBadCredentials() {
        val failure = ProviderLoginPolicy.classify(
            IllegalStateException("The server is rate-limiting this device (HTTP 429).")
        )
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, failure.reason)
        assertTrue(failure.message.contains("HTTP 429"))
        assertTrue(ProviderLoginPolicy.allowCachedAccess(true, failure.reason))
        assertFalse(ProviderLoginPolicy.allowCachedAccess(false, failure.reason))
    }

    @Test fun rejectedCredentialsNeverGetAutomaticCachedAccess() {
        val failure = ProviderLoginPolicy.classify(
            IllegalStateException("Username or password rejected by the server.")
        )
        assertEquals(ProviderLoginPolicy.Reason.REJECTED, failure.reason)
        assertFalse(ProviderValidationRecovery.shouldRetryOnce(failure.reason))
        assertFalse(ProviderLoginPolicy.allowCachedAccess(true, failure.reason))
    }

    @Test fun invalidProviderConfigurationDoesNotPretendToBeAnOutage() {
        val failure = ProviderLoginPolicy.classify(
            IllegalStateException("No Xtream API at that address (HTTP 404).")
        )
        assertEquals(ProviderLoginPolicy.Reason.CONFIGURATION, failure.reason)
        assertFalse(ProviderValidationRecovery.shouldRetryOnce(failure.reason))
        assertFalse(ProviderLoginPolicy.allowCachedAccess(true, failure.reason))
    }

    @Test fun dnsFailureMayBeProviderSpecific() {
        val failure = ProviderLoginPolicy.classify(UnknownHostException("provider-domain"))
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, failure.reason)
        assertTrue(failure.message.contains("provider", ignoreCase = true))
    }

    @Test fun automaticRetriesBackOffRatherThanHammeringProvider() {
        assertEquals(60_000L, ProviderValidationRecovery.delayBeforeNextCheckMs(1))
        assertEquals(120_000L, ProviderValidationRecovery.delayBeforeNextCheckMs(2))
        assertEquals(240_000L, ProviderValidationRecovery.delayBeforeNextCheckMs(3))
        assertEquals(600_000L, ProviderValidationRecovery.delayBeforeNextCheckMs(4))
        assertEquals(600_000L, ProviderValidationRecovery.delayBeforeNextCheckMs(99))
    }
}
