/*
 * GPL-3.0-or-later. Portable user-controlled backup via Android Storage Access Framework.
 * Android deletes app-private files on uninstall: use a user-chosen external document.
 * Never write cleartext passwords to shared storage, logs, URLs, or a QR code.
 */
package app.opentv.ui.settings

import android.net.Uri
import android.content.Intent
import app.opentv.core.PersistentDeviceCredentials
import app.opentv.isRunningOnTelevision
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import android.widget.Toast
import app.opentv.ui.onboarding.TvNativeTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.core.ProviderCredentialBackup
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Import is available on the first-run screen, before login or provider connection. */
@Composable
fun CredentialBackupControls(
    allowExport: Boolean,
    onRestored: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { ServiceLocator.get(context).sourceRepository }
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var noticeTitle by remember { mutableStateOf("") }
    var noticeText by remember { mutableStateOf("") }
    var noticeOpen by remember { mutableStateOf(false) }
    var navigateAfterNotice by remember { mutableStateOf(false) }
    fun report(title: String, details: String, restored: Boolean = false) {
        noticeTitle = title
        noticeText = details
        noticeOpen = true
        navigateAfterNotice = restored
        status = "$title: $details"
        Toast.makeText(context, title, Toast.LENGTH_LONG).show()
    }
    val supportsCreateDocument = remember(context) {
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .resolveActivity(context.packageManager) != null
    }
    val supportsOpenDocument = remember(context) {
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .resolveActivity(context.packageManager) != null
    }
    fun saveWithoutPicker() {
        busy = true
        scope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    val data = repo.observeAll().first()
                    val encrypted = ProviderCredentialBackup.encrypt(data, passphrase)
                    PersistentDeviceCredentials.saveManualBackup(context, encrypted)
                }
            }.getOrDefault(false)
            status = if (success) "Encrypted backup saved in ${PersistentDeviceCredentials.manualLocationLabel()}." +
                " Keep your passphrase separately."
            else "Cannot save to shared Documents. Grant device-folder permission above first."
            if (success) passphrase = ""
            busy = false
        }
    }
    fun restoreWithoutPicker() {
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val blob = PersistentDeviceCredentials.loadManualBackup(context)
                        ?: error("No local backup")
                    val saved = ProviderCredentialBackup.decrypt(blob, passphrase)
                    val existing = repo.observeAll().first()
                    var imported = 0
                    for (source in saved) {
                        val target = SourceRepository.normaliseUrl(source.url, source.kind)
                        val duplicate = existing.any {
                            it.kind == source.kind &&
                                SourceRepository.normaliseUrl(it.url, it.kind) == target &&
                                it.username == source.username && it.macAddress == source.macAddress
                        }
                        if (!duplicate) { repo.save(source); imported++ }
                    }
                    imported
                }
            }
            status = if (result.isSuccess)
                "Manual device backup restored without retyping provider credentials."
            else "Manual restore unavailable. Grant folder access and check passphrase/file."
            if (result.isSuccess) { passphrase = ""; onRestored() }
            busy = false
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        val sources = repo.observeAll().first()
                        val encrypted = ProviderCredentialBackup.encrypt(sources, passphrase)
                        requireNotNull(context.contentResolver.openOutputStream(uri)) {
                            "Could not write the selected document."
                        }.use { it.write(encrypted); it.flush() }
                        sources.size
                    }
                }
                status = if (ok.isSuccess) {
                    passphrase = ""
                    "Encrypted backup saved. Keep the file and passphrase separately."
                } else "Backup failed. Choose an accessible file location and try again."
                busy = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                require(out.size() + n <= 128 * 1024) { "File too large" }
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        val restored = ProviderCredentialBackup.decrypt(bytes, passphrase)
                        val existing = repo.observeAll().first()
                        var imported = 0
                        for (item in restored) {
                            val target = SourceRepository.normaliseUrl(item.url, item.kind)
                            val duplicate = existing.any {
                                it.kind == item.kind &&
                                    SourceRepository.normaliseUrl(it.url, it.kind) == target &&
                                    it.username == item.username && it.macAddress == item.macAddress
                            }
                            if (!duplicate) { repo.save(item); imported++ }
                        }
                        imported
                    }
                }
                if (result.isSuccess) {
                    passphrase = ""
                    status = "Provider credentials restored. Existing providers were not overwritten."
                    onRestored()
                } else {
                    // No raw exception messages: files and providers can contain credentials.
                    status = "Restore failed. Check your backup file and passphrase."
                }
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Portable provider login backup")
        Text(
            "Android deletes private app data when uninstalled. Save an encrypted file " +
                "in Documents or another location you control, then import it after reinstalling. " +
                "For TV boxes without a system file picker, use the shared Documents-folder " +
                "buttons below after enabling device-folder access. Keep the passphrase separately.",
        )
        TvNativeTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = "Backup passphrase (8+ characters) — OK to edit",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Press OK inside the passphrase field to type. Press Back to close " +
            "the keyboard, then move to Export or Restore.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (allowExport) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        if (passphrase.length < 8) {
                            report("Passphrase required", "Use at least 8 characters; " +
                                "close the keyboard with Back before selecting Export.")
                            return@OutlinedButton
                        }
                        // A system picker may resolve on Android TV yet fail to launch
                        // (real-device "You don't have an app" error). Prefer the
                        // already granted shared Documents permission every time.
                        if (PersistentDeviceCredentials.canRead(context)) saveWithoutPicker()
                        else if (!isRunningOnTelevision(context) && supportsCreateDocument) {
                            try { exporter.launch("Koky-Sosy-provider-backup.kspc") }
                            catch (_: Exception) {
                                status = "Document picker is unavailable on this TV. " +
                                    "Grant shared device-folder access above."
                            }
                        } else status = "Grant shared device-folder access above to " +
                            "export a manual encrypted backup on this TV."
                    },
                ) { Text("Export encrypted logins") }
            }
            Button(
                enabled = !busy,
                onClick = {
                    if (passphrase.length < 8) {
                        report("Passphrase required", "Use at least 8 characters; " +
                            "close the keyboard with Back before selecting Restore.")
                        return@Button
                    }
                    if (PersistentDeviceCredentials.canRead(context)) restoreWithoutPicker()
                    else if (!isRunningOnTelevision(context) && supportsOpenDocument) {
                        try { importer.launch(arrayOf("application/octet-stream", "*/*")) }
                        catch (_: Exception) {
                            status = "Document picker is unavailable on this TV. " +
                                "Grant shared device-folder access above."
                        }
                    } else status = "Grant shared device-folder access above to " +
                        "restore the encrypted device backup."
                },
            ) { Text("Restore logins from file") }
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 4.dp))
    }
    if (noticeOpen) {
        AlertDialog(
            onDismissRequest = {
                noticeOpen = false
                if (navigateAfterNotice) { navigateAfterNotice = false; onRestored() }
            },
            title = { Text(noticeTitle) },
            text = { Text(noticeText) },
            confirmButton = {
                TextButton(onClick = {
                    noticeOpen = false
                    if (navigateAfterNotice) { navigateAfterNotice = false; onRestored() }
                }) { Text("OK") }
            },
        )
    }
}
