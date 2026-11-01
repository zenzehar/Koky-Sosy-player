/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of GitHub's `releases/latest` response we care about.
 *
 * Every field defaults, so a shape change on GitHub's side (a renamed or dropped key) leaves
 * us with an empty value rather than a parse exception that would break launch. `ignoreUnknownKeys`
 * on the decoder handles the ninety other fields we do not read.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * The installed TV test app is tv.sokyiptv.pro.debug, NOT tv.sokyiptv.pro.
     * A tagged public release can publish both APKs. Never accidentally select
     * Production.apk just because it appears first in GitHub's asset list.
     *
     * Our CI publishes "TV-Update" for debug-ID APKs; PR builds publish
     * "Soky_IPTV_Pro_v..." as the only asset.
     */
    fun tvUpdateApkAsset(): GitHubAsset? {
        val compatible = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.size > 0L &&
                asset.browserDownloadUrl.startsWith(
                    "https://github.com/zenzehar/Koky-Sosy-player/releases/download/"
                ) &&
                !asset.name.contains("Production", ignoreCase = true)
        }
        return compatible.firstOrNull {
            it.name.contains("TV-Update", ignoreCase = true) ||
                it.name.contains("TV_Update", ignoreCase = true)
        } ?: compatible.firstOrNull {
            it.name.startsWith("Soky_IPTV_Pro_v", ignoreCase = true)
        }
    }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)
