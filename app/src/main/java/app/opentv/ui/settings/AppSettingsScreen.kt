/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.opentv.ui.onboarding.TvNativeTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Context
import android.widget.Toast
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.player.PlaybackBufferPreset
import app.opentv.player.VlcPlaybackMode
import app.opentv.diagnostics.PlaybackDiagnostics
import app.opentv.ui.diagnostics.PlaybackDiagnosticsDialog
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.data.work.SyncWorker

/**
 * Display & playback preferences: the app-behaviour settings, kept apart from the guide/data
 * settings so neither screen becomes a junk drawer. Everything here writes straight to
 * [AppSettings] and takes effect immediately.
 */
@Composable
fun AppSettingsScreen(onBack: () -> Unit, onOpenWebManager: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val themeMode by settings.themeMode.collectAsState()
    val channelLayout by settings.channelLayout.collectAsState()
    val previewVideo by settings.guidePreviewVideo.collectAsState()
    val previewSound by settings.guidePreviewSound.collectAsState()
    val captions by settings.subtitlesEnabled.collectAsState()
    val resumeLast by settings.resumeLastChannel.collectAsState()
    val autoNext by settings.seriesAutoplay.collectAsState()
    val language by settings.languageTag.collectAsState()
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val refreshHours by settings.catalogueRefreshHours.collectAsState()
    val playbackBuffer by settings.playbackBufferPreset.collectAsState()
    val vlcMode by settings.vlcPlaybackMode.collectAsState()
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
    var refreshMenuOpen by remember { mutableStateOf(false) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var languageSearch by remember { mutableStateOf("") }

    // A refresh next to each content toggle kicks a full catalogue re-sync (which now honours the
    // toggles, so a type just switched on is fetched). Runs as background work so it isn't cut short
    // if you leave this screen; a quick Toast confirms it started.
    val refreshingMessage = stringResource(R.string.settings_content_refreshing)
    val onRefreshContent: () -> Unit = {
        SyncWorker.refreshNow(context)
        Toast.makeText(context, refreshingMessage, Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_display_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        SettingsSection(stringResource(R.string.settings_appearance)) {
            ThemeOption(stringResource(R.string.settings_theme_system), themeMode == AppSettings.ThemeMode.SYSTEM) {
                settings.setThemeMode(AppSettings.ThemeMode.SYSTEM)
            }
            ThemeOption(stringResource(R.string.settings_theme_dark), themeMode == AppSettings.ThemeMode.DARK) {
                settings.setThemeMode(AppSettings.ThemeMode.DARK)
            }
            ThemeOption(stringResource(R.string.settings_theme_light), themeMode == AppSettings.ThemeMode.LIGHT) {
                settings.setThemeMode(AppSettings.ThemeMode.LIGHT)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_theme_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(stringResource(R.string.settings_section_content)) {
            Text("Series automatic next episode", style = MaterialTheme.typography.titleMedium)
            Text("Automatically continue to the next episode, including across seasons.",
                style = MaterialTheme.typography.bodySmall)
            ThemeOption("Autoplay next episode: On", autoNext) { settings.setSeriesAutoplay(true) }
            ThemeOption("Autoplay next episode: Off", !autoNext) { settings.setSeriesAutoplay(false) }
            Spacer(Modifier.height(16.dp))
            Text("Catalogue refresh: at app launch, only when due",
                style = MaterialTheme.typography.titleMedium)
            Text("Opening Movies or Series uses saved titles; manual Update is always available.",
                style = MaterialTheme.typography.bodySmall)
            val refreshOptions = listOf(0, 1, 3, 6, 12, 24)
            val refreshLabel = if (refreshHours == 0) "Off — manual update only"
                else "Every " + refreshHours +
                    (if (refreshHours == 1) " hour" else " hours")
            OutlinedButton(
                onClick = { refreshMenuOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("$refreshLabel  ▾") }
            if (refreshMenuOpen) {
                AlertDialog(
                    onDismissRequest = { refreshMenuOpen = false },
                    title = { Text("Catalogue update frequency") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            refreshOptions.forEach { hours ->
                                val label = if (hours == 0) "Off — manual update only"
                                    else "Every $hours " +
                                        (if (hours == 1) "hour" else "hours")
                                ThemeOption(label, refreshHours == hours) {
                                    settings.setCatalogueRefreshHours(hours)
                                    refreshMenuOpen = false
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { refreshMenuOpen = false }) { Text("Close") }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_content_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ContentToggleRow(
                title = stringResource(R.string.nav_live_tv),
                subtitle = stringResource(R.string.settings_content_live_subtitle),
                checked = liveEnabled,
                onToggle = settings::setLiveEnabled,
                onRefresh = onRefreshContent,
            )
            ContentToggleRow(
                title = stringResource(R.string.nav_movies),
                subtitle = stringResource(R.string.settings_content_movies_subtitle),
                checked = moviesEnabled,
                onToggle = settings::setMoviesEnabled,
                onRefresh = onRefreshContent,
            )
            ContentToggleRow(
                title = stringResource(R.string.nav_shows),
                subtitle = stringResource(R.string.settings_content_series_subtitle),
                checked = seriesEnabled,
                onToggle = settings::setSeriesEnabled,
                onRefresh = onRefreshContent,
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(stringResource(R.string.settings_section_language)) {
            val selectedName = if (language.isBlank()) stringResource(R.string.settings_language_system)
                else OpenTvLanguages.firstOrNull { it.first == language }?.second ?: language
            OutlinedButton(
                onClick = { languageSearch = ""; languageMenuOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("$selectedName  ▾") }
            Text(
                stringResource(R.string.settings_language_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (languageMenuOpen) {
            val systemName = stringResource(R.string.settings_language_system)
            val matching = OpenTvLanguages.filter { (tag, name) ->
                languageSearch.isBlank() ||
                    name.contains(languageSearch, ignoreCase = true) ||
                    tag.contains(languageSearch, ignoreCase = true)
            }
            AlertDialog(
                onDismissRequest = { languageMenuOpen = false },
                title = { Text(stringResource(R.string.settings_section_language)) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        TvNativeTextField(
                            value = languageSearch,
                            onValueChange = { languageSearch = it },
                            label = "Search languages — OK to type",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 350.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (languageSearch.isBlank() ||
                                systemName.contains(languageSearch, ignoreCase = true)) {
                                ThemeOption(systemName, language.isBlank()) {
                                    languageMenuOpen = false
                                    changeLanguage(context, settings, "")
                                }
                            }
                            matching.forEach { (tag, name) ->
                                ThemeOption(name, language == tag) {
                                    languageMenuOpen = false
                                    changeLanguage(context, settings, tag)
                                }
                            }
                            if (matching.isEmpty()) Text("No matching languages")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { languageMenuOpen = false }) { Text("Close") }
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(stringResource(R.string.settings_section_guide)) {
            Text(
                stringResource(R.string.settings_channel_layout_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ThemeOption(
                stringResource(R.string.settings_channel_layout_grid),
                channelLayout == AppSettings.ChannelLayout.GRID,
            ) { settings.setChannelLayout(AppSettings.ChannelLayout.GRID) }
            ThemeOption(
                stringResource(R.string.settings_channel_layout_list),
                channelLayout == AppSettings.ChannelLayout.LIST,
            ) { settings.setChannelLayout(AppSettings.ChannelLayout.LIST) }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = stringResource(R.string.settings_live_preview_title),
                subtitle = stringResource(R.string.settings_live_preview_subtitle),
                checked = previewVideo,
                onToggle = settings::setGuidePreviewVideo,
            )
            ToggleRow(
                title = stringResource(R.string.settings_preview_sound_title),
                subtitle = stringResource(R.string.settings_preview_sound_subtitle),
                checked = previewSound,
                onToggle = settings::setGuidePreviewSound,
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection("Provider logins across reinstall") {
            DeviceCredentialVaultControls()
            Spacer(Modifier.height(12.dp))
            CredentialBackupControls(allowExport = true)
        }
        Spacer(Modifier.height(16.dp))

        SettingsSection(stringResource(R.string.settings_section_playback)) {
            OutlinedButton(onClick = {
                diagnosticReport = PlaybackDiagnostics.lastReport(context)
            }) {
                Text("Show latest diagnostic QR (temporary test tool)")
            }
            Text(
                "Scan this QR with your phone after playback issues or a crash. " +
                    "The last report is saved even if you close the app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.settings_vlc_mode_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BufferPresetRow(
                label = stringResource(R.string.settings_vlc_mode_native),
                selected = vlcMode == VlcPlaybackMode.NATIVE,
                onSelect = { settings.setVlcPlaybackMode(VlcPlaybackMode.NATIVE) },
            )
            BufferPresetRow(
                label = stringResource(R.string.settings_vlc_mode_tuned),
                selected = vlcMode == VlcPlaybackMode.TUNED,
                onSelect = { settings.setVlcPlaybackMode(VlcPlaybackMode.TUNED) },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_playback_buffer_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlaybackBufferPreset.entries.forEach { preset ->
                BufferPresetRow(
                    label = when (preset) {
                        PlaybackBufferPreset.FAST -> stringResource(R.string.settings_buffer_fast)
                        PlaybackBufferPreset.BALANCED -> stringResource(R.string.settings_buffer_balanced)
                        PlaybackBufferPreset.STABLE -> stringResource(R.string.settings_buffer_stable)
                        PlaybackBufferPreset.LARGE -> stringResource(R.string.settings_buffer_large)
                    },
                    selected = playbackBuffer == preset,
                    onSelect = { settings.setPlaybackBufferPreset(preset) },
                )
            }
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = stringResource(R.string.settings_subtitles_title),
                subtitle = stringResource(R.string.settings_subtitles_subtitle),
                checked = captions,
                onToggle = settings::setSubtitlesEnabled,
            )
            ToggleRow(
                title = stringResource(R.string.settings_resume_title),
                subtitle = stringResource(R.string.settings_resume_subtitle),
                checked = resumeLast,
                onToggle = settings::setResumeLastChannel,
            )
        }

        Spacer(Modifier.height(16.dp))

        TmdbKeySection(settings, onOpenWebManager)

        Spacer(Modifier.height(16.dp))

        SleepTimerSection()
    }
    diagnosticReport?.let { report ->
        PlaybackDiagnosticsDialog(
            report = report,
            onDismiss = { diagnosticReport = null },
        )
    }
}

/**
 * Optional TMDB back-fill. The user pastes their own free key; it is stored on-device in
 * [AppSettings] and used to fill posters/backdrops/synopsis/cast a provider left blank. Empty by
 * default, so the feature is off until opted into.
 */
@Composable
private fun TmdbKeySection(settings: AppSettings, onOpenWebManager: () -> Unit) {
    val context = LocalContext.current
    val savedKey by settings.tmdbApiKey.collectAsState()
    val savedToken by settings.tmdbReadToken.collectAsState()
    var field by remember(savedKey) { mutableStateOf(savedKey) }
    var tokenField by remember(savedToken) { mutableStateOf(savedToken) }
    val savedMessage = stringResource(R.string.settings_tmdb_saved)

    SettingsSection(stringResource(R.string.settings_section_metadata)) {
        OutlinedButton(onClick = onOpenWebManager) {
            Text("Enter TMDB credentials from phone (show QR code)")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_tmdb_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TvNativeTextField(
            value = field,
            onValueChange = { field = it.trim() },
            label = stringResource(R.string.settings_tmdb_key_label),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TvNativeTextField(
            value = tokenField,
            onValueChange = { tokenField = it.trim() },
            label = "TMDB API Read Access Token (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                settings.setTmdbReadToken(tokenField)
                settings.setTmdbApiKey(field)
                Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.settings_tmdb_save)) }
            if (savedKey.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.settings_tmdb_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The UI languages OpenTV ships translations for, each labelled with its own endonym. The tag is
 * both the stored language tag and the resource qualifier (values-<tag>), so adding a language is
 * just a new `values-xx/strings.xml` plus a line here.
 */
private val OpenTvLanguages: List<Pair<String, String>> = listOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "ru" to "Русский",
    "tr" to "Türkçe",
    "ar" to "العربية",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "hi" to "हिन्दी",
    "sv" to "Svenska",
    "da" to "Dansk",
    "fi" to "Suomi",
    "nb" to "Norsk",
    "cs" to "Čeština",
    "el" to "Ελληνικά",
    "ro" to "Română",
    "hu" to "Magyar",
    "uk" to "Українська",
    "id" to "Bahasa Indonesia",
    "th" to "ไทย",
    "vi" to "Tiếng Việt",
    "bg" to "Български",
    "sk" to "Slovenčina",
    "hr" to "Hrvatski",
    "fa" to "فارسی",
)

/** Persist the chosen language and recreate the activity so the whole UI reloads translated. */
private fun changeLanguage(context: Context, settings: AppSettings, tag: String) {
    if (settings.languageTag.value == tag) return
    settings.setLanguageTag(tag)
    context.findActivity()?.recreate()
}

@Composable
private fun SleepTimerSection() {
    val deadline by SleepTimer.deadline.collectAsState()
    val remaining = deadline?.let {
        val left = it - System.currentTimeMillis()
        if (left <= 0L) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    Text(
        stringResource(R.string.settings_sleep_timer),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (remaining == null) stringResource(R.string.settings_sleep_off_desc)
                else stringResource(R.string.settings_sleep_on_desc, remaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SleepOption(stringResource(R.string.settings_sleep_off), selected = remaining == null) { SleepTimer.clear() }
            SleepTimer.presets.forEach { mins ->
                SleepOption(stringResource(R.string.settings_sleep_minutes, mins), selected = false) { SleepTimer.armMinutes(mins) }
            }
        }
    }
}

@Composable
private fun SleepOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun BufferPresetRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One focus target for D-pad navigation. Avoid duplicate click handlers on the radio.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).widthIn(max = 640.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * A [ToggleRow] with a refresh button in front of the switch: toggle the content type on/off, or
 * tap refresh to re-sync it now. Only the label column toggles on tap, so the refresh button and
 * switch stay independently focusable for d-pad users.
 */
@Composable
private fun ContentToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 640.dp)
                .clickable { onToggle(!checked) },
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.settings_content_refresh),
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
