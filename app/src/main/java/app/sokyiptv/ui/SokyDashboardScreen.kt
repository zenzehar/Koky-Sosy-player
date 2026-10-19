/*
 * Soky IPTV Pro Player TV-first dashboard.
 * GPL-3.0-or-later. Original visual composition, no third-party artwork.
 */
package app.sokyiptv.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import app.sokyiptv.data.work.CatalogueGate
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sokyiptv.data.work.VodSectionSyncWorker
import app.sokyiptv.data.work.LiveSectionSyncWorker
import app.sokyiptv.core.AppSettings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.data.model.Source
import app.sokyiptv.data.model.SourceKind
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val background = Color(0xFF070D25)

/** Never scroll the TV dashboard. The device must use its remote to access every action. */
@Composable
fun SokyDashboardScreen(
    hasSources: Boolean,
    sourceForFooter: Source?,
    onAddProvider: () -> Unit,
    onLive: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onRecordings: () -> Unit,
    onSearch: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    // Dedicated D-pad focus targets for both independent refresh bars.
    val liveBarFocus = remember { FocusRequester() }
    val movieBarFocus = remember { FocusRequester() }
    val seriesBarFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val update by CatalogueGate.state.collectAsState()
    val userSettings = remember(context) { AppSettings.get(context) }
    val providers by remember(context) { ServiceLocator.get(context).sourceRepository.observeAll() }
        .collectAsState(initial = emptyList())
    val providerIds = remember(providers) {
        providers.filter { it.enabled && it.kind == SourceKind.XTREAM }.map { it.id }
    }
    // Live refresh includes Xtream, M3U and Stalker providers.
    val liveProviderIds = remember(providers) {
        providers.filter { it.enabled }.map { it.id }
    }
    val liveRevision by userSettings.liveSectionRevision.collectAsState()
    val liveLastSync = remember(liveRevision, liveProviderIds) {
        userSettings.lastLiveSectionSynced(liveProviderIds)
    }
    val updateRevision by userSettings.vodSectionRevision.collectAsState()
    val movieLastSync = remember(updateRevision, providerIds) {
        userSettings.lastVodSectionSynced(false, providerIds)
    }
    val seriesLastSync = remember(updateRevision, providerIds) {
        userSettings.lastVodSectionSynced(true, providerIds)
    }
    val liveWork by remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LiveSectionSyncWorker.workName())
    }.collectAsState(initial = emptyList<WorkInfo>())
    val movieWork by remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(VodSectionSyncWorker.workName(false, null))
    }.collectAsState(initial = emptyList<WorkInfo>())
    val seriesWork by remember(context) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(VodSectionSyncWorker.workName(true, null))
    }.collectAsState(initial = emptyList<WorkInfo>())
    val manualLive = liveWork.firstOrNull { !it.state.isFinished }
    val manualMovies = movieWork.firstOrNull { !it.state.isFinished }
    val manualSeries = seriesWork.firstOrNull { !it.state.isFinished }
    val runningFullLive = update.phase == CatalogueGate.Phase.LIVE
    val runningFullMovies = update.phase == CatalogueGate.Phase.MOVIES
    val runningFullSeries = update.phase == CatalogueGate.Phase.SERIES
    val fullUpdateActive = update.phase in setOf(
        CatalogueGate.Phase.WAITING, CatalogueGate.Phase.LIVE,
        CatalogueGate.Phase.MOVIES, CatalogueGate.Phase.SERIES,
        CatalogueGate.Phase.BUILDING,
    )
    val liveFooter = VodTileUpdate(
        isSeries = false, isLive = true, lastSyncedAt = liveLastSync,
        inProgress = manualLive != null || runningFullLive,
        progress = if (manualLive != null) manualLive.progress.getInt("percent", 0)
            else (update.liveFraction * 100).toInt(),
        failed = liveWork.firstOrNull()?.state == WorkInfo.State.FAILED,
        enabled = liveProviderIds.isNotEmpty() && manualLive == null && !fullUpdateActive,
    )
    val movieFooter = VodTileUpdate(
        isSeries = false, lastSyncedAt = movieLastSync,
        inProgress = manualMovies != null || runningFullMovies,
        progress = if (manualMovies != null) manualMovies.progress.getInt("percent", 0)
            else (update.movieFraction * 100).toInt(),
        failed = movieWork.firstOrNull()?.state == WorkInfo.State.FAILED,
        enabled = providerIds.isNotEmpty() && manualMovies == null && !fullUpdateActive,
    )
    val seriesFooter = VodTileUpdate(
        isSeries = true, lastSyncedAt = seriesLastSync,
        inProgress = manualSeries != null || runningFullSeries,
        progress = if (manualSeries != null) manualSeries.progress.getInt("percent", 0)
            else (update.seriesFraction * 100).toInt(),
        failed = seriesWork.firstOrNull()?.state == WorkInfo.State.FAILED,
        enabled = providerIds.isNotEmpty() && manualSeries == null && !fullUpdateActive,
    )
    val due = CatalogueGate.due(userSettings)
    val waiting = update.phase in setOf(CatalogueGate.Phase.WAITING, CatalogueGate.Phase.LIVE,
        CatalogueGate.Phase.MOVIES, CatalogueGate.Phase.SERIES,
        CatalogueGate.Phase.BUILDING, CatalogueGate.Phase.FAILED)
    val gated = hasSources && (due || waiting)
    val liveReady = !gated || update.completed >= 1
    // Existing VOD in Room remains browseable throughout EVERY sync phase.
    val moviesReady = true
    val seriesReady = true
    val visualProgress by animateFloatAsState(
        targetValue = if (gated) update.completed / 3f else 1f,
        animationSpec = tween(durationMillis = 1300), label = "catalogueProgress",
    )
    val cache = remember(context) { context.getSharedPreferences("koky_provider_expiry", 0) }
    var expiryText by remember(sourceForFooter?.id) { mutableStateOf("Checking…") }
    LaunchedEffect(sourceForFooter?.id, sourceForFooter?.url, sourceForFooter?.username) {
        val provider = sourceForFooter
        if (provider == null) {
            expiryText = "No provider"
        } else if (provider.kind != SourceKind.XTREAM) {
            expiryText = "Not provided"
        } else {
            val key = "expiry_${provider.id}"
            val cached = cache.getLong(key, 0L)
            expiryText = if (cached > 0L) DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(cached)) else "Checking…"
            val result = ServiceLocator.get(context).sourceRepository.expiryMillis(provider)
            if (result.isSuccess) {
                val expiry = result.getOrNull()
                expiryText = if (expiry != null && expiry > 0L) {
                    cache.edit().putLong(key, expiry).apply()
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiry))
                } else "Not supplied"
            } else if (cached == 0L) {
                expiryText = "Unavailable, retry later"
            }
        }
    }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(background).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SOKY IPTV PRO", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            HeaderAction(Icons.Filled.Search, "Master Search", onSearch)
            HeaderAction(Icons.Filled.Person, "Profiles", onProfiles)
            HeaderAction(Icons.Filled.Settings, "Settings", onSettings)
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DashboardTile(
                label = "LIVE TV", icon = Icons.Filled.LiveTv,
                // Live has the same non-blocking access policy as Movies/Series:
                // a pending/full refresh must never grey out cached TV channels.
                // The per-section bottom bar alone communicates update progress.
                locked = false, loading = false, ready = true, progress = 1f,
                vodUpdate = liveFooter,
                vodBarFocus = liveBarFocus,
                vodPeerFocus = movieBarFocus,
                onVodRefresh = { LiveSectionSyncWorker.refresh(context) },
                shades = listOf(Color(0xFF31CEA1), Color(0xFF3442C8)),
                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(initialFocus),
                onClick = if (hasSources) onLive else onAddProvider,
            )
            Column(
                Modifier.weight(1.92f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DashboardTile(
                        label = "MOVIES", icon = Icons.Filled.Movie,
                        locked = false, loading = false,
                        progress = 1f,
                        vodUpdate = movieFooter,
                        vodBarFocus = movieBarFocus,
                        vodPeerFocus = seriesBarFocus,
                        onVodRefresh = {
                            VodSectionSyncWorker.refresh(context, isSeries = false, sourceId = null)
                        },
                        shades = listOf(Color(0xFFDB2D53), Color(0xFFF49A3D)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = if (hasSources) onMovies else onAddProvider,
                    )
                    DashboardTile(
                        label = "SERIES", icon = Icons.Filled.Tv,
                        locked = false, loading = false,
                        progress = 1f,
                        vodUpdate = seriesFooter,
                        vodBarFocus = seriesBarFocus,
                        vodPeerFocus = movieBarFocus,
                        onVodRefresh = {
                            VodSectionSyncWorker.refresh(context, isSeries = true, sourceId = null)
                        },
                        shades = listOf(Color(0xFF953FAF), Color(0xFF4B9EDD)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = if (hasSources) onSeries else onAddProvider,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(90.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DashboardTile(
                        label = "LIVE WITH EPG", icon = Icons.Filled.LiveTv,
                        shades = listOf(Color(0xFF5B9D82), Color(0xFF5B9D82)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = if (hasSources) onLive else onAddProvider, compact = true,
                    )
                    DashboardTile(
                        label = "RECORDINGS", icon = Icons.Filled.FiberManualRecord,
                        shades = listOf(Color(0xFF308B84), Color(0xFF308B84)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = onRecordings, compact = true,
                    )
                    DashboardTile(
                        label = "UPDATE", icon = Icons.Filled.Refresh,
                        shades = listOf(Color(0xFF308B84), Color(0xFF308B84)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = onRefresh, compact = true,
                    )
                }
            }
        }
        // Section progress is drawn INSIDE each coloured tile, never as a
        // detached Home-bottom progress bar.
        if (gated && update.phase == CatalogueGate.Phase.BUILDING) {
            Text("Finalising guide and catalogue…", color = Color.White, fontSize = 13.sp)
        }
        if (gated && update.phase == CatalogueGate.Phase.FAILED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(update.message, color = Color(0xFFFFC6B8), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                HeaderAction(Icons.Filled.Refresh, "Retry update", onRefresh)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                sourceForFooter?.name?.let { "Playlist: $it" } ?: "Select a tile to add your IPTV provider",
                color = Color.White.copy(alpha = 0.80f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Subscription expiry: $expiryText",
                color = Color.White.copy(alpha = 0.80f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.padding(start = 8.dp).size(54.dp).clip(shape)
            .background(if (focused) Color(0xFF263968) else Color(0xFF131C3D))
            .border(if (focused) 3.dp else 0.dp, Color.White, shape)
            .onFocusChanged { focused = it.isFocused }
             .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(29.dp))
    }
}

@Composable
private fun DashboardTile(
    label: String,
    icon: ImageVector,
    shades: List<Color>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    locked: Boolean = false,
    loading: Boolean = false,
    ready: Boolean = false,
    progress: Float = 1f,
    vodUpdate: VodTileUpdate? = null,
    vodBarFocus: FocusRequester? = null,
    vodPeerFocus: FocusRequester? = null,
    onVodRefresh: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tileFocus = remember { FocusRequester() }
    val internalBarFocus = remember { FocusRequester() }
    val barFocus = vodBarFocus ?: internalBarFocus
    val shape = RoundedCornerShape(14.dp)
    // Tween genuinely measured fractions from Room import completion.
    val reveal by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "fill$label",
    )
    Box(
        modifier.clip(shape)
            .border(if (focused) 3.dp else 0.dp, Color.White, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(shades))
                .focusRequester(tileFocus)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { if (vodUpdate != null) down = barFocus }
            // TV focus search sometimes skips overlaid footer rows geometrically.
            .onPreviewKeyEvent { event ->
                if (vodUpdate != null && event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown) {
                    barFocus.requestFocus()
                    true
                } else false
            }
            .clickable(enabled = !locked, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(if (compact) 27.dp else 68.dp))
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 14.sp else 27.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vodUpdate != null) {
            DashboardVodUpdateBar(
                state = vodUpdate,
                onRefresh = onVodRefresh,
                barFocus = barFocus,
                tileFocus = tileFocus,
                peerFocus = vodPeerFocus,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().padding(horizontal = 9.dp, vertical = 9.dp),
            )
        }
        if (locked || (loading && !ready)) {
            // The uncoloured area shrinks from TOP to BOTTOM so the tile itself
            // fills progressively, matching the IPTV reference behaviour.
            val remaining = (1f - reveal).coerceIn(0f, 1f)
            if (remaining > 0.001f) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .fillMaxHeight(remaining).background(Color(0xD90A0D18)))
            }
            if (!compact) {
                Text(
                    when {
                        loading && progress <= 0f -> "FETCHING…"
                        loading -> "${(reveal * 100).toInt()}%"
                        else -> "WAITING"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 11.dp),
                )
            }
        }
    }
}


private data class VodTileUpdate(
    val isSeries: Boolean,
    val isLive: Boolean = false,
    val lastSyncedAt: Long,
    val inProgress: Boolean,
    val progress: Int,
    val failed: Boolean,
    val enabled: Boolean,
)

/** Independent focusable refresh action INSIDE the bottom of each home-screen VOD card. */
@Composable
private fun DashboardVodUpdateBar(
    state: VodTileUpdate,
    onRefresh: () -> Unit,
    barFocus: FocusRequester,
    tileFocus: FocusRequester,
    peerFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    val time = remember(state.lastSyncedAt) {
        if (state.lastSyncedAt <= 0L) "Never updated"
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(state.lastSyncedAt))
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xD8131A2C))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color(0xFF75DCEB) else Color.White.copy(alpha = .22f),
                shape,
            )
            .focusRequester(barFocus)
            .focusProperties {
                up = tileFocus
                if (peerFocus != null) {
                    if (state.isSeries) left = peerFocus else right = peerFocus
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    tileFocus.requestFocus()
                    true
                } else false
            }
            .onFocusChanged { focused = it.isFocused }
            // Keep the progress bar reachable while updating; OK is guarded.
            .clickable(onClick = { if (state.enabled) onRefresh() })
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when {
                state.inProgress -> "Updating ${if (state.isLive) "Live TV" else if (state.isSeries) "Series" else "Movies"} · ${state.progress.coerceIn(0, 100)}%"
                else -> "Updated: $time"
            },
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            state.inProgress ->
                CircularProgressIndicator(modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp, color = Color.White)
            state.failed && state.enabled ->
                Text("Retry", color = Color(0xFFFFC4A5), fontSize = 11.sp)
        }
        Icon(
            Icons.Filled.Refresh,
            contentDescription = "Refresh ${if (state.isLive) "Live TV" else if (state.isSeries) "Series" else "Movies"} catalogue",
            tint = Color.White.copy(alpha = if (state.enabled) 1f else .45f),
            modifier = Modifier.size(19.dp),
        )
    }
}
