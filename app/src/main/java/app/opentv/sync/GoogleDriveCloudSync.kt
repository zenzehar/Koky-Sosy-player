package app.opentv.sync

import android.content.Context
import app.opentv.BuildConfig
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Serverless Google Drive appDataFolder transport.
 *
 * Never sends provider credentials or an unencrypted SyncBundle to Google.
 * Each device owns one encrypted file. Reads all peer files and merges latest
 * positions by (profile, kind, stream URL), avoiding write races between devices.
 */
class GoogleDriveCloudSync private constructor(context: Context) {
    private val graph = ServiceLocator.get(context)
    private val vault = GoogleSyncVault(context)
    private val engine = SyncEngine(graph)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    data class Status(
        val connected: Boolean = false,
        val busy: Boolean = false,
        val lastSuccessMillis: Long = 0L,
        val description: String = "Not connected",
    )
    data class DeviceCode(
        val code: String,
        val url: String,
        val expiresSeconds: Int,
        val pollingSeconds: Int,
        internal val token: String,
    )

    private val _status = MutableStateFlow(Status(connected = vault.read(GoogleSyncVault.REFRESH_TOKEN) != null))
    val status = _status.asStateFlow()
    val configured: Boolean get() = BuildConfig.GOOGLE_TV_CLIENT_ID.isNotBlank() &&
        BuildConfig.GOOGLE_TV_CLIENT_SECRET.isNotBlank()

    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        require(configured) { "Google TV OAuth credentials are not configured in this build." }
        val form = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
            .add("scope", "https://www.googleapis.com/auth/drive.appdata")
            .build()
        val reply = postJson("https://oauth2.googleapis.com/device/code", form)
        DeviceCode(
            code = reply.getString("user_code"),
            url = reply.getString("verification_url"),
            expiresSeconds = reply.getInt("expires_in"),
            pollingSeconds = reply.optInt("interval", 5).coerceAtLeast(5),
            token = reply.getString("device_code"),
        )
    }

    /** Polled only while the viewer is looking at the link-code screen. */
    suspend fun completeLogin(deviceCode: DeviceCode, cloudPassphrase: String) {
        require(cloudPassphrase.length >= 12) { "Passphrase must be at least 12 characters." }
        _status.value = _status.value.copy(busy = true, description = "Waiting for Google authorisation…")
        try {
            var interval = deviceCode.pollingSeconds.toLong() * 1000L
            val deadline = android.os.SystemClock.elapsedRealtime() + deviceCode.expiresSeconds * 1000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(interval)
                val body = FormBody.Builder()
                    .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
                    .add("client_secret", BuildConfig.GOOGLE_TV_CLIENT_SECRET)
                    .add("device_code", deviceCode.token)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val reply = postJsonAllowErrors("https://oauth2.googleapis.com/token", body)
                when (reply.optString("error")) {
                    "authorization_pending" -> continue
                    "slow_down" -> { interval += 5_000L; continue }
                    "access_denied" -> throw IOException("Google sign-in was denied.")
                    "expired_token" -> throw IOException("Google sign-in code expired.")
                    "" -> {
                        val refresh = reply.optString("refresh_token")
                        if (refresh.isBlank()) throw IOException("Google did not return a refresh token.")
                        vault.save(GoogleSyncVault.REFRESH_TOKEN, refresh)
                        vault.save(GoogleSyncVault.PASSPHRASE, cloudPassphrase)
                        _status.value = Status(true, false, 0L, "Connected. Syncing history…")
                        sync()
                        return
                    }
                    else -> throw IOException("Google could not authorise this TV. Check OAuth client type and test users.")
                }
            }
            throw IOException("Google sign-in code expired.")
        } finally {
            _status.value = _status.value.copy(busy = false)
        }
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val passphrase = vault.read(GoogleSyncVault.PASSPHRASE)
            if (passphrase == null || vault.read(GoogleSyncVault.REFRESH_TOKEN) == null) return@withLock false
            _status.value = _status.value.copy(connected = true, busy = true, description = "Syncing history…")
            try {
                val token = refreshAccessToken()
                val files = listCloudFiles(token)
                val positions = linkedMapOf<String, SyncPosition>()
                val profiles = linkedSetOf<String>()
                for ((_, id) in files) {
                    // Do not silently overwrite local history if one cloud file is
                    // undecryptable: wrong passphrase must fail the sync as a whole.
                    val data = downloadFile(id, token)
                    require(data.size <= 4 * 1024 * 1024) { "Cloud history file too large." }
                    val bundle = engine.decode(GoogleSyncCrypto.decrypt(data, passphrase))
                    profiles.addAll(bundle.profiles)
                    for (pos in bundle.positions) mergePosition(positions, pos)
                }
                val incoming = SyncBundle(profiles = profiles.toList(), positions = positions.values.toList())
                engine.apply(incoming)  // Local newer entries are never overwritten.
                val local = engine.gather()
                profiles.addAll(local.profiles)
                for (pos in local.positions) mergePosition(positions, pos)
                // No provider passwords, tokens, favourites, NAS recordings or hidden
                // categories are uploaded. Stream URLs live only inside AES-GCM.
                val portable = SyncBundle(
                    generatedAtMillis = System.currentTimeMillis(),
                    profiles = profiles.toList(),
                    positions = positions.values.toList(),
                )
                val encrypted = GoogleSyncCrypto.encrypt(engine.encode(portable), passphrase)
                val name = "koky-history-" + graph.settings.syncDeviceId + ".bin"
                val current = files.firstOrNull { it.first == name }?.second
                uploadFile(name, current, encrypted, token)
                _status.value = Status(true, false, System.currentTimeMillis(), "History synchronised")
                true
            } catch (_: Exception) {
                // Never expose raw errors, which could include stream URLs or tokens.
                _status.value = _status.value.copy(
                    busy = false, description = "Cloud sync failed. Check internet, OAuth access or cloud passphrase.",
                )
                false
            }
        }
    }

    fun disconnect() {
        vault.clear()
        _status.value = Status()
    }

    private fun mergePosition(map: MutableMap<String, SyncPosition>, pos: SyncPosition) {
        val key = pos.profile + "\u0000" + pos.kind + "\u0000" + pos.streamUrl
        val current = map[key]
        if (current == null || pos.updatedAtMillis > current.updatedAtMillis) map[key] = pos
    }

    private fun refreshAccessToken(): String {
        val refresh = vault.read(GoogleSyncVault.REFRESH_TOKEN)
            ?: throw IOException("Google account disconnected")
        val builder = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_TV_CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
        return postJson("https://oauth2.googleapis.com/token", builder.build()).getString("access_token")
    }

    private fun listCloudFiles(access: String): List<Pair<String, String>> {
        val files = mutableListOf<Pair<String, String>>()
        var next: String? = null
        do {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https").host("www.googleapis.com")
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name contains 'koky-history-' and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name)")
                .addQueryParameter("pageSize", "1000")
                .apply { next?.let { addQueryParameter("pageToken", it) } }
                .build()
            val data = getJson(url.toString(), access)
            val array = data.getJSONArray("files")
            for (i in 0 until array.length()) {
                val f = array.getJSONObject(i)
                files.add(f.getString("name") to f.getString("id"))
            }
            next = data.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (next != null && files.size < 10_000)
        return files
    }

    private fun downloadFile(id: String, access: String): ByteArray {
        val url = "https://www.googleapis.com/drive/v3/files/" +
            java.net.URLEncoder.encode(id, "UTF-8") + "?alt=media"
        return request(Request.Builder().url(url)
            .header("Authorization", "Bearer " + access).get().build())
    }

    private fun uploadFile(name: String, id: String?, bytes: ByteArray, access: String) {
        val mediaType = "application/octet-stream".toMediaType()
        val request = if (id == null) {
            val metadata = JSONObject()
                .put("name", name).put("parents", org.json.JSONArray().put("appDataFolder"))
            val multipart = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                    metadata.toString().toRequestBody("application/json".toMediaType()))
                .addPart(Headers.headersOf("Content-Type", "application/octet-stream"),
                    bytes.toRequestBody(mediaType))
                .build()
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .header("Authorization", "Bearer " + access).post(multipart).build()
        } else {
            val url = "https://www.googleapis.com/upload/drive/v3/files/" +
                java.net.URLEncoder.encode(id, "UTF-8") + "?uploadType=media"
            Request.Builder().url(url).header("Authorization", "Bearer " + access)
                .patch(bytes.toRequestBody(mediaType)).build()
        }
        request(request)
    }

    private fun getJson(url: String, access: String): JSONObject =
        JSONObject(String(request(Request.Builder().url(url)
            .header("Authorization", "Bearer " + access).get().build()), Charsets.UTF_8))

    private fun postJson(url: String, body: okhttp3.RequestBody): JSONObject {
        val response = postJsonAllowErrors(url, body)
        if (response.has("error")) throw IOException("Google authentication request failed.")
        return response
    }

    private fun postJsonAllowErrors(url: String, body: okhttp3.RequestBody): JSONObject {
        val response = request(Request.Builder().url(url).post(body).build(), allowHttpError = true)
        return JSONObject(String(response, Charsets.UTF_8))
    }

    private fun request(req: Request, allowHttpError: Boolean = false): ByteArray {
        http.newCall(req).execute().use { response ->
            if (!allowHttpError && !response.isSuccessful) throw IOException("Google Drive HTTP error")
            return response.body?.bytes() ?: throw IOException("Empty Google server response.")
        }
    }

    companion object {
        @Volatile private var instance: GoogleDriveCloudSync? = null
        fun get(context: Context): GoogleDriveCloudSync =
            instance ?: synchronized(this) {
                instance ?: GoogleDriveCloudSync(context.applicationContext).also { instance = it }
            }
    }
}
