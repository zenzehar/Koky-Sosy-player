/*
 * GPL-3.0-or-later.
 *
 * Opt-in, best-effort on-device encrypted credentials vault outside app-private storage.
 * Android 11+ requires special user-approved All Files Access; after reinstall the
 * permission must be granted again. No privileged permission is acquired silently.
 *
 * Device-bound encryption uses ANDROID_ID, which is stable across reinstall ONLY with
 * the same signing key, Android user and device. Never promise restoration across debug
 * signing changes, factory reset, another device, or an Android ID change. Strong manual
 * passphrase backup remains the recovery fallback.
 */
package app.opentv.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import app.opentv.data.model.Source
import java.io.File
import java.security.MessageDigest

object PersistentDeviceCredentials {
    private const val FOLDER = "KokySosy"
    private const val NAME = "device-logins.kspc"
    private const val MAX_BYTES = 128 * 1024
    private const val ENABLED_PREF = "device_vault_enabled"
    private const val PREFS = "koky_device_vault"
    private val syncLock = Any()

    /** Android 11+ requires user-approved all-files access for arbitrary Documents files. */
    fun canRead(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return false // Legacy access disabled; do not request broad legacy permissions.
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_PREF, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_PREF, value).apply()
    }

    fun exists(context: Context): Boolean = canRead(context) && vaultFile().isFile

    private fun vaultFile(): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER),
        NAME,
    )

    /** Encryption is bound to Android's app-signature-scoped Android ID, not a static shared key. */
    private fun keyMaterial(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID,
        ).orEmpty()
        require(androidId.isNotBlank())
        // Domain separator prevents accidental reuse with a different product or operation.
        val data = ("KokySosy/device-vault/1|" + context.packageName + "|" + androidId)
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun write(context: Context, sources: List<Source>): Boolean {
        if (!enabled(context) || !canRead(context) || sources.isEmpty()) return false
        return runCatching {
            val encrypted = ProviderCredentialBackup.encrypt(sources, keyMaterial(context))
            require(encrypted.size <= MAX_BYTES)
            synchronized(syncLock) {
                val destination = vaultFile()
                require(destination.parentFile?.mkdirs() == true ||
                    destination.parentFile?.isDirectory == true)
                val temp = File(destination.parentFile, "$NAME.new")
                temp.outputStream().use { it.write(encrypted); it.fd.sync() }
                if (!temp.renameTo(destination)) {
                    destination.outputStream().use { it.write(encrypted); it.fd.sync() }
                    temp.delete()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun read(context: Context): List<Source>? {
        if (!canRead(context)) return null
        return runCatching {
            synchronized(syncLock) {
                val source = vaultFile()
                if (!source.isFile || source.length() <= 0L || source.length() > MAX_BYTES) null
                else ProviderCredentialBackup.decrypt(source.readBytes(), keyMaterial(context))
            }
        }.getOrNull()
    }

    /** Called BEFORE clearApplicationUserData(); do not delete on uninstall/update. */
    fun deleteForFactoryReset(context: Context): Boolean {
        if (!canRead(context)) return !vaultFile().exists()
        return runCatching {
            synchronized(syncLock) {
                val file = vaultFile()
                val temp = File(file.parentFile, "$NAME.new")
                val ok = (!file.exists() || file.delete()) && (!temp.exists() || temp.delete())
                if (ok) file.parentFile?.delete()
                ok
            }
        }.getOrDefault(false)
    }

    /** SAF-less manual file for TV boxes without a document picker; requires explicit permission. */
    private fun manualFile(): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER),
        "manual-logins.kspc",
    )

    fun saveManualBackup(context: Context, bytes: ByteArray): Boolean {
        if (!canRead(context) || bytes.size > MAX_BYTES || bytes.isEmpty()) return false
        return runCatching {
            synchronized(syncLock) {
                val file = manualFile()
                require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                file.outputStream().use { it.write(bytes); it.fd.sync() }
            }
            true
        }.getOrDefault(false)
    }

    fun loadManualBackup(context: Context): ByteArray? {
        if (!canRead(context)) return null
        return runCatching {
            synchronized(syncLock) {
                val file = manualFile()
                if (!file.isFile || file.length() <= 0 || file.length() > MAX_BYTES) null
                else file.readBytes()
            }
        }.getOrNull()
    }

    fun manualLocationLabel(): String = "Documents/$FOLDER/manual-logins.kspc"

    fun locationLabel(): String = "Documents/$FOLDER/$NAME"
}
