/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import android.widget.Toast
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.isRunningOnTelevision
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.settings.CredentialBackupControls
import app.opentv.ui.settings.DeviceCredentialVaultControls
import app.opentv.ui.channels.readClipboardText

/**
 * First-run setup.
 *
 * Two things this screen does that most IPTV players do not:
 *
 * 1. **Test before saving.** The user finds out the password is wrong here, with a sentence
 *    explaining what to change, rather than staring at an empty channel list later.
 * 2. **Says where the credentials go.** People are handing over their provider login. They
 *    are entitled to know it stays on the device, and to be told so without having to go
 *    looking for a privacy policy.
 */
@Composable
fun AddSourceScreen(
    viewModel: SourcesViewModel,
    onFinished: () -> Unit,
    onListUsers: () -> Unit,
    editingSourceId: Long? = null,
) {
    val context = LocalContext.current
    // The QR "set it up from your phone" flow only makes sense on a TV — it serves a setup page
    // FROM this device for a phone to fill in. On a phone that same screen shows a QR meant to be
    // scanned BY a phone, which is nonsensical and traps the user — so a non-TV device defaults
    // straight to the direct entry form. On a TV the QR is still offered first, because typing a
    // server address and password with a d-pad is the worst moment in every app of this kind.
    val isTelevision = remember(context) { isRunningOnTelevision(context) }
    // Editing an existing provider always uses the direct form (the fields are pre-filled, and there
    // is nothing to pair); only a fresh add on a TV offers the phone-pairing QR first.
    var usePhone by remember { mutableStateOf(false) }
    var restoreBackup by remember { mutableStateOf(false) }

    if (restoreBackup) {
        Column(
            Modifier.fillMaxSize().padding(36.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Restore your providers after reinstalling", style = MaterialTheme.typography.headlineSmall)
            DeviceCredentialVaultControls(onRestored = onFinished)
            CredentialBackupControls(allowExport = false, onRestored = onFinished)
            OutlinedButton(onClick = { restoreBackup = false }) {
                Text("Return to login")
            }
        }
        return
    }

    if (usePhone) {
        PhonePairingScreen(
            // Even on a failed sync, leave the pairing screen: the source is already saved, so the
            // home screen shows either the guide (success) or a recoverable error (failure) rather
            // than stranding the user on an endless "Got it — connecting…" spinner.
            onReceived = { draft ->
                viewModel.saveAndSync(draft) { ok ->
                    if (ok) onFinished() else usePhone = false
                }
            },
            onCancel = { usePhone = false },
        )
        return
    }

    val ui by viewModel.ui.collectAsState()

    // When editing, find the source being edited and pre-fill every field from it. Keyed on its id
    // so the fields seed once the list has loaded; a fresh add leaves everything blank.
    val existing = remember(editingSourceId, ui.sources) {
        editingSourceId?.let { id -> ui.sources.firstOrNull { it.id == id } }
    }

    var kind by remember(existing?.id) { mutableStateOf(existing?.kind ?: SourceKind.XTREAM) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
    var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "") }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var mac by remember(existing?.id) { mutableStateOf(existing?.macAddress ?: "") }
    var epgUrl by remember(existing?.id) { mutableStateOf(existing?.epgUrl ?: "") }
    var showAdvanced by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var focusedLeft by remember { mutableStateOf("") }
    var restoreFocused by remember { mutableStateOf(false) }
    var testFocused by remember { mutableStateOf(false) }
    var userAgent by remember(existing?.id) { mutableStateOf(existing?.userAgent ?: Source.DEFAULT_USER_AGENT) }

    // Editing copies onto the existing row, so id, enabled state, live format and last-sync stamp
    // are preserved; a fresh add starts from a blank Source (id 0, so save() inserts).
    fun draft(): Source = (existing ?: Source(name = "", kind = kind, url = "")).copy(
        name = name.ifBlank {
            if (kind == SourceKind.M3U) context.getString(R.string.onboarding_default_playlist_name)
            else context.getString(R.string.onboarding_default_provider_name)
        },
        kind = kind,
        url = url,
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        macAddress = mac.takeIf { it.isNotBlank() },
        epgUrl = epgUrl.takeIf { it.isNotBlank() },
        userAgent = userAgent.ifBlank { Source.DEFAULT_USER_AGENT },
    )

    val canSubmit = url.isNotBlank() && when (kind) {
        SourceKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
        SourceKind.M3U -> true
        SourceKind.STALKER -> mac.isNotBlank()
    }

    // Reference composition: large brand at left, four large credentials at right.
    // The IPTV Smarters logo is NOT copied: Koky & Sosy uses independent artwork.
    val whiteButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color(0xFF24172F),
        disabledContainerColor = Color.White.copy(alpha = 0.45f),
        disabledContentColor = Color(0xFF5F5762),
    )
    Row(
        modifier = Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(
                Color(0xFF4E28CB), Color(0xFF8D40A7), Color(0xFFE97893),
            )))
            .padding(horizontal = 35.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(35.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(0.82f).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(width = 225.dp, height = 130.dp)) {
                val px = 1.dp.toPx()
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(21 * px, 36 * px),
                    size = Size(183 * px, 85 * px),
                    cornerRadius = CornerRadius(25 * px),
                    style = Stroke(width = 5 * px),
                )
                drawLine(Color.White, Offset(104 * px, 34 * px), Offset(82 * px, 5 * px), 4 * px)
                drawLine(Color.White, Offset(117 * px, 34 * px), Offset(140 * px, 5 * px), 4 * px)
                drawCircle(Color.White, 5 * px, Offset(82 * px, 5 * px))
                drawCircle(Color.White, 5 * px, Offset(140 * px, 5 * px))
            }
            Text("KOKY", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light)
            Text("& SOSY PLAYER", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(23.dp))
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                    }.onFailure {
                        Toast.makeText(context, "VPN settings are not available on this device.", Toast.LENGTH_LONG).show()
                    }
                },
                colors = whiteButtonColors,
                modifier = Modifier.width(200.dp).height(49.dp)
                    .onFocusChanged { 
                        if (it.isFocused) focusedLeft = "vpn"
                        else if (focusedLeft == "vpn") focusedLeft = ""
                    }
                    .border(if (focusedLeft == "vpn") 3.dp else 0.dp,
                        Color(0xFFFFE177), RoundedCornerShape(8.dp)),
            ) { Text("CONNECT VPN") }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { usePhone = true },
                enabled = isTelevision,
                colors = whiteButtonColors,
                modifier = Modifier.width(200.dp).height(49.dp)
                    .onFocusChanged { 
                        if (it.isFocused) focusedLeft = "phone"
                        else if (focusedLeft == "phone") focusedLeft = ""
                    }
                    .border(if (focusedLeft == "phone") 3.dp else 0.dp,
                        Color(0xFFFFE177), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("LOGIN BY PHONE")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { restoreBackup = true },
                border = null,
                modifier = Modifier.onFocusChanged { restoreFocused = it.isFocused }
                    .border(if (restoreFocused) 2.dp else 0.dp,
                        Color(0xFFFFE177), RoundedCornerShape(8.dp)),
            ) {
                Text("RESTORE LOGIN BACKUP")
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (choice in SourceKind.entries) {
                    OutlinedButton(
                        onClick = { kind = choice },
                        border = null,
                        modifier = Modifier
                            .onFocusChanged {
                                if (it.isFocused) focusedLeft = choice.name
                                else if (focusedLeft == choice.name) focusedLeft = ""
                            }
                            .border(if (focusedLeft == choice.name) 3.dp else 0.dp,
                                Color(0xFFFFE177), RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (kind == choice) Color.White else Color.Transparent,
                            contentColor = if (kind == choice) Color(0xFF40277E) else Color.White,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 5.dp),
                    ) {
                        Text(when (choice) {
                            SourceKind.XTREAM -> "XTREAM"
                            SourceKind.M3U -> "M3U"
                            SourceKind.STALKER -> "STALKER"
                        }, fontSize = 11.sp)
                    }
                }
            }
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier
                    .onFocusChanged { 
                        if (it.isFocused) focusedLeft = "advanced"
                        else if (focusedLeft == "advanced") focusedLeft = ""
                    }
                    .border(if (focusedLeft == "advanced") 3.dp else 0.dp,
                        Color(0xFFFFE177), RoundedCornerShape(8.dp)),
            ) {
                Text(if (showAdvanced) "HIDE ADVANCED SETTINGS" else "ADVANCED SETTINGS", color = Color.White)
            }
        }
        Column(
            modifier = Modifier.weight(1.22f).fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (existing == null) "Enter Your Login Details" else "Edit Your Login Details",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Spacer(Modifier.height(17.dp))
            TvNativeTextField(
                value = name,
                onValueChange = { name = it },
                label = "Playlist name",
                modifier = Modifier.fillMaxWidth(),
                initialFocus = true,
                smartersStyle = true,
            )
            Spacer(Modifier.height(9.dp))
            if (kind == SourceKind.XTREAM) {
                TvNativeTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username",
                    modifier = Modifier.fillMaxWidth(),
                    smartersStyle = true,
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvNativeTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        modifier = Modifier.weight(1f),
                        visualTransformation = if (showPassword)
                            androidx.compose.ui.text.input.VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        smartersStyle = true,
                    )
                    Spacer(Modifier.width(7.dp))
                    Button(
                        onClick = { showPassword = !showPassword },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5B9C8),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.width(76.dp).height(60.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Icon(
                            if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            modifier = Modifier.size(31.dp),
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
            } else if (kind == SourceKind.STALKER) {
                TvNativeTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = "MAC address",
                    modifier = Modifier.fillMaxWidth(),
                    smartersStyle = true,
                )
                Spacer(Modifier.height(9.dp))
            }
            TvNativeTextField(
                value = url,
                onValueChange = { url = it },
                label = when (kind) {
                    SourceKind.XTREAM -> "http://server-address:port"
                    SourceKind.M3U -> "M3U playlist URL"
                    SourceKind.STALKER -> "Portal URL"
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                smartersStyle = true,
            )
            Spacer(Modifier.height(9.dp))
            Button(
                onClick = { viewModel.saveAndSync(draft()) { ok -> if (ok) onFinished() } },
                enabled = canSubmit && !ui.testing && !ui.syncing,
                modifier = Modifier.fillMaxWidth().height(57.dp),
                colors = whiteButtonColors,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            ) {
                Text(if (ui.syncing) "VALIDATING…" else "ADD USER", fontSize = 22.sp)
            }
            Spacer(Modifier.height(5.dp))
            TextButton(onClick = onListUsers, modifier = Modifier.height(42.dp)) {
                Icon(Icons.Filled.People, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("List Users", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = { viewModel.test(draft()) },
                enabled = canSubmit && !ui.testing && !ui.syncing,
                border = null,
                modifier = Modifier.onFocusChanged { testFocused = it.isFocused }
                    .border(if (testFocused) 2.dp else 0.dp,
                        Color(0xFFFFE177), RoundedCornerShape(8.dp)),
            ) {
                if (ui.testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("TEST CONNECTION", color = Color.White, fontSize = 12.sp)
            }

            if (showAdvanced) {
                Spacer(Modifier.height(10.dp))
                TvNativeTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = "EPG URL (optional)",
                    modifier = Modifier.fillMaxWidth(),
                    smartersStyle = true,
                )
                Spacer(Modifier.height(9.dp))
                TvNativeTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = "User-Agent",
                    modifier = Modifier.fillMaxWidth(),
                    smartersStyle = true,
                )
            }
            ui.testResult?.let { msg ->
                Spacer(Modifier.height(5.dp))
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            ui.testError?.let { err ->
                Spacer(Modifier.height(5.dp))
                Text(err, color = Color(0xFFFFF3A0), style = MaterialTheme.typography.bodySmall)
            }
            ui.syncMessage?.let { status ->
                Spacer(Modifier.height(5.dp))
                Text(status, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * A trailing "paste" affordance for a provider field: reads the system clipboard and appends it
 * via [onPaste]. A long Xtream URL or username is miserable to type on a d-pad, so this is exactly
 * what the reporter asked for. Reads the same clipboard as the on-screen keyboard's Paste key; an
 * empty clipboard just shows a brief message rather than doing nothing silently.
 */
@Composable
private fun PasteButton(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val pasted = readClipboardText(context)
            if (pasted != null) onPaste(pasted)
            else Toast.makeText(context, context.getString(R.string.kbd_clipboard_empty), Toast.LENGTH_SHORT).show()
        },
    ) {
        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.kbd_paste))
    }
}
