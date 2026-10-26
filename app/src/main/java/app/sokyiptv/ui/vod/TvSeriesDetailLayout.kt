/*
 * Soky IPTV Pro: screenshot-guided TV series detail screen.
 * GPL-3.0-or-later. Original branding and native D-pad navigation.
 */
package app.sokyiptv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sokyiptv.data.model.PlaybackPosition
import app.sokyiptv.data.remote.EpisodeRuntimeHints
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.sokyiptv.core.AppSettings
import androidx.compose.ui.unit.sp
import app.sokyiptv.data.model.Episode
import app.sokyiptv.data.model.Series
import app.sokyiptv.data.parser.displayTitle
import app.sokyiptv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun TvSeriesDetailLayout(
    seriesId: Long,
    onPlayEpisode: (String, String, String) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel,
    onOpenPerson: (String) -> Unit = {},
) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    var expandedPlot by rememberSaveable(seriesId) { mutableStateOf(false) }
    var selectedSeason by rememberSaveable(seriesId) { mutableStateOf(-1) }
    var showCast by rememberSaveable(seriesId) { mutableStateOf(false) }
    var lastPlayedEpisode by rememberSaveable(seriesId) { mutableStateOf(0L) }
    var seasonMenuOpen by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var resumeEpisode by remember(seriesId) { mutableStateOf<Episode?>(null) }
    var resumeProgress by remember(seriesId) { mutableStateOf<PlaybackPosition?>(null) }
    var foregroundEpoch by remember(seriesId) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foregroundEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var playFocused by remember { mutableStateOf(false) }
    var seasonFocused by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    var initialActionFocused by remember(seriesId) { mutableStateOf(false) }
    val playedFocus = remember { FocusRequester() }
    val episodeListState = rememberLazyListState()
    // Do not probe the same unhelpful stream repeatedly on recompositions,
    // season switches or returning from video. Stored per series-detail visit.
    val attemptedEpisodeDurations = remember(seriesId) { mutableSetOf<Long>() }
    val preferences = remember { AppSettings.get(viewModel.getApplication()) }
    val autoNext by preferences.seriesAutoplay.collectAsState()
    var portraits by remember(seriesId) {
        mutableStateOf<List<app.sokyiptv.data.remote.TmdbCastPortrait>>(emptyList())
    }
    LaunchedEffect(seriesId, showCast, series) {
        val item = series
        if (showCast && item != null) {
            portraits = runCatching { viewModel.seriesCastPortraits(item) }.getOrDefault(emptyList())
        }
    }
    // Retain the same Flow across recompositions. New Room Flow instances on every
    // focus/navigation recomposition caused subscription churn when returning from player.
    val episodeFlow = remember(series?.sourceId, series?.seriesId) {
        series?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes
    }
    val episodes by episodeFlow.collectAsState(initial = emptyList())
    val activeProfileId by preferences.activeProfileId.collectAsState()
    // Join only this series' local episode rows with the active profile's
    // already-saved lengths. No provider requests, full-history loads or media
    // probes; the list updates when returning from playback.
    val playbackLengthFlow = remember(series?.sourceId, series?.seriesId, activeProfileId) {
        series?.let { viewModel.episodePlaybackLengths(it, activeProfileId) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<app.sokyiptv.data.db.EpisodePlaybackLength>())
    }
    val knownPlaybackLengths by playbackLengthFlow.collectAsState(initial = emptyList())
    val savedRuntimeByEpisode = remember(knownPlaybackLengths) {
        knownPlaybackLengths.associate { it.episodeId to it.durationMs }
    }
    val cachedRuntimeRevision by EpisodeRuntimeHints.revision.collectAsState()
    val runtimeContext = LocalContext.current
    var durationScanStatus by remember(seriesId) { mutableStateOf("") }

    LaunchedEffect(seriesId) {
        series = viewModel.seriesDetail(seriesId)
        series?.let { viewModel.loadEpisodes(it) }
    }
    val s = series ?: run {
        Box(Modifier.fillMaxSize().background(Color(0xFF070B21)),
            contentAlignment = Alignment.Center) { Text("Loading series...", color = Color.White) }
        return
    }
    val bySeason = remember(episodes) {
        episodes.groupBy { it.season }.mapValues { (_, eps) -> eps.sortedBy { it.episodeNumber } }.toSortedMap()
    }
    val activeSeason = selectedSeason.takeIf { it in bySeason.keys } ?: bySeason.keys.firstOrNull() ?: 1
    // Runs only while this UI is foreground-resumed. Cancels in-flight HTTP
    // metadata reads on close/background, and skips all reads during playback.
    // Episode rows update reactively as verified lengths arrive in Room.
    LaunchedEffect(series?.id, activeSeason, lifecycleOwner) {
        val selected = series ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val loaded = snapshotFlow { episodes }.first { it.isNotEmpty() }
            viewModel.fillUnknownEpisodeDurations(
                selected, loaded, attemptedEpisodeDurations, activeSeason,
                onStatus = { durationScanStatus = it },
            )
        }
    }
    val currentEpisodes = bySeason[activeSeason].orEmpty()
    val firstEpisode = currentEpisodes.firstOrNull()
    val playEpisode = resumeEpisode?.takeIf { it.season == activeSeason && currentEpisodes.any { e -> e.id == it.id } }
        ?: firstEpisode
    LaunchedEffect(seriesId, series?.id, episodes, foregroundEpoch) {
        val currentSeries = series ?: return@LaunchedEffect
        if (episodes.isEmpty()) return@LaunchedEffect
        val saved = viewModel.latestResumeEpisode(currentSeries)
            ?.takeIf { ep -> episodes.any { it.id == ep.id } }
        resumeEpisode = saved
        resumeProgress = saved?.let { viewModel.resumePosition("ep:${it.id}") }
        if (selectedSeason == -1 && saved != null) selectedSeason = saved.season
        if (saved != null) lastPlayedEpisode = saved.id
    }
    // The primary Play/Resume button is disabled until the episodes are loaded.
    // Focus it only once when enabled, without stealing focus again on season changes.
    LaunchedEffect(seriesId, playEpisode?.id) {
        if (!initialActionFocused && playEpisode != null) {
            repeat(8) {
                kotlinx.coroutines.delay(85L)
                if (runCatching { playFocus.requestFocus() }.isSuccess) {
                    initialActionFocused = true
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF080C24))) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 19.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("SOKY IPTV PRO", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 17.sp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(s.displayTitle, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                viewModel.toggleSeriesFavourite(s)
                series = s.copy(favourite = !s.favourite)
            }) {
                Icon(if (s.favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favourite", tint = Color.White)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AsyncImage(
                model = s.posterUrl, contentDescription = s.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(114.dp).height(165.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailAttribute("Directed By:", "N/A")
                DetailAttribute("Release Date:", s.year?.toString() ?: "N/A")
                DetailAttribute("Genre:", s.genre ?: "N/A")
                Row {
                    Text("Plot:", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(130.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.plot?.takeIf { it.isNotBlank() } ?: "No synopsis supplied",
                            maxLines = if (expandedPlot) Int.MAX_VALUE else 2,
                            color = Color.White, overflow = TextOverflow.Ellipsis)
                        if (!s.plot.isNullOrBlank() && s.plot!!.length > 100) {
                            Text(if (expandedPlot) "Read less" else "Read more",
                                color = Color(0xFFFFA449),
                                modifier = Modifier.clickable { expandedPlot = !expandedPlot })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { playEpisode?.let { ep ->
                            lastPlayedEpisode = ep.id
                            onPlayEpisode("ep:${ep.id}", ep.streamUrl,
                                "S${ep.season}E${ep.episodeNumber} - ${ep.title}")
                        } },
                        enabled = playEpisode != null,
                        modifier = Modifier.focusRequester(playFocus)
                            .onFocusChanged { playFocused = it.isFocused }
                            .border(if (playFocused) 2.dp else 0.dp,
                                Color(0xFF6FE5F5), RoundedCornerShape(6.dp)),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        val isResuming = playEpisode?.id == resumeEpisode?.id && resumeEpisode != null
                        val pos = resumeProgress?.takeIf { isResuming }
                        val progress = if (pos != null && pos.durationMillis > 0L)
                            ((pos.positionMillis * 100L / pos.durationMillis).coerceIn(0L, 100L)) else null
                        val resumeLabel = if (isResuming) "Resume" else "Play"
                        Text(buildString {
                            append(resumeLabel)
                            append(" - S${playEpisode?.season ?: activeSeason}:E${playEpisode?.episodeNumber ?: 1}")
                            if (pos != null) {
                                append(" · ${pos.positionMillis / 60_000L}m")
                                if (progress != null) append(" (${progress}%)")
                            }
                        }, maxLines = 1)
                    }
                    Box {
                        Button(
                            onClick = { seasonMenuOpen = true }, enabled = bySeason.isNotEmpty(),
                            modifier = Modifier.onFocusChanged { seasonFocused = it.isFocused }
                                .border(if (seasonFocused) 2.dp else 0.dp,
                                    Color(0xFF6FE5F5), RoundedCornerShape(6.dp)),
                        ) {
                            Text("Season - $activeSeason")
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = seasonMenuOpen, onDismissRequest = { seasonMenuOpen = false }) {
                            bySeason.keys.forEach { number ->
                                DropdownMenuItem(text = { Text("Season $number") }, onClick = {
                                    selectedSeason = number; seasonMenuOpen = false
                                })
                            }
                        }
                    }
                    TextButton(onClick = {
                        preferences.setSeriesAutoplay(!autoNext)
                    }) { Text(if (autoNext) "Autoplay: On" else "Autoplay: Off") }
                    TextButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Text("Reset Progress")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 25.dp)) {
            TvDetailTab("Episodes (${episodes.size})", !showCast) { showCast = false }
            TvDetailTab("Cast", showCast) { showCast = true }
            if (!showCast && durationScanStatus.isNotBlank()) {
                Text(
                    durationScanStatus,
                    modifier = Modifier.padding(start = 16.dp, top = 5.dp),
                    color = Color(0xFFA8D9E6), fontSize = 11.sp, maxLines = 1,
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            if (showCast) {
                val names = s.cast.orEmpty().split(',', '|').map { it.trim() }.filter { it.isNotBlank() }
                // Keep provider-only names alongside TMDB photos; some feeds credit leads
                // whom TMDB omits, or supply alternative/transliterated actor names.
                val cast = (portraits + names
                    .filter { name -> portraits.none { it.name.equals(name, ignoreCase = true) } }
                    .map { app.sokyiptv.data.remote.TmdbCastPortrait(it, null) })
                    .distinctBy { it.name.trim().lowercase() }
                if (cast.isEmpty()) Text(
                    "Cast not provided. Optional photos use your TMDB API key in Settings.",
                    color = Color.LightGray, modifier = Modifier.padding(12.dp),
                ) else LazyVerticalGrid(
                    columns = GridCells.Adaptive(115.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    gridItems(cast, key = { it.name }) { actor ->
                        var actorFocused by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .onFocusChanged { actorFocused = it.isFocused }
                                .border(if (actorFocused) 2.dp else 0.dp, Color(0xFF79ECF7),
                                    RoundedCornerShape(5.dp))
                                .tvRemoteActions({ onOpenPerson(actor.name) }, { onOpenPerson(actor.name) })
                                .padding(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.width(95.dp).height(128.dp)
                                .background(Color(0xFF222A42), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center) {
                                if (actor.imageUrl != null) {
                                    AsyncImage(actor.imageUrl, contentDescription = actor.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop)
                                } else {
                                    Text(actor.name.take(1), color = Color.White, fontSize = 36.sp)
                                }
                            }
                            Text(actor.name, color = Color.White, fontSize = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                if (currentEpisodes.isEmpty()) {
                    Text("Loading episodes, or none supplied by the provider", color = Color.LightGray,
                        modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(
                        state = episodeListState,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(currentEpisodes, key = { it.id }) { ep ->
                            EpisodePreviewRow(
                                ep, s.posterUrl,
                                savedRuntimeByEpisode[ep.id]
                                    ?: EpisodeRuntimeHints.lookupMillis(runtimeContext, ep).also {
                                        // Observe revision so newly scanned or retained
                                        // duration hints update this row reactively.
                                        cachedRuntimeRevision
                                    },
                                Modifier,
                                onPlay = {
                                    lastPlayedEpisode = ep.id
                                    onPlayEpisode("ep:${ep.id}", ep.streamUrl,
                                        "S${ep.season}E${ep.episodeNumber} - ${ep.title}")
                                },
                                onReset = { viewModel.resetEpisodeProgress(ep.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset this series?") },
            text = { Text("Clear watching progress for every episode of ${s.displayTitle} in this profile?") },
            confirmButton = { TextButton(onClick = {
                viewModel.resetSeriesProgress(s)
                lastPlayedEpisode = 0L
                resumeEpisode = null
                resumeProgress = null
                showResetConfirm = false
            }) { Text("Reset Series") } },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailAttribute(label: String, value: String) {
    Row {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(130.dp))
        Text(value, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvDetailTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.width(182.dp).height(47.dp)
            .background(if (selected) Color(0xFF202641) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick), contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 17.sp, color = Color.White)
            if (selected || focused) Box(
                Modifier.fillMaxWidth().height(if (focused) 3.dp else 2.dp)
                    .background(if (focused) Color(0xFF7AE4F0) else Color(0xFFDB8AD2))
            )
        }
    }
}

@Composable
private fun EpisodePreviewRow(
    episode: Episode,
    fallbackArt: String?,
    observedDurationMs: Long?,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onReset: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastPlayAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color(0xFF18243E) else Color(0xFF080C24))
                .tvRemoteActions(
                    onClick = {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPlayAt >= 650L) {
                            lastPlayAt = now
                            onPlay()
                        }
                    },
                    onLongPress = { menuOpen = true },
                )
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(140.dp).height(79.dp)) {
                AsyncImage(
                    model = episode.stillUrl ?: fallbackArt,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                    tint = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            Column(Modifier.weight(1f)) {
                Text("S%02dE%02d - %s".format(episode.season, episode.episodeNumber, episode.title),
                    color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(EpisodeRuntimeLabel.fromSources(
                    episode.durationSeconds, observedDurationMs,
                ), color = Color.LightGray)
            }
            if (focused) IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Episode options",
                    tint = Color(0xFF89E6F5))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Play episode") }, onClick = {
                menuOpen = false; onPlay()
            })
            DropdownMenuItem(text = { Text("Reset episode progress") }, onClick = {
                menuOpen = false; onReset()
            })
        }
    }
}
