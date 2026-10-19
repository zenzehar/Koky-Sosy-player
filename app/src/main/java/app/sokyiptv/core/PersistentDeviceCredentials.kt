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
package app.sokyiptv.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import app.sokyiptv.data.model.Source
import java.io.File
import java.security.MessageDigest

object PersistentDeviceCredentials {
    private const val FOLDER = "SokyIPTVPro"
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
        val data = ("SokyIPTVPro/device-vault/1|" + context.packageName + "|" + androidId)
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun write(context: Context, sources: List<Source>): Boolean {
        if (!enabled(context) || !canRead(context)) return false
        if (sources.isEmpty()) {
            // Deleting the final provider must not leave an outdated secret on disk
            // which would silently resurrect previously removed login information.
            return runCatching {
                synchronized(syncLock) {
                    val saved = vaultFile()
                    !saved.exists() || saved.delete()
                }
            }.getOrDefault(false)
        }
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
        // Permission denial can make exists() falsely report "absent"; never claim
        // a factory reset erased credentials if we cannot even inspect the file.
        if (!canRead(context)) return false
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

    // Non-sensitive outcomes: do not expose provider URLs, usernames, encryption
    // material or exception messages in the on-screen report.
    enum class ManualBackupWriteResult {
        SAVED, PERMISSION_REQUIRED, INVALID_SIZE, STORAGE_UNAVAILABLE,
        FOLDER_UNAVAILABLE, WRITE_FAILED, VERIFY_FAILED
    }

    fun saveManualBackupWithStatus(context: Context, bytes: ByteArray): ManualBackupWriteResult {
        if (!canRead(context)) return ManualBackupWriteResult.PERMISSION_REQUIRED
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return ManualBackupWriteResult.INVALID_SIZE
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return ManualBackupWriteResult.STORAGE_UNAVAILABLE
        }
        return synchronized(syncLock) {
            val target = manualFile()
            val folder = target.parentFile
            if (folder == null || !(folder.isDirectory || folder.mkdirs())) {
                return@synchronized ManualBackupWriteResult.FOLDER_UNAVAILABLE
            }
            val temporary = File(folder, "manual-logins.kspc.new")
            try {
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                if (temporary.length() != bytes.size.toLong()) {
                    return@synchronized ManualBackupWriteResult.VERIFY_FAILED
                }
                // Replace only after the encrypted temporary copy is fully written.
                if (!temporary.renameTo(target)) {
                    target.outputStream().use { it.write(bytes); it.fd.sync() }
                    temporary.delete()
                }
                if (target.isFile && target.length() == bytes.size.toLong()) {
                    ManualBackupWriteResult.SAVED
                } else ManualBackupWriteResult.VERIFY_FAILED
            } catch (_: Exception) {
                ManualBackupWriteResult.WRITE_FAILED
            } finally {
                // A failed write must not leave a second recoverable backup behind.
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    /** Existing callers can keep using the Boolean convenience API. */
    fun saveManualBackup(context: Context, bytes: ByteArray): Boolean =
        saveManualBackupWithStatus(context, bytes) == ManualBackupWriteResult.SAVED

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
