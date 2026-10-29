package app.sokyiptv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: channel or EPG readiness is NEVER a login prerequisite. */
class LoginAdmissionPolicyTest {
    @Test fun successfulProviderResponseAndSavedAccountImmediatelyUnlockHome() {
        assertTrue(LoginAdmissionPolicy.canEnterHome(true, 42L))
    }

    @Test fun invalidCredentialsOrFailedLocalSaveStillBlockHome() {
        assertFalse(LoginAdmissionPolicy.canEnterHome(false, 42L))
        assertFalse(LoginAdmissionPolicy.canEnterHome(true, 0L))
        assertFalse(LoginAdmissionPolicy.canEnterHome(true, -1L))
    }

    @Test fun noSecondNetworkProbeAfterNewAccountWasVerifiedThisSession() {
        val ids = setOf(42L, 84L)
        assertTrue(LoginAdmissionPolicy.canReuseSession(42L, ids, true))
        assertFalse(LoginAdmissionPolicy.canReuseSession(42L, ids, false))
        assertFalse(LoginAdmissionPolicy.canReuseSession(7L, ids, true))
        assertFalse(LoginAdmissionPolicy.canReuseSession(0L, ids, true))
    }
}
