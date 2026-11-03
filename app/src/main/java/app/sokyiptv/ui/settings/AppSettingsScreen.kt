/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.ui.settings

import app.sokyiptv.ui.isHandheldPortrait

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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.sokyiptv.ui.onboarding.TvNativeTextField
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
import app.sokyiptv.R
import app.sokyiptv.core.AppSettings
import app.sokyiptv.player.PlaybackBufferPreset
import app.sokyiptv.player.VlcPlaybackMode
import app.sokyiptv.diagnostics.PlaybackDiagnostics
import app.sokyiptv.ui.diagnostics.PlaybackDiagnosticsDialog
import app.sokyiptv.core.SleepTimer
import app.sokyiptv.core.findActivity
import app.sokyiptv.data.work.SyncWorker

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
    val playerUiStyle by settings.playerUiStyle.collectAsState()
    val thumbnailSeekMode by settings.thumbnailSeekMode.collectAsState()
    val latencyHud by settings.uiLatencyHud.collectAsState()
    val language by settings.languageTag.collectAsState()
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    val refreshHours by settings.catalogueRefreshHours.collectAsState()
    val playbackBuffer by settings.playbackBufferPreset.collectAsState()
    val vlcMode by settings.vlcPlaybackMode.collectAsState()
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
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

    // A single eagerly composed column previously measured and laid out every
    // settings control on EACH D-pad move or text-field update. Compose only
    // the visible sections, and keep dialogs outside the lazy list so they
    // never disappear when their launch row scrolls out of view.
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_display_title), style = MaterialTheme.typography.headlineMedium,
                modifier = if (isHandheldPortrait()) Modifier.weight(1f) else Modifier,
                maxLines = if (isHandheldPortrait()) 2 else Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(if (isHandheldPortrait()) Modifier.width(8.dp) else Modifier.weight(1f))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done), maxLines = 1, softWrap = false) }
        }
        }
        item(key = "appearance") {
        SettingsSection(stringResource(R.string.settings_appearance)) {
            val themes = listOf(
                stringResource(R.string.settings_theme_system),
                stringResource(R.string.settings_theme_dark),
                stringResource(R.string.settings_theme_light),
            )
            SettingsChoiceDropdown("Appearance", themes,
                when (themeMode) {
                    AppSettings.ThemeMode.SYSTEM -> 0
                    AppSettings.ThemeMode.DARK -> 1
                    AppSettings.ThemeMode.LIGHT -> 2
                }
            ) { choice ->
                settings.setThemeMode(when (choice) {
                    1 -> AppSettings.ThemeMode.DARK
                    2 -> AppSettings.ThemeMode.LIGHT
                    else -> AppSettings.ThemeMode.SYSTEM
                })
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_theme_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
        item(key = "content") {
        SettingsSection(stringResource(R.string.settings_section_content)) {
            Text("Series automatic next episode", style = MaterialTheme.typography.titleMedium)
            Text("Automatically continue to the next episode, including across seasons.",
                style = MaterialTheme.typography.bodySmall)
            SettingsChoiceDropdown("Automatically play next episode",
                listOf("On — only after confirmed completion", "Off — ask me"),
                if (autoNext) 0 else 1,
            ) { settings.setSeriesAutoplay(it == 0) }
            Spacer(Modifier.height(16.dp))
            Text("Catalogue refresh: at app launch, only when due",
                style = MaterialTheme.typography.titleMedium)
            Text("Opening Movies or Series uses saved titles; manual Update is always available.",
                style = MaterialTheme.typography.bodySmall)
            val refreshOptions = listOf(0, 1, 3, 6, 12, 24)
            SettingsChoiceDropdown("Catalogue refresh interval",
                refreshOptions.map { hours ->
                    if (hours == 0) "Off — manual update only"
                    else "Every " + hours + if (hours == 1) " hour" else " hours"
                },
                refreshOptions.indexOf(refreshHours).coerceAtLeast(0),
            ) { choice -> settings.setCatalogueRefreshHours(refreshOptions[choice]) }
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
        }
        item(key = "language") {
        SettingsSection(stringResource(R.string.settings_section_language)) {
            val selectedName = if (language.isBlank()) stringResource(R.string.settings_language_system)
                else SokyIptvLanguages.firstOrNull { it.first == language }?.second ?: language
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
        }
        item(key = "guide") {
        SettingsSection(stringResource(R.string.settings_section_guide)) {
            Text(
                stringResource(R.string.settings_channel_layout_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SettingsChoiceDropdown("Channel guide layout", listOf(
                stringResource(R.string.settings_channel_layout_grid),
                stringResource(R.string.settings_channel_layout_list),
            ), if (channelLayout == AppSettings.ChannelLayout.GRID) 0 else 1
            ) { settings.setChannelLayout(
                if (it == 0) AppSettings.ChannelLayout.GRID else AppSettings.ChannelLayout.LIST) }
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
        }
        item(key = "vault") {
        SettingsSection("Provider logins across reinstall") {
            DeviceCredentialVaultControls()
            Spacer(Modifier.height(12.dp))
            CredentialBackupControls(allowExport = true)
        }
        }
        item(key = "playback") {
        SettingsSection(stringResource(R.string.settings_section_playback)) {
            Text("Video player controls", style = MaterialTheme.typography.titleMedium)
            Text("Choose the control layout for Movies and Series. Playback engine and seeking remain unchanged.",
                style = MaterialTheme.typography.bodySmall)
            SettingsChoiceDropdown("Player controls style",
                listOf("Current style", "Netflix-like style"),
                if (playerUiStyle == AppSettings.PlayerUiStyle.CURRENT) 0 else 1,
            ) { settings.setPlayerUiStyle(
                if (it == 0) AppSettings.PlayerUiStyle.CURRENT
                else AppSettings.PlayerUiStyle.NETFLIX_LIKE) }
            Spacer(Modifier.height(14.dp))
            Text("Thumbnail seek preview", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose how preview images are prepared when scrubbing. This does not change playback or the player layout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsChoiceDropdown("Thumbnail preview style", listOf(
                "Netflix-like seek thumbnails (default)",
                "Current disk-cached thumbnails",
            ), if (thumbnailSeekMode == AppSettings.ThumbnailSeekMode.NETFLIX_LIKE) 0 else 1
            ) { settings.setThumbnailSeekMode(
                if (it == 0) AppSettings.ThumbnailSeekMode.NETFLIX_LIKE
                else AppSettings.ThumbnailSeekMode.CURRENT_DISK) }
            Text(
                "Netflix-like: predictive broad-to-fine storyboard and current-seek priority, using a separate disk cache. " +
                    "Current: the existing five-second whole-video disk index. " +
                    "Both depend on provider support for unseen-scene extraction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Automatic intro and credits recognition learns from playback visuals on this device. Skip appears only with sufficient visual evidence; it never assumes fixed intro lengths.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
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
            SettingsChoiceDropdown("VLC playback mode", listOf(
                stringResource(R.string.settings_vlc_mode_native),
                stringResource(R.string.settings_vlc_mode_tuned),
            ), if (vlcMode == VlcPlaybackMode.NATIVE) 0 else 1
            ) { settings.setVlcPlaybackMode(
                if (it == 0) VlcPlaybackMode.NATIVE else VlcPlaybackMode.TUNED) }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.settings_playback_buffer_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Legacy v0.1.9 VLC playback uses a fixed 1.4-second cache. These presets " +
                    "continue to apply to Media3 but do not override legacy VLC caching.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val bufferChoices = PlaybackBufferPreset.entries
            SettingsChoiceDropdown("Buffering preset",
                bufferChoices.map { preset ->
                    when (preset) {
                        PlaybackBufferPreset.FAST -> context.getString(R.string.settings_buffer_fast)
                        PlaybackBufferPreset.BALANCED -> context.getString(R.string.settings_buffer_balanced)
                        PlaybackBufferPreset.STABLE -> context.getString(R.string.settings_buffer_stable)
                        PlaybackBufferPreset.LARGE -> context.getString(R.string.settings_buffer_large)
                    }
                },
                bufferChoices.indexOf(playbackBuffer).coerceAtLeast(0),
            ) { settings.setPlaybackBufferPreset(bufferChoices[it]) }
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
        }
        item(key = "live-recovery") {
            val end by settings.liveRecoveryEnd.collectAsState()
            val timeout by settings.liveNoResponseSeconds.collectAsState()
            val countdown by settings.liveSkipCountdownSeconds.collectAsState()
            val retryGap by settings.playbackRetryDelaySeconds.collectAsState()
            val retryCount by settings.playbackRetryCount.collectAsState()
            SettingsSection("Playback · Connection recovery") {
                Text("Discrete text over the video shows reconnecting attempts without " +
                    "interrupting remote or touch navigation. Connection failures never " +
                    "mark an episode complete.",
                    style = MaterialTheme.typography.bodySmall)
                val countChoices = listOf(1, 2, 3, 4, 5, 6, 7, 10)
                SettingsChoiceDropdown("Reconnection attempts",
                    countChoices.map { it.toString() + " attempts" },
                    countChoices.indexOf(retryCount).coerceAtLeast(0),
                ) { settings.setPlaybackRetryCount(countChoices[it]) }
                val gapChoices = listOf(2, 3, 5, 10)
                SettingsChoiceDropdown("Wait between retry attempts",
                    gapChoices.map { it.toString() + " seconds" },
                    gapChoices.indexOf(retryGap).coerceAtLeast(0),
                ) { settings.setPlaybackRetryDelaySeconds(gapChoices[it]) }
                SettingsChoiceDropdown("After all retry attempts fail",
                    listOf("Stop and let me intervene (default)",
                           "Next after countdown (Live: channel; Series: episode)"),
                    if (end == AppSettings.LiveRecoveryEnd.WAIT_FOR_USER) 0 else 1,
                ) { settings.setLiveRecoveryEnd(
                    if (it == 0) AppSettings.LiveRecoveryEnd.WAIT_FOR_USER
                    else AppSettings.LiveRecoveryEnd.AUTO_SKIP) }
                if (end == AppSettings.LiveRecoveryEnd.AUTO_SKIP) {
                    val secondsChoices = listOf(5, 10, 15, 30, 60)
                    SettingsChoiceDropdown("Automatic skip countdown",
                        secondsChoices.map { it.toString() + " seconds" },
                        secondsChoices.indexOf(countdown).coerceAtLeast(0),
                    ) { settings.setLiveSkipCountdownSeconds(secondsChoices[it]) }
                }
                val timeoutChoices = listOf(10, 20, 30, 60)
                SettingsChoiceDropdown("No response / buffering timeout",
                    timeoutChoices.map { it.toString() + " seconds" },
                    timeoutChoices.indexOf(timeout).coerceAtLeast(0),
                ) { settings.setLiveNoResponseSeconds(timeoutChoices[it]) }
                Text("When auto-skip is selected, Live TV moves to the next channel " +
                    "(unless recording), Series moves to the next episode without marking " +
                    "this one complete, and Movies stop for intervention. " +
                    "Recordings are never automatically skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "tmdb") {
        TmdbKeySection(settings, onOpenWebManager)
        }
        item(key = "sleep") {
        SleepTimerSection()
        }
        item(key = "performance") {
            SettingsSection("Navigation responsiveness diagnostics") {
                ToggleRow(
                    title = "Show input latency counter",
                    subtitle = "Displays remote input-to-next-frame timing on every screen. " +
                        "Use it to compare navigation when updating vs idle.",
                    checked = latencyHud,
                    onToggle = settings::setUiLatencyHud,
                )
                Text(
                    "This measures dispatch to the next frame callback, not complete " +
                        "on-screen response time. Default Off; no monitoring work when disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (languageMenuOpen) {
        val systemName = stringResource(R.string.settings_language_system)
        val matching = SokyIptvLanguages.filter { (tag, name) ->
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
 * The UI languages SokyIPTV ships translations for, each labelled with its own endonym. The tag is
 * both the stored language tag and the resource qualifier (values-<tag>), so adding a language is
 * just a new `values-xx/strings.xml` plus a line here.
 */
private val SokyIptvLanguages: List<Pair<String, String>> = listOf(
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
            val minuteOptions = SleepTimer.presets
            SettingsChoiceDropdown(
                label = stringResource(R.string.settings_sleep_timer),
                choices = listOf(stringResource(R.string.settings_sleep_off)) +
                    minuteOptions.map { stringResource(R.string.settings_sleep_minutes, it) },
                selectedIndex = if (remaining == null) 0
                    else (minuteOptions.indexOfFirst { it >= remaining }.takeIf { it >= 0 } ?: 0) + 1,
                selectedOverride = remaining?.let { "Running · " + it + " minutes left" },
            ) { selected ->
                if (selected == 0) SleepTimer.clear()
                else SleepTimer.armMinutes(minuteOptions[selected - 1])
            }
        }
    }
}


@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** One visible, D-pad focusable control per mutually exclusive choice group. */

@Composable
private fun ThemeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    // Searchable language choices only. No radio-button list in Settings.
    TextButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Text((if (selected) "✓  " else "") + label,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
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
