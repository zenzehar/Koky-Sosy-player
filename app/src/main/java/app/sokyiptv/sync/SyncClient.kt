/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.sync

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pulls a watch-history bundle from another SokyIPTV that is currently sharing on the LAN.
 *
 * Given the address the other device shows ("192.168.0.14:41234") and its six-digit code, this
 * does one plain HTTP GET over the local network and parses the JSON. No retries, no discovery —
 * the sharing device is up for a few minutes and the user typed exactly where it is.
 */
class SyncClient(private val httpClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    // The shared IPTV catalogue client allows five-minute requests. A local peer
    // should either respond promptly or fail, not keep a TV waiting indefinitely.
    private val lanHttp = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun pull(address: String, code: String): Result<SyncBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanAddress = address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            val cleanCode = code.trim()
            require(cleanCode.matches(Regex("[0-9]{6}"))) { "Enter the six-digit code shown on the sharing TV." }
            require(cleanAddress.matches(Regex("[0-9.]+:[0-9]{1,5}"))) { "Select a discovered device or enter its IP:port." }
            require(app.sokyiptv.pairing.PairingHttp.isPrivateAddress(cleanAddress.substringBefore(':'))) {
                "Device sync only works on a private local network."
            }
            val url = "http://$cleanAddress/export?c=$cleanCode"
            val request = Request.Builder().url(url).get().build()
            try {
            lanHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            401 -> "That code doesn't match the one on the other device."
                            else -> "The other device didn't accept the connection (HTTP ${response.code})."
                        },
                    )
                }
                val body = response.body?.string().orEmpty()
                json.decodeFromString(SyncBundle.serializer(), body)
            }
            } catch (_: java.net.SocketTimeoutException) {
                error("The other TV did not respond within 12 seconds. Check that it is sharing and both TVs are on the same Wi-Fi.")
            } catch (_: java.net.ConnectException) {
                error("Connection refused. Restart Share on the other TV and try Discover devices.")
            } catch (_: java.net.NoRouteToHostException) {
                error("The other TV is unreachable. Check Wi-Fi isolation and the displayed IP address.")
            }
        }
    }
}
