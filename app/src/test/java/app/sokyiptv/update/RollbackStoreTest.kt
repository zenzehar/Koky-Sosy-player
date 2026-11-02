package app.sokyiptv.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RollbackStoreTest {
    private val current = "0.1.11.92-24h-rollback"
    private val previous = "0.1.11.91-navigation-smoothness"
    private val installedAt = 100_000_000L
    private val firstInstalledAt = 50_000_000L

    @Test fun twentyFourHoursFromActualInstallNotFirstAppLaunch() {
        assertThat(RollbackStore.isEligibleUpgrade(
            current, previous, firstInstalledAt, installedAt,
            installedAt + RollbackStore.WINDOW_MS - 1L,
        )).isTrue()
        assertThat(RollbackStore.isEligibleUpgrade(
            current, previous, firstInstalledAt, installedAt,
            installedAt + RollbackStore.WINDOW_MS,
        )).isFalse()
        assertThat(RollbackStore.safeExpiry(installedAt))
            .isEqualTo(installedAt + 24L * 60L * 60L * 1000L)
    }

    @Test fun cleanInstallOrUnknownPreviousNeverShowsRollback() {
        assertThat(RollbackStore.isEligibleUpgrade(
            current, previous, installedAt, installedAt, installedAt + 100L,
        )).isFalse()
        assertThat(RollbackStore.isEligibleUpgrade(
            current, null, firstInstalledAt, installedAt, installedAt + 100L,
        )).isFalse()
        assertThat(RollbackStore.isEligibleUpgrade(
            current, current, firstInstalledAt, installedAt, installedAt + 100L,
        )).isFalse()
    }

    @Test fun neverInferAPreviousVersionOrAllowFuturePreviousVersion() {
        assertThat(RollbackStore.isEligibleUpgrade(
            current, "0.1.11.93", firstInstalledAt, installedAt, installedAt + 100L,
        )).isFalse()
        assertThat(RollbackStore.isEligibleUpgrade(
            current, "0.1.11.90", firstInstalledAt, installedAt, installedAt + 100L,
        )).isTrue() // Known skipped version: URL must still match exact signed asset.
    }

    @Test fun rollbackCannotStartWithFutureTimestamp() {
        assertThat(RollbackStore.isEligibleUpgrade(
            current, previous, firstInstalledAt, installedAt, installedAt - 1L,
        )).isFalse()
    }
}
