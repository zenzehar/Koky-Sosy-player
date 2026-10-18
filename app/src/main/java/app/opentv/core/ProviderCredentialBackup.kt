/* GPL-3.0-or-later
 * Encrypted, user-owned provider backup. It lives in a document chosen with the Android
 * system file picker, NOT app-private files or an unencrypted public Downloads folder.
 */
package app.opentv.core

import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

object ProviderCredentialBackup {
    private const val MAX_SOURCES = 50
    private const val MAX_FILE_BYTES = 128 * 1024
    private const val ITERATIONS = 210_000
    private const val NONCE_BYTES = 12
    private const val SALT_BYTES = 16
    private val MAGIC = "KSPC2".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(sources: List<Source>, passphrase: String): ByteArray {
        require(passphrase.length >= 8) { "Use a backup passphrase of at least eight characters." }
        require(sources.size <= MAX_SOURCES)
        val payload = JSONArray()
        for (source in sources) {
            payload.put(JSONObject().apply {
                put("name", source.name)
                put("kind", source.kind.name)
                put("url", source.url)
                put("username", source.username ?: "")
                put("password", source.password ?: "")
                put("macAddress", source.macAddress ?: "")
                put("epgUrl", source.epgUrl ?: "")
                put("userAgent", source.userAgent)
                put("liveFormat", source.liveFormat.name)
                put("enabled", source.enabled)
            })
        }
        val plain = JSONObject()
            .put("version", 2)
            .put("providers", payload)
            .toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_FILE_BYTES / 2)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(MAGIC)
            val encrypted = cipher.doFinal(plain)
            return MAGIC + salt + nonce + encrypted
        } finally {
            Arrays.fill(key, 0.toByte())
            Arrays.fill(plain, 0.toByte())
        }
    }

    fun decrypt(blob: ByteArray, passphrase: String): List<Source> {
        require(blob.size in (MAGIC.size + SALT_BYTES + NONCE_BYTES + 17)..MAX_FILE_BYTES) {
            "Unsupported backup file."
        }
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "Unsupported backup format."
        }
        val saltStart = MAGIC.size
        val nonceStart = saltStart + SALT_BYTES
        val encStart = nonceStart + NONCE_BYTES
        val salt = blob.copyOfRange(saltStart, nonceStart)
        val nonce = blob.copyOfRange(nonceStart, encStart)
        val key = deriveKey(passphrase, salt)
        val plain: ByteArray
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(MAGIC)
            plain = cipher.doFinal(blob.copyOfRange(encStart, blob.size))
        } finally {
            Arrays.fill(key, 0.toByte())
        }
        try {
            val root = JSONObject(plain.toString(Charsets.UTF_8))
            require(root.getInt("version") == 2)
            val providers = root.getJSONArray("providers")
            require(providers.length() in 0..MAX_SOURCES)
            return (0 until providers.length()).map { index ->
                val v = providers.getJSONObject(index)
                val name = v.getString("name").take(120)
                val url = v.getString("url")
                require(url.isNotBlank() && url.length < 2_048 && name.isNotBlank())
                Source(
                    id = 0L,
                    name = name,
                    kind = SourceKind.valueOf(v.getString("kind")),
                    url = url,
                    username = v.optString("username").takeIf(String::isNotBlank),
                    password = v.optString("password").takeIf(String::isNotBlank),
                    macAddress = v.optString("macAddress").takeIf(String::isNotBlank),
                    epgUrl = v.optString("epgUrl").takeIf(String::isNotBlank),
                    userAgent = v.optString("userAgent").ifBlank { Source.DEFAULT_USER_AGENT },
                    liveFormat = LiveStreamFormat.valueOf(v.optString("liveFormat", "HLS")),
                    enabled = v.optBoolean("enabled", true),
                )
            }
        } finally {
            Arrays.fill(plain, 0.toByte())
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try {
            // Supported on Android API 23+ (unlike some SHA-256 PBKDF2 providers).
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            Arrays.fill(chars, '\u0000')
        }
    }
}
