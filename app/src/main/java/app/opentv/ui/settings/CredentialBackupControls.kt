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
            if (success) {
                passphrase = ""
                report("Backup saved successfully",
                    "Encrypted provider backup saved at: " +
                        PersistentDeviceCredentials.manualLocationLabel() +
                        ". This file survives app uninstall. Keep the passphrase safe.")
            } else report("Backup failed",
                "Couldn't write the encrypted backup at " +
                    PersistentDeviceCredentials.manualLocationLabel() +
                    ". Check All files access and available space.")
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
            if (result.isSuccess) {
                passphrase = ""
                report("Restore successful",
                    "Restored " + result.getOrNull() + " new provider(s) from " +
                        PersistentDeviceCredentials.manualLocationLabel() +
                        ". Existing providers were not overwritten.", restored = true)
            } else report("Restore failed",
                "No readable encrypted backup at " +
                    PersistentDeviceCredentials.manualLocationLabel() +
                    ". Check the file, passphrase and storage permission.")
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
                if (ok.isSuccess) {
                    passphrase = ""
                    report("Backup saved", "Saved encrypted backup to selected file: " +
                        uri.toString())
                } else report("Backup failed", "Unable to write the selected document.")
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
                    report("Restore successful",
                        "Providers restored from selected document without overwriting existing ones.",
                        restored = true)
                } else {
                    // No raw exception messages: files and providers can contain credentials.
                    report("Restore failed", "Check the selected file and its passphrase.")
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
                                report("No document picker", "Grant device folder access above.")
                            }
                        } else report("Storage permission required",
                            "Grant All files access using Device vault above. " +
                                "The export file will be saved at " +
                                PersistentDeviceCredentials.manualLocationLabel())
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
                            report("No document picker", "Grant device folder access above.")
                        }
                    } else report("Storage permission required",
                        "Grant All files access using Device vault above, then restore from " +
                            PersistentDeviceCredentials.manualLocationLabel())
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
