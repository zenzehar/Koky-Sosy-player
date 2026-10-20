/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sokyiptv.R
import app.sokyiptv.sync.SyncServer
import app.sokyiptv.ui.SyncViewModel

/**
 * Local watch-history sync between two SokyIPTV devices on the same wifi. No servers of ours: one
 * device shares behind a code, the other receives. Only continue-watching positions travel.
 */
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = viewModel(),
) {
    var mode by remember { mutableStateOf(Mode.SHARE) }
    // Closing or switching away from Share also closes its LAN listener. No hidden timeout.
    DisposableEffect(mode) {
        onDispose { viewModel.stopSharing() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_sync_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { viewModel.stopSharing(); onBack() }) { Text(stringResource(R.string.common_done)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                Mode.CLOUD -> "Sync watch progress across your TVs with your own Google account. IPTV credentials are never uploaded."
                Mode.NAS -> stringResource(R.string.sync_nas_desc)
                else -> stringResource(R.string.sync_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 720.dp),
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeChip(stringResource(R.string.sync_share_from_here), mode == Mode.SHARE) { mode = Mode.SHARE }
            ModeChip(stringResource(R.string.sync_receive), mode == Mode.RECEIVE) { mode = Mode.RECEIVE }
            ModeChip(stringResource(R.string.sync_nas), mode == Mode.NAS) { mode = Mode.NAS }
            ModeChip("Google Drive", mode == Mode.CLOUD) { mode = Mode.CLOUD }
        }
        Spacer(Modifier.height(20.dp))

        when (mode) {
            Mode.SHARE -> SharePane(viewModel)
            Mode.RECEIVE -> ReceivePane(viewModel)
            Mode.NAS -> NasPane(viewModel)
            Mode.CLOUD -> GoogleDriveSyncPane()
        }
    }
}

private enum class Mode { SHARE, RECEIVE, NAS, CLOUD }

@Composable
private fun SharePane(viewModel: SyncViewModel) {
    val state by viewModel.serverState.collectAsState()
    var useFixed by remember { mutableStateOf(viewModel.preferFixedCode) }
    var confirmedPin by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(18.dp).widthIn(max = 670.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Share watch history over local Wi-Fi", style = MaterialTheme.typography.titleMedium)
            Text("Both devices must be on the same network. Discovery will show the TV's name and address.")
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reuse the same six-digit code")
                Switch(
                    checked = useFixed,
                    onCheckedChange = { enabled -> if (state !is SyncServer.State.Sharing) { useFixed = enabled; viewModel.setPreferFixedCode(enabled) } },
                    enabled = state !is SyncServer.State.Sharing,
                )
            }
            if (useFixed) {
                Text("Reusable code: ${viewModel.fixedCode}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    enabled = state !is SyncServer.State.Sharing,
                    onClick = { confirmedPin = viewModel.rotateFixedCode() },
                ) { Text("Generate new reusable code") }
                if (confirmedPin.isNotEmpty()) Text("Reusable code changed. Re-pair other TVs if needed.")
            }
            when (val current = state) {
                is SyncServer.State.Sharing -> {
                    Text("Sharing is active until you stop it or leave this screen.",
                        style = MaterialTheme.typography.bodyMedium)
                    Field("Device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                    Field("Address", current.session.address)
                    Field("Pairing code", current.session.code)
                    Text(if (current.session.persistent) "Reusable code is enabled" else "Temporary pairing code")
                    OutlinedButton(onClick = { viewModel.stopSharing() }) { Text("Stop sharing") }
                }
                is SyncServer.State.Failed -> {
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.startSharing(useFixed) }) { Text("Try again") }
                }
                else -> {
                    Button(onClick = { viewModel.startSharing(useFixed) }) { Text("Start sharing") }
                }
            }
        }
    }
}

