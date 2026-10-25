package app.sokyiptv.diagnostic

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Read-only implementation of the original app's ProviderCredentialBackup KSPC2 format.
 * No credentials are persisted; separate apps cannot access Soky's private preferences.
 */
internal object ProviderImport {
    private val magic = "KSPC2".toByteArray(Charsets.US_ASCII)
    private const val LIMIT = 128 * 1024

    data class Provider(
        val name: String, val kind: String, val url: String,
        val username: String, val password: String, val userAgent: String,
    )

    fun fromManualBackup(blob: ByteArray, passphrase: String): List<Provider> =
        decrypt(blob, passphrase)

    fun fromDeviceVault(context: Context, blob: ByteArray): List<Provider> {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ).orEmpty()
        require(androidId.isNotBlank()) { "Android ID unavailable." }
        // The original app includes its own package ID in the encryption key.
        // On Android 8+, ANDROID_ID is also scoped to the app's signing key.
        // A separately signed diagnostic APK will not be able to decrypt this vault.
        val originals = listOf("tv.sokyiptv.pro.debug", "tv.sokyiptv.pro")
        originals.forEach { id ->
            val text = "SokyIPTVPro/device-vault/1|$id|$androidId"
            val keyMaterial = MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            runCatching { return decrypt(blob, keyMaterial) }
        }
        error("Automatic vault cannot be decrypted with this application's Android identity. " +
            "Use the passphrase-protected manual-logins.kspc backup from Soky's settings.")
    }

    private fun decrypt(blob: ByteArray, passphrase: String): List<Provider> {
        require(passphrase.isNotBlank()) { "Enter the manual backup passphrase." }
        require(blob.size in (magic.size + 16 + 12 + 17)..LIMIT &&
            blob.copyOfRange(0, magic.size).contentEquals(magic)) {
            "The selected file is not a valid KSPC2 encrypted login backup."
        }
        val salt = blob.copyOfRange(magic.size, magic.size + 16)
        val nonce = blob.copyOfRange(magic.size + 16, magic.size + 28)
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, 210_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            Arrays.fill(chars, '\u0000')
        }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            cipher.doFinal(blob.copyOfRange(magic.size + 28, blob.size))
        } finally {
            Arrays.fill(key, 0.toByte())
        }
        try {
            val root = JSONObject(plain.toString(Charsets.UTF_8))
            require(root.getInt("version") == 2)
            val items = root.getJSONArray("providers")
            require(items.length() in 0..50)
            return (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                Provider(
                    name = item.optString("name").take(120),
                    kind = item.optString("kind"),
                    url = item.getString("url"),
                    username = item.optString("username"),
                    password = item.optString("password"),
                    userAgent = item.optString("userAgent").ifBlank { "SokyIPTV/0.1 (Android)" },
                )
            }
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }
}
