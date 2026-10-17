/*
 * GPL-3.0-or-later. Portable user-controlled backup via Android Storage Access Framework.
 * Android deletes app-private files on uninstall: use a user-chosen external document.
 * Never write cleartext passwords to shared storage, logs, URLs, or a QR code.
 */
package app.opentv.ui.settings

import android.net.Uri
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
import androidx.compose.material3.OutlinedTextField
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
                "The file stays outside the app; never forget its passphrase.",
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Backup passphrase (8+ characters)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (allowExport) {
                OutlinedButton(
                    enabled = !busy && passphrase.length >= 8,
                    onClick = { exporter.launch("Koky-Sosy-provider-backup.kspc") },
                ) { Text("Export encrypted logins") }
            }
            Button(
                enabled = !busy && passphrase.length >= 8,
                onClick = { importer.launch(arrayOf("application/octet-stream", "*/*")) },
            ) { Text("Restore logins from file") }
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.padding(top = 4.dp))
    }
}
