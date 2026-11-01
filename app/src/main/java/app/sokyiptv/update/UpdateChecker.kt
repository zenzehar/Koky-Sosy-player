/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.update

import app.sokyiptv.data.remote.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks whether a newer SokyIPTV release has been published, so a sideloaded install can
 * update itself.
 *
 * Sideloaded apps get no automatic updates — nothing on the device knows to look. Without
 * this, whoever installs v0.1 stays on v0.1 until they happen to hear there is a newer one
 * and go and fetch it by hand. The app store equivalents (Play, Amazon) push updates for
 * you; the Downloader-app install route we ship through does not, so the app has to do it.
 *
 * ## Design
 * - **Silent on failure.** No network, rate-limited, a 404 because the release does not exist
 *   yet — all of it returns "no update" and never surfaces an error. An update check is a
 *   nicety; it must never be a thing that breaks or nags at launch.
 * - **Checks the actual release repository.** The old repo slug did not exist.
 * - **Includes owner-signed test prereleases.** Every v85-v88 APK was a GitHub
 *   prerelease; GitHub's /releases/latest endpoint intentionally omitted them.
 * - **Checks compatibility** with the permanent signing era and the installed
 *   TV-test (.debug) app ID, not the separately published production APK.
 * - **Compares numeric tags**, not publication time, and never downgrades.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionName: String,
    private val repoSlug: String = REPO_SLUG,
) {
    data class Update(
        val versionName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the newer release, or null if we are current / could not tell. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoSlug/releases?per_page=50")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SokyIPTV")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null

                val releases = json.decodeFromString<List<GitHubRelease>>(body)
                val release = newestSignedTvRelease(releases, currentVersionName)
                    ?: return@withContext null
                val asset = release.tvUpdateApkAsset() ?: return@withContext null

                Update(
                    versionName = release.tagName.trimStart('v', 'V'),
                    title = release.name.ifBlank { release.tagName },
                    notes = cleanNotes(release.body),
                    apkUrl = asset.browserDownloadUrl,
                    apkSizeBytes = asset.size,
                    releaseUrl = release.htmlUrl,
                )
            }
        }.getOrNull()
    }

    companion object {
        /** The single place the project's GitHub location is written down. */
        const val REPO_SLUG = "zenzehar/Koky-Sosy-player"

        /**
         * Releases for builds before v87 used temporary debug signing keys and
         * cannot be installed over permanent-key-signed packages.
         */
        private const val FIRST_PERMANENT_VERSION = "0.1.11.87"

        /**
         * Public GitHub release metadata identifies the permanent-key builds.
         * This does not replace Android certificate verification at install;
         * it avoids recommending older incompatible CI debug builds.
         *
         * Include prereleases because this project publishes signed TV test
         * builds on draft pull requests. All other releases are ignored unless
         * they meet BOTH the permanent signing marker and TV APK requirements.
         */
        fun newestSignedTvRelease(
            releases: List<GitHubRelease>,
            current: String,
        ): GitHubRelease? = releases.asSequence()
            .filter { !it.draft }
            .filter { isNewer(it.tagName, current) }
            .filter { !isNewer(FIRST_PERMANENT_VERSION, it.tagName) }
            .filter {
                it.body.contains("permanent", ignoreCase = true) &&
                    it.body.contains("sign", ignoreCase = true)
            }
            .filter { it.tvUpdateApkAsset() != null }
            .maxWithOrNull(Comparator { a, b -> compareVersion(a.tagName, b.tagName) })

        private fun compareVersion(a: String, b: String): Int {
            val left = versionParts(a)
            val right = versionParts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val difference = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (difference != 0) return difference
            }
            return 0
        }

        /**
         * The GitHub release body is written for developers and has GitHub's auto-generated
         * commit list appended. Turn it into a few clean "what's new" lines for the update
         * dialog: drop the build/verify/signing boilerplate and the auto-changelog, and flatten
         * the Markdown that a plain dialog would otherwise show as literal `**`, `[x](y)`, `#`.
         * If nothing user-facing is left, returns empty and the dialog simply omits the notes.
         */
        fun cleanNotes(raw: String): String {
            var text = raw
            // Everything from the first boilerplate / auto-generated marker onward is noise.
            val cutFrom = listOf(
                "Built by GitHub Actions",
                "**Full Changelog**",
                "## What's Changed",
                "<!--",
            )
            for (marker in cutFrom) {
                val i = text.indexOf(marker, ignoreCase = true)
                if (i >= 0) text = text.substring(0, i)
            }
            text = text
                .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1") // [label](url) -> label
                .replace(Regex("""[*_]{1,3}([^*_\n]+)[*_]{1,3}"""), "$1") // **bold**/_em_ -> plain
                .replace("`", "")                                   // inline-code ticks
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")    // ## headers -> text
                .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")        // "- item" -> "• item"
                .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "")        // --- horizontal rules
            return text.lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .take(1200)
        }

        /**
         * True when [tag] names a strictly higher version than [current].
         *
         * Both are reduced to lists of integers (`v0.2.0` → 0,2,0) and compared left to right.
         * A missing or non-numeric component counts as 0, so `0.2` and `0.2.0` are equal and a
         * malformed tag simply never wins.
         */
        fun isNewer(tag: String, current: String): Boolean =
            compareVersion(tag, current) > 0

        private fun versionParts(v: String): List<Int> =
            v.trim().trimStart('v', 'V')
                .split('.', '-', '+')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
