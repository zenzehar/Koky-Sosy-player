package app.sokyiptv.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sokyiptv.core.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal object RollbackUi {
    val error = MutableStateFlow<String?>(null)
}

/** Only rendered during the 24 hours after a real in-place update with known old version. */
@Composable
fun RollbackSettings() {
    val context = LocalContext.current
    val http = remember(context) { ServiceLocator.get(context).httpClient }
    val repository = remember(http) { RollbackRepository(http) }
    val scope = rememberCoroutineScope()
    var window by remember { mutableStateOf(RollbackStore.window(context)) }
    var asset by remember { mutableStateOf<RollbackRepository.Asset?>(null) }
    var lookupFinished by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var installing by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val installError by RollbackUi.error.collectAsState()

    LaunchedEffect(window?.sourceVersion, window?.previousVersion) {
        asset = null
        lookupFinished = false
        val eligible = window
        if (eligible != null) {
            asset = repository.find(eligible)
            lookupFinished = true
        }
    }
    LaunchedEffect(window?.expiresAtMs) {
        while (window != null) {
            delay(15_000L)
            val refreshed = RollbackStore.window(context)
            if (refreshed == null) {
                RollbackStore.prune(context)
                window = null
                asset = null
                confirmation = false
                break
            }
        }
    }

    val offer = window ?: return
    val remaining = (offer.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    if (remaining == 0L) return
    Spacer(Modifier.height(16.dp))
    Text("24-hour rollback", style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Return to the exact code of " + offer.previousVersion +
            " without uninstalling Soky or clearing your provider logins, settings or watch progress.")
        Text("Offer closes automatically 24 hours after this update. " +
            "No rollback APK is stored on your TV. Internet is needed to download the signed rollback build.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val hours = remaining / 3_600_000L
        val mins = (remaining / 60_000L) % 60L
        Text("Remaining: " + hours + "h " + mins + "m")
        when {
            !lookupFinished -> Text("Checking for compatible signed rollback package…")
            asset == null -> Text("No verified rollback package for the version that was installed before this update.",
                color = MaterialTheme.colorScheme.error)
            else -> OutlinedButton(
                enabled = !installing,
                onClick = { confirmation = true },
            ) { Text(if (installing) "Preparing rollback…" else "Rollback to " + offer.previousVersion) }
        }
        if (installing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        info?.let { Text(it) }
        installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmation && asset != null) {
        AlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text("Return to the previous version?") },
            text = { Text(
                "Soky will install the previous version's code as a specially signed update. " +
                "Your app data is not deleted. Android will ask you to approve the installation. " +
                "The rollback must be started before the 24-hour deadline. " +
                "If you have made important changes, keep an encrypted backup first."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = false
                    val selected = asset ?: return@TextButton
                    val current = RollbackStore.window(context) ?: return@TextButton
                    installing = true
                    progress = 0f
                    info = null
                    RollbackUi.error.value = null
                    scope.launch {
                        runCatching {
                            RollbackInstaller(http).install(
                                context, selected, current.expiresAtMs,
                            ) { fraction -> progress = fraction }
                        }.onSuccess {
                            info = "Download verified by the installer; approve the system installation prompt."
                        }.onFailure {
                            RollbackUi.error.value = it.message ?: "Rollback could not be installed."
                        }
                        installing = false
                    }
                }) { Text("Continue to Android installer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = false }) { Text("Cancel") }
            },
        )
    }
}
