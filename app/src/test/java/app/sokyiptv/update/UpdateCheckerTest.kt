package app.sokyiptv.update

import app.sokyiptv.data.remote.GitHubAsset
import app.sokyiptv.data.remote.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for signed v87+ GitHub prerelease discovery on Android TV. */
class UpdateCheckerTest {
    private val base = "https://github.com/zenzehar/Koky-Sosy-player/releases/download/"

    private fun release(
        version: Int,
        prerelease: Boolean = true,
        signed: Boolean = true,
        draft: Boolean = false,
        assets: List<GitHubAsset>? = null,
    ): GitHubRelease {
        val suffix = if (signed) "signed-test" else "legacy"
        val tag = "v0.1.11.$version-$suffix"
        return GitHubRelease(
            tagName = tag,
            prerelease = prerelease,
            draft = draft,
            body = if (signed) "Permanently signed developer APK, certificate checked" else "Legacy build",
            assets = assets ?: listOf(
                GitHubAsset(
                    name = "Soky_IPTV_Pro_$tag.apk",
                    browserDownloadUrl = base + tag + "/Soky_IPTV_Pro_$tag.apk",
                    size = 199_000_000L,
                ),
            ),
        )
    }

    @Test fun usesTheActualRepository() {
        assertEquals("zenzehar/Koky-Sosy-player", UpdateChecker.REPO_SLUG)
    }

    @Test fun selectsLatestSignedPrereleaseNotGithubStableLatest() {
        val newer = UpdateChecker.newestSignedTvRelease(
            listOf(release(87), release(88), release(86, signed = false)),
            "0.1.11.87-permanent-signing-validation",
        )
        assertEquals("v0.1.11.88-signed-test", newer?.tagName)
    }

    @Test fun refusesDowngradesDraftsOrTemporarySigningBuilds() {
        assertNull(UpdateChecker.newestSignedTvRelease(
            listOf(release(87), release(88)), "0.1.11.89-signed-update-discovery"))
        assertNull(UpdateChecker.newestSignedTvRelease(
            listOf(release(90, draft = true), release(86, signed = false)), "0.1.11.88"))
        assertNull(UpdateChecker.newestSignedTvRelease(
            listOf(release(86, signed = false)), "0.1.11.85"))
    }

    @Test fun skipsNewerProductionOnlyReleaseInFavorOfTvCompatibleApk() {
        val production = GitHubAsset(
            name = "Soky_IPTV_Pro_v0.1.11.90-Production.apk",
            browserDownloadUrl = base + "v0.1.11.90/Soky_IPTV_Pro_v0.1.11.90-Production.apk",
            size = 10_000L,
        )
        val onlyProduction = release(90, assets = listOf(production))
        assertEquals("v0.1.11.88-signed-test",
            UpdateChecker.newestSignedTvRelease(
                listOf(onlyProduction, release(88)), "0.1.11.87")?.tagName)
        assertNull(onlyProduction.tvUpdateApkAsset())
    }

    @Test fun explicitlySelectsTvUpdateWhenProductionApkAppearsFirst() {
        val production = GitHubAsset(
            "Soky_IPTV_Pro_v0.1.11.90-Production.apk",
            base + "v0.1.11.90/Soky_IPTV_Pro_v0.1.11.90-Production.apk",
            199L,
        )
        val tv = GitHubAsset(
            "Soky_IPTV_Pro_v0.1.11.90-TV-Update.apk",
            base + "v0.1.11.90/Soky_IPTV_Pro_v0.1.11.90-TV-Update.apk",
            199L,
        )
        assertEquals(tv, release(90, assets = listOf(production, tv)).tvUpdateApkAsset())
        assertNull(release(90, assets = listOf(
            tv.copy(browserDownloadUrl = "https://example.invalid/unsafe.apk"),
        )).tvUpdateApkAsset())
    }

    @Test fun numericVersionComparisonDoesNotConfuseTags() {
        assertTrue(UpdateChecker.isNewer("v0.1.11.100-test", "0.1.11.99-permanent"))
        assertFalse(UpdateChecker.isNewer("v0.1.11.88-test", "0.1.11.88-signed"))
        assertFalse(UpdateChecker.isNewer("v0.1.11.88-test", "0.1.11.89"))
    }
}
