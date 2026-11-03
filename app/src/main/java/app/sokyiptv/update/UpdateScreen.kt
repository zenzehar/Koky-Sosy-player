/* Soky IPTV Pro updater: WorkManager-backed, resumable, foreground install only.
 * GPL-3.0-or-later. */
package app.sokyiptv.update

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sokyiptv.BuildConfig
import app.sokyiptv.core.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun UpdateGate(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val transfer by viewModel.transfer.collectAsState()
    var dialogVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(transfer.version) {
        if (transfer.version.isNotBlank()) dialogVisible = true
    }
    if (transfer.stage in listOf("downloading", "paused", "failed", "ready") &&
        dialogVisible) {
        AlertDialog(
            onDismissRequest = { dialogVisible = false }, // Transfer continues independently.
            title = {
                Text(when (transfer.stage) {
                    "paused" -> "Update paused"
                    "failed" -> "Download interrupted"
                    "ready" -> "Update ready to install"
                    else -> "Downloading update"
                })
            },
            confirmButton = {
                when (transfer.stage) {
                    "downloading" -> TextButton(onClick = viewModel::pause) { Text("Pause") }
                    "paused", "failed" -> TextButton(onClick = viewModel::resume) { Text("Resume") }
                    "ready" -> TextButton(onClick = viewModel::installReady) { Text("Install") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (transfer.stage == "ready") {
                        dialogVisible = false
                    } else viewModel.abort()
                }) {
                    Text(if (transfer.stage == "ready") "Later" else "Abort")
                }
                TextButton(onClick = { dialogVisible = false }) {
                    Text("Background")
                }
            },
            text = {
                Column {
                    Text("Version " + transfer.version)
                    if (transfer.stage != "ready") {
                        if (transfer.fraction >= 0f) {
                            LinearProgressIndicator(
                                progress = { transfer.fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text((transfer.fraction * 100).toInt().toString() + "%",
                                Modifier.padding(top = 8.dp))
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (transfer.stage == "failed")
                        Text("Download stopped: " + transfer.error +
                            ". Resume will continue from the verified partial file.")
                    if (transfer.stage == "ready")
                        Text("Download complete. Android will ask you to approve installation.")
                    if (transfer.stage == "downloading")
                        Text("You can send the app to the background; downloading continues.")
                }
            },
        )
        return
    }

    when (val s = state) {
        UpdateUiState.Idle -> Unit
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = { viewModel.install(); dialogVisible = true }) {
                Text("Update") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Later") } },
            title = { Text("Update available") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Soky IPTV Pro " + s.update.versionName + " is available. You have " +
                        BuildConfig.VERSION_NAME + ".")
                    if (s.update.notes.isNotBlank()) {
                        Text(s.update.notes, modifier = Modifier.padding(top = 12.dp),
                            maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        )
        else -> Unit
    }
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
}

/** Manual update discovery in About and automatic startup share one prompt. */
object UpdateHub {
    val state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val checker = UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME)
    private val installer = ApkInstaller(graph.httpClient)
    private val _state = UpdateHub.state
    val state = _state.asStateFlow()
    val transfer = UpdateTransfer.state

    init {
        UpdateTransfer.restore(app)
        checkThrottled()
    }

    private fun checkThrottled() {
        viewModelScope.launch {
            if (transfer.value.stage in listOf("downloading", "paused", "ready", "failed")) return@launch
            val prefs = getApplication<Application>()
                .getSharedPreferences("sokyiptv", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastVersion = prefs.getString(KEY_LAST_CHECK_VERSION, null)
            if (lastVersion == BuildConfig.VERSION_NAME &&
                now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return@launch
            val update = checker.check()
            prefs.edit().putLong(KEY_LAST_CHECK, now)
                .putString(KEY_LAST_CHECK_VERSION, BuildConfig.VERSION_NAME).apply()
            if (update != null && transfer.value.stage == "none")
                _state.value = UpdateUiState.Available(update)
        }
    }

    fun install() {
        val update = (state.value as? UpdateUiState.Available)?.update ?: return
        _state.value = UpdateUiState.Idle
        UpdateTransfer.start(getApplication(), update)
    }

    fun pause() = UpdateTransfer.pause(getApplication())
    fun resume() = UpdateTransfer.resume(getApplication())
    fun abort() {
        UpdateTransfer.abort(getApplication())
        _state.value = UpdateUiState.Idle
    }

    fun installReady() {
        val snapshot = transfer.value
        if (snapshot.stage != "ready") return
        viewModelScope.launch {
            runCatching {
                RollbackStore.markUpdateAttempt(getApplication(), snapshot.version)
                installer.installDownloaded(
                    context = getApplication(),
                    apk = UpdateTransfer.ready(getApplication()),
                    expectedBytes = snapshot.expectedBytes,
                    targetVersion = snapshot.version,
                )
            }.onFailure {
                UpdateTransfer.change(getApplication(),
                    snapshot.copy(stage = "failed", error = it.javaClass.simpleName))
            }
        }
    }

    fun dismiss() { _state.value = UpdateUiState.Idle }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check"
        const val KEY_LAST_CHECK_VERSION = "last_update_check_version"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
