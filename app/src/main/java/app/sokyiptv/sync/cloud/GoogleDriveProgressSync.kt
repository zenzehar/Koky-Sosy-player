/*
 * Soky IPTV Pro — experimental, user-owned Google Drive progress transport.
 * GPL-3.0-or-later. Only SHA-256 media IDs, profiles, timestamps and positions
 * reach Drive; provider URLs, credentials, and full LAN/NAS bundles stay local.
 */
package app.sokyiptv.sync.cloud

import android.content.Context
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.data.model.PlaybackPosition
import app.sokyiptv.data.model.Profile
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class CloudSyncState(
    val connected: Boolean = false,
    val configured: Boolean = true,
    val accountLabel: String = "",
    val pendingCode: TvGoogleCode? = null,
    val busy: Boolean = false,
    val message: String = "",
    val lastSuccessMillis: Long = 0,
    val importedCount: Int = 0,
    val matchedCount: Int = 0,
    val remoteCount: Int = 0,
    val unmatchedCount: Int = 0,
)

class GoogleDriveProgressSync private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val graph = ServiceLocator.get(appContext)
    private val vault = CloudCredentialVault(appContext)
    private val oauth = GoogleTvOAuth(graph.httpClient, vault)
    private val http: OkHttpClient = graph.httpClient
    private val prefs = appContext.getSharedPreferences("soky_cloud_progress_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicJob: Job? = null
    @Volatile private var lastAttemptFailed: Boolean = false
    private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private val ownName = "soky-progress-v1-$deviceId.json"
    private val prefix = "soky-progress-v1-"

    private val _state = MutableStateFlow(
        CloudSyncState(
            connected = oauth.connected,
            configured = oauth.configured,
            accountLabel = prefs.getString("owner_email", "").orEmpty(),
            lastSuccessMillis = prefs.getLong("last_success", 0),
        )
    )
    init {
        vault.write("test_secret", null)
        // Re-establish periodic background work after Android process restart.
        if (oauth.connected) DriveBackgroundSyncWorker.schedule(appContext)
    }
    val authorized: Boolean get() = oauth.connected

    /** LAN reads this signal but NEVER triggers an additional cloud scan itself. */
    val lanFallbackNeeded: Boolean get() = !oauth.connected || lastAttemptFailed

    val state: StateFlow<CloudSyncState> = _state

    fun disconnect() {
        DriveBackgroundSyncWorker.cancel(appContext)
        oauth.disconnect()
        lastAttemptFailed = false
        prefs.edit().remove("last_digest").apply()
        _state.value = CloudSyncState(
            connected = false,
            configured = oauth.configured,
            message = "Google account disconnected. Local viewing history remains on this device.",
        )
    }

    suspend fun connect() {
        _state.value = _state.value.copy(busy = true, pendingCode = null, message = "Requesting a Google sign-in code…")
        try {
            val code = oauth.createDeviceCode()
            _state.value = _state.value.copy(busy = true, pendingCode = code, message = "Open the link on your phone, then enter the code.")
            oauth.waitForUser(code)
            // Resolve identity on Dispatchers.IO. The UI coroutine resumes on Main
            // after OAuth polling. Calling OkHttp.execute() there caused a
            // NetworkOnMainThreadException and discarded an otherwise valid sign-in.
            // Both Drive about.get and files.list use the authenticated user's token.
            val token = oauth.accessToken()
            _state.value = _state.value.copy(
                busy = true, pendingCode = null, message = "Google approved your account. Checking Drive access…",
            )
            withContext(Dispatchers.IO) { registerAuthorizedAccount(token) }
            prefs.edit().remove("last_digest").apply()
            _state.value = _state.value.copy(
                connected = true, pendingCode = null, busy = false, message = "Connected to Google Drive. Synchronising…",
            )
            DriveBackgroundSyncWorker.schedule(appContext)
            // Do not tie initial upload to a transient Settings/QR dialog.
            // The app-owned scope keeps going when the user leaves the screen.
            scope.launch { syncNow() }
        } catch (c: CancellationException) {
            if (oauth.connected) {
                _state.value = _state.value.copy(
                    connected = true, busy = false, pendingCode = null,
                    message = "Google authorised. Sync will continue in the background.",
                )
                DriveBackgroundSyncWorker.schedule(appContext)
                scope.launch { syncNow() }
            } else {
                _state.value = _state.value.copy(busy = false, pendingCode = null, message = "Sign-in cancelled.")
            }
            throw c
        } catch (e: Exception) {
            if (oauth.connected && _state.value.connected != true) oauth.disconnect()
            _state.value = _state.value.copy(busy = false, pendingCode = null, connected = oauth.connected, message = safeMessage(e))
        }
    }

    fun startForegroundSync() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            // Let source catalogues and VOD home screens become responsive
            // before the first full remote-index reconciliation on app launch.
            delay(20_000L)
            while (isActive) {
                if (oauth.connected) syncNow()
                delay(120_000L)
            }
        }
    }

    fun stopForegroundSync() {
        periodicJob?.cancel()
        periodicJob = null
        if (oauth.connected) scope.launch { syncNow() }
    }

    /** Returns true only if a complete cloud read/merge/write succeeds. */
    suspend fun syncNow(): Boolean {
        if (!oauth.connected) return false
        return gate.withLock {
            _state.value = _state.value.copy(busy = true, message = "Synchronising watch history…")
            try {
                val result = withContext(Dispatchers.IO) { performSync() }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_success", now).apply()
                lastAttemptFailed = false
                _state.value = _state.value.copy(
                    connected = true, busy = false, pendingCode = null,
                    lastSuccessMillis = now,
                    importedCount = result.imported,
                    matchedCount = result.matched,
                    remoteCount = result.remote,
                    unmatchedCount = result.unmatched,
                    message = "Drive sync: ${result.imported} new positions imported; " +
                        "${result.matched}/${result.remote} remote records matched this TV." +
                        if (result.unmatched > 0) " ${result.unmatched} skipped (missing or different catalogue URLs)." else
                            if (result.uploaded) " Progress uploaded." else " Already up to date.",
                )
                true
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(busy = false)
                throw c
            } catch (e: Exception) {
                lastAttemptFailed = true
                _state.value = _state.value.copy(busy = false, message = safeMessage(e))
                false
            }
        }
    }

    private fun safeMessage(e: Exception): String {
        // Never display tokens, signed URLs, request bodies, or Google error payloads.
        return when (e) {
            is android.os.NetworkOnMainThreadException ->
                "Internal TV networking error. Update to the latest test APK."
            is java.net.UnknownHostException ->
                "Cannot reach Google Drive. Check the TV's internet connection."
            is java.net.SocketTimeoutException ->
                "Google Drive timed out. Select Sync Now to retry."
            is javax.net.ssl.SSLException ->
                "Unable to establish a secure Google connection. Check the TV clock and network."
            is IllegalStateException, is IllegalArgumentException ->
                e.message?.take(170) ?: "Google Drive request failed."
            else ->
                // Show only the exception class, never OAuth tokens or raw HTTP bodies.
                "Cloud connection failed (${e.javaClass.simpleName}). Retry, then report this error type."
        }
    }

    private data class CloudFile(val id: String, val name: String)
    private data class CloudListing(val files: List<CloudFile>, val parentId: String)
    private val visibleFolderName = "Soky IPTV Pro"

    private fun driveRequest(token: String, request: Request): JSONObject {
        val authenticated = request.newBuilder().header("Authorization", "Bearer $token").build()
        val repeatable = authenticated.method == "GET" || authenticated.method == "PATCH"
        for (attempt in 0..2) {
            http.newCall(authenticated).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return if (body.isBlank()) JSONObject() else JSONObject(body)
                }
                if (repeatable && attempt < 2 &&
                    response.code in setOf(429, 500, 502, 503, 504)) {
                    // GET and media-PATCH may be repeated safely. Never retry a create
                    // POST automatically: the server may have created the file already.
                    Thread.sleep(500L * (1L shl attempt))
                } else {
                    throw IllegalStateException("Google Drive request failed (HTTP ${response.code}).")
                }
            }
        }
        throw IllegalStateException("Google Drive remained unavailable after retries.")
    }

    /**
     * Each installation can switch between Google accounts. Never upload the previous account's
     * existing local history into a NEW Google Drive when the account changes.
     * Existing Room history stays local; only watch events after the account change may upload.
     */
    private fun registerAuthorizedAccount(token: String) {
        val about = driveRequest(
            token,
            Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user(permissionId%2CemailAddress)").get().build(),
        ).optJSONObject("user") ?: throw IllegalStateException("Google Drive did not identify the connected account.")
        val owner = about.optString("permissionId")
        require(owner.isNotBlank()) { "The Google account identifier was unavailable. Try signing in again." }
        val email = about.optString("emailAddress").take(120)
        val previous = prefs.getString("owner_id", null)
        if (previous != null && previous != owner) {
            prefs.edit()
                .putLong("upload_cutoff", System.currentTimeMillis())
                .remove("last_digest")
                .remove("last_success")
                .apply()
        }
        prefs.edit().putString("owner_id", owner).putString("owner_email", email).apply()
        _state.value = _state.value.copy(accountLabel = email)
    }

    /**
     * Google TV's device-code endpoint sometimes returns invalid_scope for
     * drive.appdata even though it is listed in the official allowed scopes.
     * The drive.file alternative uses a visible, app-created folder in My Drive.
     * No pre-existing user files are accessed.
     */
    private fun findOrCreateVisibleFolder(token: String): String {
        val query = "name = '$visibleFolderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val url = "https://www.googleapis.com/drive/v3/files?fields=nextPageToken%2Cfiles(id%2Cname)" +
            "&pageSize=100&q=" + URLEncoder.encode(query, "UTF-8")
        val response = driveRequest(token, Request.Builder().url(url).get().build())
        val files = response.optJSONArray("files")
        val candidates = mutableListOf<String>()
        if (files != null) for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            if (file.optString("name") == visibleFolderName) {
                file.optString("id").takeIf { it.isNotBlank() }?.let { candidates += it }
            }
        }
        // For typical accounts there is one application-created folder. If a
        // former device created a duplicate, consistently pick the first ID.
        if (candidates.isNotEmpty()) return candidates.minOrNull()!!
        val body = JSONObject()
            .put("name", visibleFolderName)
            .put("mimeType", "application/vnd.google-apps.folder")
            .toString()
        val request = Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val id = driveRequest(token, request).optString("id")
        require(id.isNotBlank()) { "Google Drive did not return the sync folder ID." }
        return id
    }

    private fun listSnapshots(token: String): CloudListing {
        val found = mutableListOf<CloudFile>()
        val folderId = if (oauth.usingDriveFile) findOrCreateVisibleFolder(token) else "appDataFolder"
        var page: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?")
                if (!oauth.usingDriveFile) append("spaces=appDataFolder&")
                val folderQuery = "'$folderId' in parents and trashed = false"
                append("q=").append(URLEncoder.encode(folderQuery, "UTF-8"))
                append("&fields=nextPageToken%2Cfiles(id%2Cname)&pageSize=100")
                page?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val objectList = driveRequest(token, Request.Builder().url(url).get().build())
            val files = objectList.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val id = item.optString("id")
                if (name.startsWith(prefix) && name.endsWith(".json") && id.isNotBlank()) {
                    found += CloudFile(id, name)
                }
            }
            page = objectList.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null && found.size < 1000)
        require(page == null) { "More than 1,000 Drive sync files detected. Sync cancelled to prevent partial overwrite." }
        return CloudListing(found, folderId)
    }

    private fun downloadSnapshot(token: String, file: CloudFile): CloudWatchSnapshot? {
        val url = "https://www.googleapis.com/drive/v3/files/" +
            URLEncoder.encode(file.id, "UTF-8") + "?alt=media"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Cannot download Drive progress (HTTP ${response.code}).")
            // Limit damage from accidentally huge/corrupt cloud files.
            val payload = response.body?.bytes()
                ?: throw IllegalStateException("A Drive sync file had no content. No cloud data changed.")
            if (payload.size > 3_000_000) throw IllegalStateException("Cloud watch-history file is unexpectedly large.")
            val parsed = runCatching { json.decodeFromString(CloudWatchSnapshot.serializer(), String(payload, Charsets.UTF_8)) }
                .getOrElse { throw IllegalStateException("Drive contains an unreadable sync file. No cloud data changed.") }
            require(parsed.version == 1 && parsed.positions.size <= 12_000) {
                "Unsupported or excessively large Drive history detected. No cloud data changed."
            }
            return parsed
        }
    }

    private fun uploadSnapshot(token: String, existing: CloudFile?, payload: String, parentId: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = if (existing != null) {
            val url = "https://www.googleapis.com/upload/drive/v3/files/" +
                URLEncoder.encode(existing.id, "UTF-8") + "?uploadType=media"
            Request.Builder().url(url).patch(payload.toRequestBody(mediaType)).build()
        } else {
            val boundary = "soky-" + UUID.randomUUID().toString()
            val meta = JSONObject().put("name", ownName).put("mimeType", "application/json")
                .put("parents", org.json.JSONArray().put(parentId)).toString()
            val multipart =
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta +
                "\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                payload + "\r\n--$boundary--\r\n"
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .post(multipart.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
        }
        driveRequest(token, request)
    }

    /** Only the active viewing profile participates in this phase of sync. */
    private suspend fun localSnapshot(index: ProgressMediaIndex): CloudWatchSnapshot {
        val cutoff = prefs.getLong("upload_cutoff", 0L)
        val profileId = graph.settings.activeProfileId.value
        val newest = linkedMapOf<String, CloudWatchPoint>()
        for (position in graph.playbackPositions.forProfile(profileId)
            .sortedByDescending { it.updatedAtMillis }) {
            if (position.updatedAtMillis < cutoff) continue
            val item = index.byLocal[position.mediaKey] ?: continue
            val point = CloudWatchPoint(
                profile = "active",
                mediaHash = item.hash,
                kind = item.key.substringBefore(':'),
                positionMillis = position.positionMillis.coerceAtLeast(0L),
                durationMillis = position.durationMillis.coerceAtLeast(0L),
                updatedAtMillis = position.updatedAtMillis,
                logicalHash = item.logicalHash,
                identityAliases = item.aliases.toList(),
            )
            newest.putIfAbsent(item.logicalHash, point)
        }
        return CloudWatchSnapshot(
            deviceId = deviceId, generatedAtMillis = System.currentTimeMillis(),
            positions = newest.values.take(10_000),
        )
    }

    private data class SyncOutcome(
        val imported: Int, val matched: Int, val remote: Int,
        val unmatched: Int, val uploaded: Boolean,
    )

    private suspend fun performSync(): SyncOutcome {
        val token = oauth.accessToken()
        registerAuthorizedAccount(token)
        val listing = listSnapshots(token)
        val files = listing.files
        val now = System.currentTimeMillis()
        val index = ProgressMediaIndex.build(graph)
        val raw = mutableListOf<CloudWatchPoint>()
        // Read every file before writing anything. Never truncate bad or missing snapshots.
        for (file in files) {
            val snapshot = downloadSnapshot(token, file) ?: continue
            require(snapshot.version == 1) { "Unsupported cloud history. No changes made." }
            raw += snapshot.positions
        }
        val newest = linkedMapOf<String, Pair<CloudWatchPoint, ProgressMediaIndex.Media>>()
        var matched = 0
        for (point in raw) {
            if (!CloudMergeRules.valid(point, now)) continue
            val media = index.findAny(point.kind, listOf(point.mediaHash) + point.identityAliases) ?: continue
            if (!ProgressMediaIndex.plausibleDuration(media.durationMillis, point.durationMillis)) continue
            matched++
            val existing = newest[media.logicalHash]
            if (existing == null || point.updatedAtMillis > existing.first.updatedAtMillis) {
                newest[media.logicalHash] = point to media
            }
        }
        val profileId = graph.settings.activeProfileId.value
        // The locally newest title or episode wins, including when a different
        // episode of the same series was watched last. Never import older data.
        val localNewest = graph.playbackPositions.forProfile(profileId)
            .mapNotNull { progress ->
                index.byLocal[progress.mediaKey]?.let { it.logicalHash to progress.updatedAtMillis }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, stamps) -> stamps.maxOrNull() ?: 0L }
        var imported = 0
        for ((logical, pair) in newest) {
            val (point, media) = pair
            if (point.updatedAtMillis <= (localNewest[logical] ?: 0L)) continue
            imported += graph.playbackPositions.mergeIfNewer(
                profileId, media.key, point.positionMillis, point.durationMillis, point.updatedAtMillis,
            )
        }
        // Per-device snapshot, not a mirrored union of other users' old snapshots.
        // This avoids exponentially duplicating cloud files and resurrecting stale episodes.
        val snapshot = localSnapshot(index)
        val payload = json.encodeToString(CloudWatchSnapshot.serializer(), snapshot)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            json.encodeToString(snapshot.positions).toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val current = files.firstOrNull { it.name == ownName }
        val alreadyUploaded = current != null && prefs.getString("last_digest", null) == digest
        if (!alreadyUploaded) {
            uploadSnapshot(token, current, payload, listing.parentId)
            prefs.edit().putString("last_digest", digest).apply()
        }
        return SyncOutcome(imported, newest.size, raw.size, raw.size - matched, !alreadyUploaded)
    }

    companion object {
        @Volatile private var instance: GoogleDriveProgressSync? = null
        fun get(context: Context): GoogleDriveProgressSync =
            instance ?: synchronized(this) {
                instance ?: GoogleDriveProgressSync(context.applicationContext).also { instance = it }
            }
    }
}