@Composable
private fun ReceivePane(viewModel: SyncViewModel) {
    val state by viewModel.receiveState.collectAsState()
    val discovery by viewModel.discoverState.collectAsState()
    var selectedAddress by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.discoverDevices() }

    Card {
        Column(Modifier.padding(18.dp).widthIn(max = 660.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Receive watch history", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = discovery !is SyncViewModel.DiscoverState.Searching,
                onClick = { viewModel.discoverDevices() },
            ) { Text(if (discovery is SyncViewModel.DiscoverState.Searching) "Searching…" else "Discover devices") }
            when (val found = discovery) {
                is SyncViewModel.DiscoverState.Results -> {
                    if (found.peers.isEmpty()) {
                        Text("No sharing TVs found. Start sharing on the other TV, and check Wi-Fi client isolation. You can still enter its IP:port below.")
                    }
                    found.peers.forEach { peer ->
                        OutlinedButton(onClick = {
                            selectedAddress = peer.address
                            entry = ""
                            viewModel.resetReceive()
                        }) {
                            Text("${peer.name}  ·  ${peer.address}" +
                                if (selectedAddress == peer.address) "  (selected)" else "")
                        }
                    }
                }
                is SyncViewModel.DiscoverState.Failed -> Text(found.detail)
                else -> Unit
            }
            if (selectedAddress.isNotBlank()) {
                Text("Selected device: $selectedAddress")
                OutlinedButton(onClick = { selectedAddress = ""; entry = "" }) { Text("Enter address manually") }
            }
            Text(
                if (selectedAddress.isNotBlank())
                    "Six-digit pairing code: ${entry.ifBlank { "______" }}"
                else "Enter IP:port#code: ${entry.ifBlank { "192.168.0.10:12345#123456" }}",
                style = MaterialTheme.typography.titleMedium,
            )
            NumPad(
                onKey = { key ->
                    if (selectedAddress.isNotBlank()) {
                        if (key.all(Char::isDigit) && entry.length < 6) entry += key
                    } else if (entry.length < 48) entry += key
                },
                onBackspace = { entry = entry.dropLast(1) },
            )
            Button(
                enabled = state !is SyncViewModel.ReceiveState.Connecting &&
                    (if (selectedAddress.isNotBlank()) entry.matches(Regex("[0-9]{6}"))
                     else entry.contains('#') && entry.substringAfter('#').matches(Regex("[0-9]{6}"))),
                onClick = {
                    val address = selectedAddress.ifBlank { entry.substringBefore('#') }
                    val code = if (selectedAddress.isNotBlank()) entry else entry.substringAfter('#')
                    viewModel.receive(address, code)
                },
            ) { Text("Connect and receive") }
            when (val result = state) {
                is SyncViewModel.ReceiveState.Connecting -> Text("Connecting (maximum 12 seconds)…")
                is SyncViewModel.ReceiveState.Done -> Text(
                    "${result.merged} matching positions updated. Open Movies/Series Continue Watching.",
                    color = MaterialTheme.colorScheme.primary,
                )
                is SyncViewModel.ReceiveState.Failed -> Text(
                    result.message, color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

/**
 * Free, server-less "cloud" sync through the user's own NAS: every device drops a small JSON file
 * into a shared folder on the SMB share and reads the others'. No server, no account. Requires the
 * NAS to be set up under Recording settings first.
 */
@Composable
private fun NasPane(viewModel: SyncViewModel) {
    val nasState by viewModel.nasState.collectAsState()
    val autoSync by viewModel.nasAutoSync.collectAsState()
    val smbHost by viewModel.smbHost.collectAsState()
    val configured = smbHost.isNotBlank()

    Card {
        Column(Modifier.padding(20.dp).widthIn(max = 640.dp)) {
            Text(stringResource(R.string.sync_nas_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sync_nas_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (!configured) {
                Text(
                    stringResource(R.string.sync_nas_needs_setup),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        enabled = nasState !is SyncViewModel.NasState.Syncing,
                        onClick = { viewModel.syncNas() },
                    ) { Text(stringResource(R.string.sync_nas_sync_now)) }

                    when (val s = nasState) {
                        is SyncViewModel.NasState.Syncing ->
                            Text(stringResource(R.string.sync_nas_syncing))
                        is SyncViewModel.NasState.Done ->
                            Text(s.message, color = MaterialTheme.colorScheme.primary)
                        is SyncViewModel.NasState.Failed ->
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.sync_nas_auto),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = autoSync, onCheckedChange = { viewModel.setNasAutoSync(it) })
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text("$label:  ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NumPad(onKey: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789", ".0:", "#⌫")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { ch ->
                    NumKey(ch.toString()) {
                        if (ch == '⌫') onBackspace() else onKey(ch.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun NumKey(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(54.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}
