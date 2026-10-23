/*
 * Soky IPTV Pro — experimental, user-owned Google Drive progress transport.
 * GPL-3.0-or-later. Only SHA-256 media IDs, profiles, timestamps and positions
 * reach Drive; provider URLs, credentials, and full LAN/NAS bundles stay local.
 */
package app.sokyiptv.sync.cloud

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.TimeUnit
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
    // Cloud requests should never inherit the five-minute catalogue timeout.
    private val http: OkHttpClient = graph.httpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build()
    private val prefs = appContext.getSharedPreferences("soky_cloud_progress_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var periodicJob: Job? = null
    private var pushJob: Job? = null
    private var lastSyncAttemptMillis: Long = 0L
    private var cachedIndex: Pair<Map<String, String>, Map<String, String>>? = null
    private var indexCachedAtMillis = 0L
    private val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private val sharedName = "soky-progress-shared-v2.json"
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

    val state: StateFlow<CloudSyncState> = _state

    fun disconnect() {
        DriveBackgroundSyncWorker.cancel(appContext)
        oauth.disconnect()
        prefs.edit().remove("last_digest").remove("shared_file_id").apply()
        pushJob?.cancel()
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
            while (isActive) {
                if (oauth.connected) syncNow()
                delay(30_000L)
            }
        }
    }

    fun stopForegroundSync() {
        periodicJob?.cancel()
        periodicJob = null
        if (oauth.connected) scope.launch { syncNow() }
    }

    /** Debounced near-real-time upload after a saved local watch-position change. */
    fun requestSoon() {
        if (!oauth.connected) return
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            delay(2_500L)
            val wait = 12_000L - (android.os.SystemClock.elapsedRealtime() - lastSyncAttemptMillis)
            if (wait > 0L) delay(wait)
            syncNow()
        }
    }

    /** Returns true only if a complete cloud read/merge/write succeeds. */
    suspend fun syncNow(): Boolean {
        if (!oauth.connected) return false
        return gate.withLock {
            lastSyncAttemptMillis = android.os.SystemClock.elapsedRealtime()
            _state.value = _state.value.copy(busy = true, message = "Synchronising shared watch history…")
            try {
                val result = withContext(Dispatchers.IO) { performSync() }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_success", now).apply()
                _state.value = _state.value.copy(
                    connected = true, busy = false, pendingCode = null,
                    lastSuccessMillis = now,
                    importedCount = result.imported,
                    matchedCount = result.matched,
                    remoteCount = result.remote,
                    unmatchedCount = result.unmatched,
                    message = "Account-wide sync: ${result.remote} entries in one shared file; " +
                        "${result.imported} applied to this TV." +
                        if (result.unmatched > 0) " ${result.unmatched} titles retained in cloud until catalogued locally."
                        else if (result.uploaded) " Cloud file updated." else " Up to date.",
                )
                true
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(busy = false)
                throw c
            } catch (e: Exception) {
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
                .remove("shared_file_id")
                .remove("shared_parent_id")
                .apply()
            cachedIndex = null
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
                if ((name == sharedName || name.startsWith(prefix) && name.endsWith(".json")) &&
                    id.isNotBlank()) {
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
            require(parsed.version in 1..2 && parsed.positions.size <= 12_000) {
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
            val meta = JSONObject().put("name", sharedName).put("mimeType", "application/json")
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
        val now = android.os.SystemClock.elapsedRealtime()
        cachedIndex?.takeIf { now - indexCachedAtMillis < 120_000L }?.let { return it }
        val byLocalKey = HashMap<String, String>()
        val byCloudKey = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        fun add(kind: String, id: Long, url: String) {
            if (url.isBlank()) return
            val localKey = "$kind:$id"
            val digest = CloudMediaKeys.hash(kind, url)
            val cloudKey = "$kind:$digest"
            byLocalKey[localKey] = digest
            val keys = setOf(
                cloudKey,
                "$kind:${CloudMediaKeys.previousHash(kind, url)}",
                "$kind:${CloudMediaKeys.legacyHash(kind, url)}",
            )
            for (candidate in keys) {
                val prior = byCloudKey.putIfAbsent(candidate, localKey)
                if (prior != null && prior != localKey) ambiguous += candidate
            }
        }
        graph.database.movies().cloudRefs().forEach { add("movie", it.id, it.streamUrl) }
        graph.database.episodes().cloudRefs().forEach { add("ep", it.id, it.streamUrl) }
        // If the same provider URL is used for multiple local rows, we cannot know
        // which row is the correct playback history target. Skip it; never guess.
        ambiguous.forEach { byCloudKey.remove(it) }
        return (byLocalKey to byCloudKey).also {
            cachedIndex = it
            indexCachedAtMillis = now
        }
    }

    private suspend fun localSnapshot(index: Map<String, String>): CloudWatchSnapshot {
        // The same Google account is the global watch-history namespace.
        // Only the currently active local profile participates, regardless of its name or id.
        val activeProfile = graph.settings.activeProfileId.value
        val cutoff = prefs.getLong("upload_cutoff", 0L)
        val positions = graph.playbackPositions.all().mapNotNull { position ->
            if (position.updatedAtMillis < cutoff || position.profileId != activeProfile)
                return@mapNotNull null
            val key = index[position.mediaKey] ?: return@mapNotNull null
            CloudWatchPoint(
                profile = "",
                mediaHash = key,
                kind = position.mediaKey.substringBefore(':'),
                positionMillis = position.positionMillis.coerceAtLeast(0L),
                durationMillis = position.durationMillis.coerceAtLeast(0L),
                updatedAtMillis = position.updatedAtMillis,
            )
        }.sortedByDescending { it.updatedAtMillis }.take(10_000)
        return CloudWatchSnapshot(deviceId = deviceId, generatedAtMillis = System.currentTimeMillis(), positions = positions)
    }

    private data class SyncOutcome(
        val imported: Int, val matched: Int, val remote: Int,
        val unmatched: Int, val uploaded: Boolean,
    )

    /** Owner-separated on-device recovery copy, never another Drive file. */
    private fun recoveryFile(): AtomicFile {
        val owner = prefs.getString("owner_id", "").orEmpty()
        val safe = MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray(Charsets.UTF_8)).take(12)
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(appContext.filesDir, "soky-cloud-recovery-$safe.json"))
    }

    private fun readRecovery(): List<CloudWatchPoint> = runCatching {
        val bytes = recoveryFile().openRead().use { it.readBytes() }
        if (bytes.size > 3_000_000) return@runCatching emptyList()
        json.decodeFromString(CloudWatchSnapshot.serializer(), bytes.toString(Charsets.UTF_8))
            .positions
    }.getOrDefault(emptyList())

    private fun saveRecovery(snapshot: CloudWatchSnapshot) {
        val file = recoveryFile()
        val bytes = json.encodeToString(CloudWatchSnapshot.serializer(), snapshot)
            .toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun mergedPoints(vararg groups: List<CloudWatchPoint>, now: Long): List<CloudWatchPoint> =
        CloudAccountLedger.merge(now, *groups)

    private fun isCovered(actual: List<CloudWatchPoint>, expected: List<CloudWatchPoint>) =
        CloudAccountLedger.covers(actual, expected)

    private fun archiveLegacy(token: String, legacy: List<CloudFile>) {
        // Older clients still running can recreate these. Upgrade all TVs to v40.
        for (file in legacy) {
            runCatching {
                driveRequest(
                    token, Request.Builder()
                        .url("https://www.googleapis.com/drive/v3/files/" +
                            URLEncoder.encode(file.id, "UTF-8") + "?fields=id%2Ctrashed")
                        .patch(JSONObject().put("trashed", true).toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build(),
                )
            }
        }
    }

    /**
     * One Drive object per Google account, not one object per TV. On first migration
     * gather the old per-device files once. Thereafter the cached file ID permits
     * a single GET to poll the shared ledger, and a PATCH only for new progress.
     *
     * Drive v3 does not promise server-enforced conditional writes. Keep durable
     * local recovery copies, re-read immediately before/after writes, and retry
     * conflicts. Concurrent TVs converge on subsequent sync; an atomic guarantee
     * requires a transactional backend rather than one Drive JSON file.
     */
    private suspend fun performSync(): SyncOutcome {
        val token = oauth.accessToken()
        if (prefs.getString("owner_id", null).isNullOrBlank()) registerAuthorizedAccount(token)
        val now = System.currentTimeMillis()
        val cachedId = prefs.getString("shared_file_id", null)
        var shared: CloudFile? = cachedId?.let { CloudFile(it, sharedName) }
        var listing: CloudListing? = null
        var remoteSnapshot: CloudWatchSnapshot? = null
        var legacy = emptyList<CloudFile>()
        var migration = false

        if (shared != null) {
            // A user can trash the shared file manually. Re-discover once if missing.
            try {
                remoteSnapshot = downloadSnapshot(token, shared)
            } catch (e: IllegalStateException) {
                if (!e.message.orEmpty().contains("HTTP 404")) throw e
                prefs.edit().remove("shared_file_id").apply()
                shared = null
            }
        }
        if (shared == null) {
            listing = listSnapshots(token)
            val candidates = listing.files.filter { it.name == sharedName }.sortedBy { it.id }
            shared = candidates.firstOrNull()
            legacy = listing.files.filter { it.name.startsWith(prefix) }
            migration = true
            if (shared != null) remoteSnapshot = downloadSnapshot(token, shared)
        }

        val legacyPoints = if (migration) legacy.flatMap { file ->
            downloadSnapshot(token, file)?.positions.orEmpty()
        } else emptyList()
        val index = indexLocalMedia()
        val local = localSnapshot(index.first)
        val recovered = readRecovery()
        var combined = mergedPoints(
            remoteSnapshot?.positions.orEmpty(), legacyPoints, recovered, local.positions,
            now = now,
        )
        // A cloud item always belongs to this Google account. Apply it to the
        // ACTIVE local profile, never match/create cloud profile names.
        val profileId = graph.settings.activeProfileId.value
        var imported = 0
        var resolvable = 0
        for (point in combined) {
            val localKey = index.second["${point.kind}:${point.mediaHash}"] ?: continue
            resolvable++
            imported += graph.playbackPositions.mergeIfNewer(
                profileId, localKey, point.positionMillis, point.durationMillis, point.updatedAtMillis,
            )
        }
        val originalCloud = mergedPoints(remoteSnapshot?.positions.orEmpty(), now = now)
        var uploaded = false
        var verified = false
        // A local safety copy is persisted before any potentially competing Drive write.
        saveRecovery(local.copy(version = 2, positions = combined))
        for (attempt in 0..2) {
            // Refresh before replacing a shared file, reducing overwrite races.
            if (shared != null && (attempt > 0 || combined != originalCloud)) {
                val latest = downloadSnapshot(token, shared)
                combined = mergedPoints(combined, latest?.positions.orEmpty(), now = now)
            }
            if (shared != null && combined == mergedPoints(
                    remoteSnapshot?.positions.orEmpty(), now = now,
                ) && !migration) {
                verified = true
                break
            }
            // Migration or locally changed progress: create/replace the SAME shared file.
            val payload = json.encodeToString(CloudWatchSnapshot.serializer(),
                local.copy(version = 2, positions = combined))
            uploadSnapshot(token, shared, payload, listing?.parentId ?:
                prefs.getString("shared_parent_id", null) ?: "appDataFolder")
            uploaded = true
            if (shared == null) {
                // A first upload returns a new id but the existing upload helper
                // has a Unit signature. Discover this once; cached for future syncs.
                listing = listSnapshots(token)
                shared = listing.files.filter { it.name == sharedName }.minByOrNull { it.id }
                    ?: throw IllegalStateException("Drive created no shared watch-history file.")
            }
            val echoed = downloadSnapshot(token, shared)
            val confirmed = mergedPoints(echoed?.positions.orEmpty(), now = now)
            if (isCovered(confirmed, combined)) {
                verified = true
                combined = mergedPoints(confirmed, combined, now = now)
                break
            }
            combined = mergedPoints(combined, confirmed, now = now)
            delay(200L * (attempt + 1))
        }
        if (!verified) throw IllegalStateException("Concurrent Drive updates detected; recovery retained, please retry.")
        // Preserve the verified content in a recovery copy for the next race repair.
        saveRecovery(local.copy(version = 2, positions = combined))
        shared?.let {
            prefs.edit().putString("shared_file_id", it.id)
                .putString("shared_parent_id", listing?.parentId ?:
                    prefs.getString("shared_parent_id", "")).apply()
        }
        if (migration && shared != null && legacy.isNotEmpty()) {
            // Reversible trash, only after the shared ledger has been verified.
            archiveLegacy(token, legacy)
        }
        return SyncOutcome(imported, resolvable, combined.size, combined.size - resolvable, uploaded)
    }

    companion object {
        @Volatile private var instance: GoogleDriveProgressSync? = null

        fun signalLocalProgressChanged() {
            instance?.requestSoon()
        }
        fun get(context: Context): GoogleDriveProgressSync =
            instance ?: synchronized(this) {
                instance ?: GoogleDriveProgressSync(context.applicationContext).also { instance = it }
            }
    }
}
