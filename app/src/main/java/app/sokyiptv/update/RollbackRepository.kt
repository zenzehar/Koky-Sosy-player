package app.sokyiptv.update

import app.sokyiptv.data.remote.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Only the explicitly named, signed, same-release rollback companion is eligible. */
internal class RollbackRepository(private val http: OkHttpClient) {
    data class Asset(val url: String, val bytes: Long)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(window: RollbackStore.Window): Asset? = withContext(Dispatchers.IO) {
        runCatching {
            val tag = "v" + window.sourceVersion
            val request = Request.Builder()
                .url("https://api.github.com/repos/" + UpdateChecker.REPO_SLUG +
                    "/releases/tags/" + tag)
                .header("User-Agent", "SokyIPTV")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val release = json.decodeFromString<GitHubRelease>(
                    response.body?.string().orEmpty()
                )
                if (release.draft || release.tagName != tag ||
                    !release.body.contains("permanent", ignoreCase = true) ||
                    !release.body.contains("sign", ignoreCase = true)) return@withContext null

                val fileName = "Soky_IPTV_Pro_Rollback_to_v" +
                    window.previousVersion + ".apk"
                release.assets.firstOrNull {
                    it.name == fileName && it.size > 0L &&
                        it.browserDownloadUrl ==
                            "https://github.com/" + UpdateChecker.REPO_SLUG +
                                "/releases/download/" + tag + "/" + fileName
                }?.let { Asset(it.browserDownloadUrl, it.size) }
            }
        }.getOrNull()
    }
}
