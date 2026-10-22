package app.sokyiptv.sync

import android.content.Context
import app.sokyiptv.pairing.PairingHttp
import app.sokyiptv.sync.cloud.CloudCredentialVault
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Code needed once per peer; subsequent connections use an encrypted random token. */
class SyncClient(httpClient: OkHttpClient, context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val vault = CloudCredentialVault(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("soky_lan_peers", Context.MODE_PRIVATE)
    fun pairedIds(): Set<String> = prefs.getStringSet("paired_ids", emptySet()).orEmpty().toSet()
    fun lastAddress(deviceId: String): String? = prefs.getString("address_$deviceId", null)

    private val http = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun isPaired(deviceId: String): Boolean =
        deviceId.matches(Regex("[a-fA-F0-9-]{36}")) && !vault.read("lan_peer_$deviceId").isNullOrBlank()
    fun forgetPairing(deviceId: String) {
        if (deviceId.matches(Regex("[a-fA-F0-9-]{36}"))) {
            vault.write("lan_peer_$deviceId", null)
            prefs.edit().putStringSet("paired_ids", pairedIds() - deviceId)
                .remove("address_$deviceId").apply()
        }
    }

    suspend fun pull(address: String, code: String, expectedDeviceId: String = "", outgoing: String? = null): Result<SyncBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = address.trim().removePrefix("http://").trimEnd('/')
                require(host.matches(Regex("[0-9.]+:[0-9]{1,5}")) &&
                    PairingHttp.isPrivateAddress(host.substringBefore(':'))) {
                    "Select a discovered TV or enter a private IP:port."
                }
                val shortCode = code.trim()
                val savedToken = if (expectedDeviceId.isNotBlank()) vault.read("lan_peer_$expectedDeviceId") else null
                val paired = shortCode.isBlank() && !savedToken.isNullOrBlank()
                require(paired || shortCode.matches(Regex("[0-9]{6}"))) {
                    "Enter the six-digit code once to pair these TVs."
                }

                val (token, deviceId) = if (paired) {
                    savedToken!! to expectedDeviceId
                } else {
                    val pinRequest = Request.Builder()
                        .url("http://$host/pair?c=$shortCode").get().build()
                    val reply = http.newCall(pinRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            error(if (response.code == 401) "Incorrect pairing code." else
                                "TV pairing failed (HTTP ${response.code}).")
                        }
                        JSONObject(response.body?.string().orEmpty())
                    }
                    val remoteId = reply.optString("deviceId")
                    val newToken = reply.optString("token")
                    require(remoteId.matches(Regex("[a-fA-F0-9-]{36}")) && newToken.length >= 32) {
                        "The other TV did not provide a valid pairing token."
                    }
                    if (expectedDeviceId.isNotBlank() && expectedDeviceId != remoteId) {
                        error("TV identity changed. Refusing to transfer history to an unexpected device.")
                    }
                    newToken to remoteId
                }

                // A modern peer atomically imports our current active-profile history
                // and responds with its own refreshed snapshot. This allows BOTH TVs to
                // converge after a single Receive action; GET remains a legacy fallback.
                val request = Request.Builder()
                    .url("http://$host/${if (outgoing == null) "export" else "exchange"}")
                    .header("X-Soky-Pair-Token", token)
                    .apply {
                        if (outgoing == null) get()
                        else post(outgoing.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    }
                    .build()
                val firstResponse = http.newCall(request).execute()
                val response = if (outgoing != null && firstResponse.code in listOf(404, 405)) {
                    // Older supported TV builds accept only GET /export.
                    firstResponse.close()
                    http.newCall(Request.Builder().url("http://$host/export")
                        .header("X-Soky-Pair-Token", token).get().build()).execute()
                } else firstResponse
                val bundle = response.use {
                    if (!it.isSuccessful) {
                        if (it.code == 401 && paired) forgetPairing(expectedDeviceId)
                        error(if (it.code == 401)
                            "Pairing expired or was revoked. Enter the new pairing code once."
                        else "The sharing TV rejected the transfer (HTTP ${it.code}).")
                    }
                    json.decodeFromString(SyncBundle.serializer(), it.body?.string().orEmpty())
                }
                if (!paired) vault.write("lan_peer_$deviceId", token)
                if (isPaired(deviceId)) {
                    prefs.edit().putStringSet("paired_ids", pairedIds() + deviceId)
                        .putString("address_$deviceId", host).apply()
                }
                bundle
            }.recoverCatching { e ->
                when (e) {
                    is SocketTimeoutException -> error("TV did not respond within 12 seconds. Check that sharing is enabled on the other TV.")
                    is ConnectException -> error("Connection refused. Enable Share on the other TV and search again.")
                    is NoRouteToHostException -> error("Peer unreachable. Check Wi-Fi client isolation.")
                    else -> throw e
                }
            }
        }
}
