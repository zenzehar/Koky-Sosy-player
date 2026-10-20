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
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class CloudSyncState(
    val connected: Boolean = false,
    val hasTestClientSecret: Boolean = false,
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
            hasTestClientSecret = oauth.hasTestSecret(),
            lastSuccessMillis = prefs.getLong("last_success", 0),
        )
    )
    val state: StateFlow<CloudSyncState> = _state

    fun saveTestSecret(value: String) {
        require(value.isNotBlank()) { "The temporary client secret cannot be blank." }
        vault.write("test_secret", value.trim())
        _state.value = _state.value.copy(hasTestClientSecret = oauth.hasTestSecret(), message = "Test credentials saved on this device.")
    }

    fun disconnect() {
        oauth.disconnect()
        prefs.edit().remove("last_digest").apply()
        _state.value = CloudSyncState(
            connected = false,
            hasTestClientSecret = oauth.hasTestSecret(),
            message = "Google account disconnected from this device.",
        )
    }

    suspend fun connect() {
        _state.value = _state.value.copy(busy = true, pendingCode = null, message = "Requesting a Google sign-in code…")
        try {
            val code = oauth.createDeviceCode()
            _state.value = _state.value.copy(busy = true, pendingCode = code, message = "Open the link on your phone, then enter the code.")
            oauth.waitForUser(code)
            prefs.edit().remove("last_digest").apply()
            _state.value = _state.value.copy(
                connected = true, pendingCode = null, busy = false, message = "Connected to Google Drive. Synchronising…",
            )
            syncNow()
        } catch (c: CancellationException) {
            _state.value = _state.value.copy(busy = false, pendingCode = null, message = "Sign-in cancelled.")
            throw c
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, pendingCode = null, message = safeMessage(e))
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
            is IllegalStateException, is IllegalArgumentException -> e.message?.take(170) ?: "Cloud sync failed."
            else -> "Cloud sync failed. Check your internet connection and Google account permissions."
        }
    }

    private data class CloudFile(val id: String, val name: String)

    private fun driveRequest(token: String, request: Request): JSONObject {
        http.newCall(request.newBuilder().header("Authorization", "Bearer $token").build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Google Drive request failed (HTTP ${response.code}).")
            }
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun listSnapshots(token: String): List<CloudFile> {
        val found = mutableListOf<CloudFile>()
        var page: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder")
                append("&fields=nextPageToken%2Cfiles(id%2Cname)&pageSize=100")
                if (page != null) append("&pageToken=").append(URLEncoder.encode(page, "UTF-8"))
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
        return found
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

    private fun uploadSnapshot(token: String, existing: CloudFile?, payload: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = if (existing != null) {
            val url = "https://www.googleapis.com/upload/drive/v3/files/" +
                URLEncoder.encode(existing.id, "UTF-8") + "?uploadType=media"
            Request.Builder().url(url).patch(payload.toRequestBody(mediaType)).build()
        } else {
            val boundary = "soky-" + UUID.randomUUID().toString()
            val meta = JSONObject().put("name", ownName).put("mimeType", "application/json")
                .put("parents", org.json.JSONArray().put("appDataFolder")).toString()
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
        val positions = graph.playbackPositions.all().mapNotNull { position ->
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
        val files = listSnapshots(token)
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

        val snapshot = localSnapshot(byLocal)
        val payload = json.encodeToString(CloudWatchSnapshot.serializer(), snapshot)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            json.encodeToString(snapshot.positions).toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        val currentOwnFile = files.firstOrNull { it.name == ownName }
        val alreadyUploaded = currentOwnFile != null && prefs.getString("last_digest", null) == digest
        if (!alreadyUploaded) {
            uploadSnapshot(token, currentOwnFile, payload)
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
