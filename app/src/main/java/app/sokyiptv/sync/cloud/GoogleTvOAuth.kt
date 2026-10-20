/*
 * Soky IPTV Pro experimental Google TV OAuth device flow.
 * GPL-3.0-or-later. OAuth material is never logged or saved in plaintext.
 */
package app.sokyiptv.sync.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.sokyiptv.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class CloudCredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("soky_cloud_auth_v1", Context.MODE_PRIVATE)
    private val alias = "soky_drive_cloud_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val found = store.getKey(alias, null) as? SecretKey
        if (found != null) return found
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun read(name: String): String? {
        val data = prefs.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(data, Base64.NO_WRAP)
            require(raw.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }.getOrElse {
            // Android Keystore keys can be invalidated or disappear after an Android restore.
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun write(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun clearAuthorization() {
        prefs.edit().remove("access").remove("refresh").remove("expiry").apply()
    }
}

data class TvGoogleCode(
    val userCode: String,
    val verificationUrl: String,
    internal val deviceCode: String,
    internal val pollSeconds: Long,
    internal val expiresAtMillis: Long,
)

internal class GoogleTvOAuth(
    private val http: OkHttpClient,
    private val vault: CloudCredentialVault,
) {
    private val clientId: String get() = BuildConfig.GOOGLE_TV_CLIENT_ID
    private val clientSecret: String
        get() = vault.read("test_secret")?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GOOGLE_TV_CLIENT_SECRET

    val connected: Boolean get() = !vault.read("refresh").isNullOrBlank()

    fun hasTestSecret(): Boolean = clientSecret.isNotBlank()
    fun saveTestSecret(value: String) = vault.write("test_secret", value.trim())
    fun disconnect() = vault.clearAuthorization()

    private fun post(endpoint: String, vararg fields: Pair<String, String>): JSONObject {
        val data = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(endpoint).post(data).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val result = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (!response.isSuccessful && !result.has("error")) {
                throw IllegalStateException("Google authentication returned HTTP ${response.code}.")
            }
            return result
        }
    }

    suspend fun createDeviceCode(): TvGoogleCode = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { "Google TV Client ID is missing from the build." }
        require(clientSecret.isNotBlank()) {
            "Enter your temporary TV OAuth Client Secret in Google Drive Sync settings first."
        }
        val result = post(
            "https://oauth2.googleapis.com/device/code",
            "client_id" to clientId,
            "scope" to "https://www.googleapis.com/auth/drive.appdata",
        )
        if (result.has("error")) throw IllegalStateException("Google device authorisation was declined: ${result.optString("error")}.")
        TvGoogleCode(
            userCode = result.getString("user_code"),
            verificationUrl = result.getString("verification_url"),
            deviceCode = result.getString("device_code"),
            pollSeconds = result.optLong("interval", 5L).coerceAtLeast(5L),
            expiresAtMillis = System.currentTimeMillis() + result.optLong("expires_in", 900L) * 1000L,
        )
    }

    suspend fun waitForUser(code: TvGoogleCode) = withContext(Dispatchers.IO) {
        var seconds = code.pollSeconds
        while (System.currentTimeMillis() < code.expiresAtMillis) {
            delay(seconds * 1000L)
            val result = post(
                "https://oauth2.googleapis.com/token",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "device_code" to code.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
            when (val error = result.optString("error")) {
                "" -> {
                    rememberTokens(result)
                    return@withContext
                }
                "authorization_pending" -> Unit
                "slow_down" -> seconds += 5L
                "access_denied" -> throw IllegalStateException("Google account authorisation was denied.")
                "expired_token" -> throw IllegalStateException("The Google sign-in code expired. Try connecting again.")
                else -> throw IllegalStateException("Google sign-in failed: $error.")
            }
        }
        throw IllegalStateException("The Google sign-in code expired. Try connecting again.")
    }

    private fun rememberTokens(result: JSONObject) {
        val access = result.optString("access_token")
        require(access.isNotEmpty()) { "Google did not return an access token." }
        vault.write("access", access)
        val refresh = result.optString("refresh_token")
        if (refresh.isNotBlank()) vault.write("refresh", refresh)
        val expiry = System.currentTimeMillis() + result.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L
        vault.write("expiry", expiry.toString())
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val cached = vault.read("access")
        val expires = vault.read("expiry")?.toLongOrNull() ?: 0L
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < expires - 60_000L) {
            return@withContext cached
        }
        val refresh = vault.read("refresh") ?: throw IllegalStateException("Connect a Google account first.")
        val result = post(
            "https://oauth2.googleapis.com/token",
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token",
        )
        if (result.has("error")) {
            // Never leak the bearer/refresh tokens to UI or logs.
            throw IllegalStateException("Google session expired. Disconnect and connect your account again.")
        }
        rememberTokens(result)
        vault.read("access") ?: throw IllegalStateException("Google did not return an access token.")
    }
}
