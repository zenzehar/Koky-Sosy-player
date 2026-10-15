/*
 * Koky & Sosy Player: screenshot-guided TV series detail screen.
 * GPL-3.0-or-later. Original branding and native D-pad navigation.
 */
package app.opentv.ui.vod

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
internal fun TvSeriesDetailLayout(
    seriesId: Long,
    onPlayEpisode: (String, String, String) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel,
) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    var expandedPlot by rememberSaveable(seriesId) { mutableStateOf(false) }
    var selectedSeason by rememberSaveable(seriesId) { mutableStateOf(-1) }
    var showCast by rememberSaveable(seriesId) { mutableStateOf(false) }
    var lastPlayedEpisode by rememberSaveable(seriesId) { mutableStateOf(0L) }
    var seasonMenuOpen by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val playedFocus = remember { FocusRequester() }
    val episodeListState = rememberLazyListState()
    val episodes by (series?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes)
        .collectAsState(initial = emptyList())

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
    val currentEpisodes = bySeason[activeSeason].orEmpty()
    val firstEpisode = currentEpisodes.firstOrNull()
    LaunchedEffect(episodes, activeSeason, lastPlayedEpisode, showCast) {
        if (showCast) return@LaunchedEffect
        val index = currentEpisodes.indexOfFirst { it.id == lastPlayedEpisode }
        if (index >= 0) {
            runCatching { episodeListState.scrollToItem(index) }
            withFrameNanos { }
            runCatching { playedFocus.requestFocus() }
        } else if (episodes.isNotEmpty()) {
            withFrameNanos { }
            runCatching { playFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF080C24))) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("KOKY & SOSY PLAYER", color = Color.White, fontWeight = FontWeight.Bold,
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            AsyncImage(
                model = s.posterUrl, contentDescription = s.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(145.dp).height(220.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { firstEpisode?.let { ep ->
                            lastPlayedEpisode = ep.id
                            onPlayEpisode("ep:${ep.id}", ep.streamUrl,
                                "S${ep.season}E${ep.episodeNumber} - ${ep.title}")
                        } },
                        enabled = firstEpisode != null,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { Icon(Icons.Default.PlayArrow, null); Text("Play - S$activeSeason:E${firstEpisode?.episodeNumber ?: 1}") }
                    Box {
                        Button(onClick = { seasonMenuOpen = true }, enabled = bySeason.isNotEmpty()) {
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
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            if (showCast) {
                val people = s.cast.orEmpty().split(',', '|').map { it.trim() }.filter { it.isNotBlank() }
                LazyColumn {
                    if (people.isEmpty()) item { Text("Cast information unavailable", color = Color.LightGray) }
                    items(people) { name -> Text(name, color = Color.White, fontSize = 19.sp,
                        modifier = Modifier.padding(14.dp)) }
                }
            } else {
                if (currentEpisodes.isEmpty()) {
                    Text("Loading episodes, or none supplied by the provider", color = Color.LightGray,
                        modifier = Modifier.padding(20.dp))
                } else {
                    LazyColumn(
                        state = episodeListState,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(currentEpisodes, key = { it.id }) { ep ->
                            EpisodePreviewRow(
                                ep, s.posterUrl,
                                if (lastPlayedEpisode == ep.id) Modifier.focusRequester(playedFocus) else Modifier,
                            ) {
                                lastPlayedEpisode = ep.id
                                onPlayEpisode("ep:${ep.id}", ep.streamUrl,
                                    "S${ep.season}E${ep.episodeNumber} - ${ep.title}")
                            }
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
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF18243E) else Color(0xFF080C24))
            .clickable(onClick = onPlay)
            .padding(horizontal = 5.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.width(196.dp).height(110.dp)) {
            AsyncImage(
                model = episode.stillUrl ?: fallbackArt,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Icon(Icons.Default.PlayArrow, contentDescription = "Play",
                tint = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        Column {
            Text("S%02dE%02d - %s".format(episode.season, episode.episodeNumber, episode.title),
                color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text(episode.durationSeconds?.takeIf { it > 0 }?.let { "${it / 60}m" } ?: "Duration unavailable",
                color = Color.LightGray)
        }
    }
}
