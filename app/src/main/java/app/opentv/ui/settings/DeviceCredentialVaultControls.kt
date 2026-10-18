/*
 * Optional Android TV/phone shared Documents credential vault controls.
 * Storage permission is user-controlled, never implicitly requested.
 */
package app.opentv.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.PersistentDeviceCredentials
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Auto-restore can only happen AFTER Android grants access to the persistent folder
 * for the current installation. Users must explicitly approve special storage access.
 */
@Composable
fun DeviceCredentialVaultControls(onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { ServiceLocator.get(context).sourceRepository }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(PersistentDeviceCredentials.canRead(context)) }
    var enabled by remember { mutableStateOf(PersistentDeviceCredentials.enabled(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        access = PersistentDeviceCredentials.canRead(context)
        if (access) {
            scope.launch {
                busy = true
                val restored = repo.restoreDeviceLoginsIfAvailable()
                if (restored > 0) {
                    status = "$restored provider login(s) recovered from shared device storage."
                    enabled = true
                    onRestored()
                } else if (PersistentDeviceCredentials.exists(context)) {
                    status = "A saved vault exists but couldn't be decrypted. " +
                        "This can happen after signing-key changes. Use a manual backup."
                } else {
                    status = "Access granted. Enable automatic saving to create a device vault."
                }
                busy = false
            }
        } else {
            status = "Storage access wasn't granted. No shared files were read or written."
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Keep logins across uninstall (device vault)")
        Text(
            "Optional encrypted file: ${PersistentDeviceCredentials.locationLabel()}. " +
                "It stays in shared device storage after uninstall and is cleared by the " +
                "app's Factory reset. Android may require re-granting storage access after " +
                "reinstall, and changing the app signing key can prevent automatic decryption.",
        )
        if (Build.VERSION.SDK_INT < 30) {
            Text("Automatic shared-folder recovery isn't supported on this Android version.")
        } else if (!access) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try {
                        permissionLauncher.launch(intent)
                    } catch (_: Exception) {
                        try {
                            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {
                            status = "This TV does not provide the storage-access settings screen."
                        }
                    }
                },
            ) { Text("Allow shared device-folder access") }
        } else {
            Text(if (enabled) "Automatic device vault: Enabled" else "Automatic device vault: Off")
            Button(
                enabled = !busy,
                onClick = {
                    enabled = !enabled
                    PersistentDeviceCredentials.setEnabled(context, enabled)
                    if (enabled) scope.launch {
                        busy = true
                        status = if (repo.syncDeviceLogins()) {
                            "Encrypted provider credentials saved to shared device storage."
                        } else "No providers yet, or the vault could not be written."
                        busy = false
                    }
                },
            ) { Text(if (enabled) "Disable automatic saving" else "Enable automatic saving") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val count = repo.restoreDeviceLoginsIfAvailable()
                        status = when {
                            count > 0 -> "$count provider(s) recovered from saved device vault."
                            PersistentDeviceCredentials.exists(context) ->
                                "Vault found, but unavailable or credentials already present."
                            else -> "No saved device vault found."
                        }
                        busy = false
                        if (count > 0) onRestored()
                    }
                },
            ) { Text("Check device folder for saved logins") }
        }
        if (status.isNotBlank()) Text(status)
    }
}
