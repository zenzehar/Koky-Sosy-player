package app.sokyiptv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProviderLoginPolicyTest {
    @Test fun explicitlyRejectedCredentialsNeverAllowCachedAccess() {
        val rejected = ProviderLoginPolicy.classify(
            IllegalStateException("Username or password rejected by the server."))
        assertEquals(ProviderLoginPolicy.Reason.REJECTED, rejected.reason)
        assertFalse(ProviderLoginPolicy.allowCachedAccess(true, rejected.reason))
        assertFalse(ProviderLoginPolicy.allowCachedAccess(false, rejected.reason))
        val expired = ProviderLoginPolicy.classify(
            IllegalStateException("This account is Expired."))
        assertEquals(ProviderLoginPolicy.Reason.REJECTED, expired.reason)
    }

    @Test fun previouslyVerifiedUserCanContinueDuringOutageOnly() {
        val offline = ProviderLoginPolicy.classify(UnknownHostException("host unavailable"))
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, offline.reason)
        assertTrue(ProviderLoginPolicy.allowCachedAccess(true, offline.reason))
        assertFalse(ProviderLoginPolicy.allowCachedAccess(false, offline.reason))
        val timeout = ProviderLoginPolicy.classify(SocketTimeoutException("timeout"))
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, timeout.reason)
    }

    @Test fun brokenServerUrlRequiresLoginCorrection() {
        val bad = ProviderLoginPolicy.classify(
            IllegalStateException("No Xtream API at that address (HTTP 404)"))
        assertEquals(ProviderLoginPolicy.Reason.CONFIGURATION, bad.reason)
        assertFalse(ProviderLoginPolicy.allowCachedAccess(true, bad.reason))
    }

    @Test fun temporaryServerFailureIsNotMistakenForWrongPassword() {
        val provider = ProviderLoginPolicy.classify(
            IllegalStateException("The provider's server is having problems (HTTP 503)."))
        assertEquals(ProviderLoginPolicy.Reason.UNAVAILABLE, provider.reason)
        assertTrue(ProviderLoginPolicy.allowCachedAccess(true, provider.reason))
    }
}
