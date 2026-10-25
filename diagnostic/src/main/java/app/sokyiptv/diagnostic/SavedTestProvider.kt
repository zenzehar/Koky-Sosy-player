package app.sokyiptv.diagnostic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Device-local diagnostic identity. Not baked into publicly downloadable APKs or
 * Git history. AndroidKeyStore AES-256-GCM key is bound to this app on this device.
 *
 * Same applicationId and signing certificate retain this across diagnostic updates.
 * Uninstalling the diagnostic app, clearing app storage, or changing signing keys can
 * make the saved provider unavailable. Backups are explicitly disabled in Manifest.
 */
internal object SavedTestProvider {
    private const val PREFS = "private_diagnostic_account"
    private const val FIELD = "encrypted_provider_v1"
    private const val KEY_ALIAS = "soky_xtream_diagnostic_provider_aes_v1"
    private const val AAD = "soky-diagnostic-provider-v1"
    private const val NONCE_BYTES = 12

    fun save(context: Context, provider: ProviderImport.Provider) {
        require(provider.kind == "XTREAM") { "Only Xtream accounts are supported." }
        val payload = JSONObject()
            .put("name", provider.name)
            .put("kind", provider.kind)
            .put("url", provider.url)
            .put("username", provider.username)
            .put("password", provider.password)
            .put("userAgent", provider.userAgent)
            .toString().toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
            val nonce = cipher.iv
            val ciphertext = cipher.doFinal(payload)
            require(nonce.size == NONCE_BYTES)
            val encoded = Base64.encodeToString(nonce + ciphertext, Base64.NO_WRAP)
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(FIELD, encoded).commit()) {
                "Could not save encrypted diagnostic login."
            }
        } finally {
            payload.fill(0)
        }
    }

    fun load(context: Context): ProviderImport.Provider? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(FIELD, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size in (NONCE_BYTES + 17)..(128 * 1024)) {
            "Stored diagnostic account format is invalid."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, NONCE_BYTES))
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        val clear = cipher.doFinal(data, NONCE_BYTES, data.size - NONCE_BYTES)
        try {
            val obj = JSONObject(String(clear, StandardCharsets.UTF_8))
            require(obj.optString("kind") == "XTREAM")
            return ProviderImport.Provider(
                name = obj.optString("name"),
                kind = "XTREAM",
                url = obj.getString("url"),
                username = obj.getString("username"),
                password = obj.getString("password"),
                userAgent = obj.optString("userAgent").ifEmpty { "SokyIPTV/0.1 (Android)" },
            )
        } finally {
            clear.fill(0)
        }
    }

    /** Remove the local encrypted payload and encryption key; not the original Soky login. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(FIELD).commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
