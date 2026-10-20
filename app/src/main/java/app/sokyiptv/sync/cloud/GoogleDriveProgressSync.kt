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
        // Discard temporary secrets entered in the prior test release. New builds never
        // ask the user to supply an OAuth credential.
        vault.write("test_secret", null)
    }

    val state: StateFlow<CloudSyncState> = _state

    fun disconnect() {
        oauth.disconnect()
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
            syncNow()
        } catch (c: CancellationException) {
            _state.value = _state.value.copy(busy = false, pendingCode = null, message = "Sign-in cancelled.")
            throw c
        } catch (e: Exception) {
            if (oauth.connected && _state.value.connected != true) oauth.disconnect()
            _state.value = _state.value.copy(busy = false, pendingCode = null, connected = oauth.connected, message = safeMessage(e))
        }
    }

    fun startForegroundSync() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
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

    suspend fun syncNow() {
        if (!oauth.connected) return
        gate.withLock {
            _state.value = _state.value.copy(busy = true, message = "Synchronising watch history…")
            try {
                val (count, uploaded) = withContext(Dispatchers.IO) { performSync() }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_success", now).apply()
                _state.value = _state.value.copy(
                    connected = true, busy = false, pendingCode = null,
                    lastSuccessMillis = now, importedCount = count,
                    message = "Cloud sync complete: $count positions updated locally" +
                        if (uploaded) " and your latest progress uploaded." else "; cloud upload already current.",
                )
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(busy = false)
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, message = safeMessage(e))
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
        http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive request failed (HTTP ${response.code}).")
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
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
        return CloudListing(found, folderId)
    }

    private fun downloadSnapshot(token: String, file: CloudFile): CloudWatchSnapshot? {
        val url = "https://www.googleapis.com/drive/v3/files/" +
            URLEncoder.encode(file.id, "UTF-8") + "?alt=media"
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Cannot download Drive progress (HTTP ${response.code}).")
            // Limit damage from accidentally huge/corrupt cloud files.
            val payload = response.body?.bytes() ?: return null
            if (payload.size > 3_000_000) throw IllegalStateException("Cloud watch-history file is unexpectedly large.")
            return runCatching { json.decodeFromString(CloudWatchSnapshot.serializer(), String(payload, Charsets.UTF_8)) }
                .getOrNull()?.takeIf { it.version == 1 && it.positions.size <= 12_000 }
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

    /** Only SHA256s of local stream URLs are used for the cross-install lookup. */
    private suspend fun indexLocalMedia(): Pair<Map<String, String>, Map<String, String>> {
        val byLocalKey = HashMap<String, String>()
        val byCloudKey = HashMap<String, String>()
        graph.database.movies().cloudRefs().forEach { row ->
            if (row.streamUrl.isNotBlank()) {
                val local = "movie:${row.id}"
                val key = CloudMediaKeys.hash("movie", row.streamUrl)
                byLocalKey[local] = key
                byCloudKey.putIfAbsent("movie:$key", local)
            }
        }
        graph.database.episodes().cloudRefs().forEach { row ->
            if (row.streamUrl.isNotBlank()) {
                val local = "ep:${row.id}"
                val key = CloudMediaKeys.hash("ep", row.streamUrl)
                byLocalKey[local] = key
                byCloudKey.putIfAbsent("ep:$key", local)
            }
        }
        return byLocalKey to byCloudKey
    }

    private suspend fun localSnapshot(index: Map<String, String>): CloudWatchSnapshot {
        val names = graph.profiles.all().associate { it.id to it.name }
        // A different Google account must not inherit historical watch positions from the
        // preceding account on this device. Remote points still merge into local Room safely.
        val cutoff = prefs.getLong("upload_cutoff", 0L)
        val positions = graph.playbackPositions.all().mapNotNull { position ->
            if (position.updatedAtMillis < cutoff) return@mapNotNull null
            val name = names[position.profileId]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = index[position.mediaKey] ?: return@mapNotNull null
            CloudWatchPoint(
                profile = name,
                mediaHash = key,
                kind = position.mediaKey.substringBefore(':'),
                positionMillis = position.positionMillis.coerceAtLeast(0L),
                durationMillis = position.durationMillis.coerceAtLeast(0L),
                updatedAtMillis = position.updatedAtMillis,
            )
        }.sortedByDescending { it.updatedAtMillis }.take(10_000)
        return CloudWatchSnapshot(deviceId = deviceId, generatedAtMillis = System.currentTimeMillis(), positions = positions)
    }

    private suspend fun performSync(): Pair<Int, Boolean> {
        val token = oauth.accessToken()
        registerAuthorizedAccount(token)
        val listing = listSnapshots(token)
        val files = listing.files
        val remote = mutableMapOf<String, CloudWatchPoint>()
        // Own snapshot is included so remote points survive reinstall even if only one device exists.
        for (file in files) {
            val snapshot = downloadSnapshot(token, file) ?: continue
            for (item in snapshot.positions) {
                if (item.kind !in setOf("movie", "ep") || item.profile.isBlank() ||
                    item.mediaHash.length != 64 || item.updatedAtMillis <= 0) continue
                val combinedKey = item.profile + "\u0000" + item.kind + "\u0000" + item.mediaHash
                val previous = remote[combinedKey]
                if (previous == null || item.updatedAtMillis > previous.updatedAtMillis) {
                    remote[combinedKey] = item
                }
            }
        }

        val (byLocal, byCloud) = indexLocalMedia()
        val existingProfiles = graph.profiles.all().associate { it.name to it.id }.toMutableMap()
        val now = System.currentTimeMillis()
        var imported = 0
        for (point in remote.values) {
            // Don't import timestamps far in the future from a clock that's badly misconfigured.
            if (point.updatedAtMillis > now + 5 * 60_000L) continue
            val localKey = byCloud["${point.kind}:${point.mediaHash}"] ?: continue
            val profileId = existingProfiles[point.profile] ?: run {
                graph.profiles.insert(Profile(name = point.profile, createdAtMillis = now)).also {
                    existingProfiles[point.profile] = it
                }
            }
            val current = graph.playbackPositions.get(profileId, localKey)
            if (current == null || point.updatedAtMillis > current.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = localKey,
                        positionMillis = point.positionMillis.coerceAtLeast(0L),
                        durationMillis = point.durationMillis.coerceAtLeast(0L),
                        updatedAtMillis = point.updatedAtMillis,
                    )
                )
                imported++
            }
        }

        // Preserve this Google account's existing Drive progress when the same TV switches
        // away to another account and later returns. Uploading only positions newer than
        // the account-switch cutoff would otherwise overwrite its previous cloud file with
        // an empty/partial snapshot and erase watched positions.
        val local = localSnapshot(byLocal)
        val merged = LinkedHashMap<String, CloudWatchPoint>()
        for (point in remote.values + local.positions) {
            val key = point.profile + "\u0000" + point.kind + "\u0000" + point.mediaHash
            val existing = merged[key]
            if (existing == null || point.updatedAtMillis > existing.updatedAtMillis) {
                merged[key] = point
            }
        }
        val snapshot = local.copy(
            positions = merged.values.sortedByDescending { it.updatedAtMillis }.take(10_000),
        )
        val payload = json.encodeToString(CloudWatchSnapshot.serializer(), snapshot)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            json.encodeToString(snapshot.positions).toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val currentOwnFile = files.firstOrNull { it.name == ownName }
        val alreadyUploaded = currentOwnFile != null && prefs.getString("last_digest", null) == digest
        if (!alreadyUploaded) {
            uploadSnapshot(token, currentOwnFile, payload, listing.parentId)
            prefs.edit().putString("last_digest", digest).apply()
        }
        return imported to !alreadyUploaded
    }

    companion object {
        @Volatile private var instance: GoogleDriveProgressSync? = null
        fun get(context: Context): GoogleDriveProgressSync =
            instance ?: synchronized(this) {
                instance ?: GoogleDriveProgressSync(context.applicationContext).also { instance = it }
            }
    }
}
