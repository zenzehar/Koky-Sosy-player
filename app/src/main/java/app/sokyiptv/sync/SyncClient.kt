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
import org.json.JSONObject

/** Code needed once per peer; subsequent connections use an encrypted random token. */
class SyncClient(httpClient: OkHttpClient, context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val vault = CloudCredentialVault(context.applicationContext)
    private val http = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun isPaired(deviceId: String): Boolean =
        deviceId.matches(Regex("[a-fA-F0-9-]{36}")) && !vault.read("lan_peer_$deviceId").isNullOrBlank()

    suspend fun pull(address: String, code: String, expectedDeviceId: String = ""): Result<SyncBundle> =
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

                val request = Request.Builder()
                    .url("http://$host/export")
                    .header("X-Soky-Pair-Token", token)
                    .get().build()
                val bundle = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(if (response.code == 401)
                            "Pairing has expired or was revoked. Re-enter the sharing TV's code."
                        else "The sharing TV rejected the transfer (HTTP ${response.code}).")
                    }
                    json.decodeFromString(SyncBundle.serializer(), response.body?.string().orEmpty())
                }
                if (!paired) vault.write("lan_peer_$deviceId", token)
                bundle
            }.recoverCatching { e ->
                when (e) {
                    is SocketTimeoutException -> error("TV did not respond within 12 seconds. Check that Share is open.")
                    is ConnectException -> error("Connection refused. Reopen Share and search for devices.")
                    is NoRouteToHostException -> error("Peer unreachable. Check Wi-Fi client isolation.")
                    else -> throw e
                }
            }
        }
}
