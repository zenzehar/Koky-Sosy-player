/*
 * Koky & Sosy Player TV-first dashboard.
 * GPL-3.0-or-later. Original visual composition, no third-party artwork.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import app.opentv.data.work.CatalogueGate
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
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
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
fun KokyDashboardScreen(
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
    val context = LocalContext.current
    val update by CatalogueGate.state.collectAsState()
    val userSettings = remember(context) { app.opentv.core.AppSettings.get(context) }
    val due = CatalogueGate.due(userSettings)
    val waiting = update.phase in setOf(CatalogueGate.Phase.WAITING, CatalogueGate.Phase.LIVE,
        CatalogueGate.Phase.MOVIES, CatalogueGate.Phase.SERIES,
        CatalogueGate.Phase.BUILDING, CatalogueGate.Phase.FAILED)
    val gated = hasSources && (due || waiting)
    val liveReady = !gated || update.completed >= 1
    val moviesReady = !gated || update.phase == CatalogueGate.Phase.COMPLETE
    val seriesReady = !gated || update.phase == CatalogueGate.Phase.COMPLETE
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
                "KOKY & SOSY PLAYER", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            HeaderAction(Icons.Filled.Search, "Master Search",
                onClick = { if (!gated) onSearch() })
            HeaderAction(Icons.Filled.Person, "Profiles", onProfiles)
            HeaderAction(Icons.Filled.Settings, "Settings", onSettings)
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DashboardTile(
                label = "LIVE TV", icon = Icons.Filled.LiveTv,
                locked = !liveReady, loading = gated && update.phase == CatalogueGate.Phase.LIVE,
                ready = gated && liveReady, progress = if (liveReady) 1f else update.liveFraction,
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
                        locked = !moviesReady, loading = gated && update.phase == CatalogueGate.Phase.MOVIES,
                        ready = gated && moviesReady,
                        progress = if (moviesReady) 1f else update.movieFraction,
                        shades = listOf(Color(0xFFDB2D53), Color(0xFFF49A3D)),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = if (hasSources) onMovies else onAddProvider,
                    )
                    DashboardTile(
                        label = "SERIES", icon = Icons.Filled.Tv,
                        locked = !seriesReady, loading = gated && update.phase == CatalogueGate.Phase.SERIES,
                        ready = gated && seriesReady,
                        progress = if (seriesReady) 1f else update.seriesFraction,
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
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    // Tween genuinely measured fractions from Room import completion.
    val reveal by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "fill$label",
    )
    Box(
        modifier.clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, Color.White, shape)
            .clickable(enabled = !locked, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(shades)),
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
