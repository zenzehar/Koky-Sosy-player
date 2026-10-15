/*
 * Koky & Sosy Player: television VOD catalogue browser.
 * GPL-3.0-or-later. Inspired by the user's layout reference, using original branding.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Category
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import app.opentv.ui.onboarding.TvNativeTextField
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

private val tvBg = Color(0xFF080D25)
private val sideBg = Color(0xFF050509)
private val selectedTeal = Color(0xFF006A80)

@Composable
internal fun TvVodBrowser(
    isSeries: Boolean,
    viewModel: VodViewModel,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onResume: (String, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
) {
    val movies by viewModel.movies.collectAsState()
    val series by viewModel.series.collectAsState()
    val movieCategories by viewModel.movieCategories.collectAsState()
    val seriesCategories by viewModel.seriesCategories.collectAsState()
    val recentlyMovies by viewModel.recentlyAddedMovies.collectAsState()
    val recentlySeries by viewModel.recentlyAddedSeries.collectAsState()
    val recentResumes by viewModel.continueWatching.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val sourceId by viewModel.selectedVodSource.collectAsState()

    // Exactly one Room observation per type, and the grid itself is virtualised.
    LaunchedEffect(Unit) {
        viewModel.selectMovieCategory(null)
        viewModel.selectSeriesCategory(null)
        viewModel.loadHomeFeeds()
    }
    var selection by rememberSaveable(isSeries) { mutableStateOf("all") }
    var categoryQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    var titleQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<VodViewModel.ResumeItem?>(null) }

    val rawCategories = if (isSeries) seriesCategories else movieCategories
    val scopedMovies = remember(movies, sourceId) { movies.filter { sourceId == null || it.sourceId == sourceId } }
    val scopedSeries = remember(series, sourceId) { series.filter { sourceId == null || it.sourceId == sourceId } }
    val scopedResume = remember(recentResumes, sourceId, isSeries) {
        recentResumes.filter { (sourceId == null || it.sourceId == sourceId) &&
            (if (isSeries) it.seriesProviderId != null else it.seriesProviderId == null) }
    }
    val categories = remember(rawCategories, categoryQuery) {
        rawCategories.filter { it.name.contains(categoryQuery.trim(), ignoreCase = true) }
    }
    val visibleMovies = remember(scopedMovies, selection, titleQuery, recentlyMovies) {
        val base = when (selection) {
            "favourites" -> scopedMovies.filter { it.favourite }
            "recent" -> scopedMovies.filter { m -> recentlyMovies.any { it.id == m.id } }
            "all", "continue" -> scopedMovies
            else -> scopedMovies.filter { it.categoryId == selection }
        }
        base.filter { titleQuery.isBlank() || it.displayTitle.contains(titleQuery.trim(), ignoreCase = true) }
    }
    val visibleSeries = remember(scopedSeries, selection, titleQuery, recentlySeries) {
        val base = when (selection) {
            "favourites" -> scopedSeries.filter { it.favourite }
            "recent" -> scopedSeries.filter { s -> recentlySeries.any { it.id == s.id } }
            "all", "continue" -> scopedSeries
            else -> scopedSeries.filter { it.categoryId == selection }
        }
        base.filter { titleQuery.isBlank() || it.displayTitle.contains(titleQuery.trim(), ignoreCase = true) }
    }

    Row(Modifier.fillMaxSize().background(tvBg)) {
        Column(
            Modifier.weight(0.96f).fillMaxSize().background(sideBg).padding(16.dp),
        ) {
            Text("‹   KOKY & SOSY PLAYER", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 20.sp, maxLines = 1)
            Spacer(Modifier.height(20.dp))
            TvNativeTextField(categoryQuery, { categoryQuery = it }, "Search in categories",
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (sources.size > 1) {
                Box {
                    SidebarItem("Provider: " + (sources.firstOrNull { it.id == sourceId }?.name ?: "All"),
                        "", false) { sourceMenu = true }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        DropdownMenuItem(text = { Text("All providers") }, onClick = {
                            viewModel.selectVodSource(null); sourceMenu = false
                        })
                        sources.forEach { src ->
                            DropdownMenuItem(text = { Text(src.name) }, onClick = {
                                viewModel.selectVodSource(src.id); sourceMenu = false
                            })
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                item { SidebarItem("ALL", (if (isSeries) scopedSeries.size else scopedMovies.size).toString(),
                    selection == "all") { selection = "all" } }
                item { SidebarItem("FAVOURITES",
                    (if (isSeries) scopedSeries.count { it.favourite } else scopedMovies.count { it.favourite }).toString(),
                    selection == "favourites") { selection = "favourites" } }
                item { SidebarItem("CONTINUE WATCHING", scopedResume.size.toString(),
                    selection == "continue") { selection = "continue" } }
                item { SidebarItem("RECENTLY ADDED",
                    (if (isSeries) recentlySeries.size else recentlyMovies.size).toString(),
                    selection == "recent") { selection = "recent" } }
                items(categories, key = { it.id }) { cat ->
                    val count = if (isSeries) scopedSeries.count { it.categoryId == cat.id }
                        else scopedMovies.count { it.categoryId == cat.id }
                    SidebarItem(cat.name, count.toString(), selection == cat.id) { selection = cat.id }
                }
            }
        }
        Column(Modifier.weight(2.14f).fillMaxSize().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    TvNativeTextField(titleQuery, { titleQuery = it },
                        if (isSeries) "Search Series" else "Search Movies",
                        modifier = Modifier.fillMaxWidth())
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Master Search", tint = Color.White)
                }
            }
            Spacer(Modifier.height(9.dp))
            val heading = when (selection) {
                "all" -> "ALL"
                "continue" -> "CONTINUE WATCHING"
                "favourites" -> "FAVOURITES"
                "recent" -> "RECENTLY ADDED"
                else -> rawCategories.firstOrNull { it.id == selection }?.name ?: "ALL"
            }
            Text(heading, color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            if (selection == "continue") {
                if (scopedResume.isEmpty()) {
                    Text("Nothing to continue yet", color = Color.LightGray)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(145.dp),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        gridItems(scopedResume.filter {
                            titleQuery.isBlank() || it.title.contains(titleQuery.trim(), ignoreCase = true)
                        }, key = { it.logicalKey }) { item ->
                            TvResumePoster(item,
                                onPlay = { onResume(item.mediaKey, item.streamUrl, item.title) },
                                onReset = { resetTarget = item })
                        }
                    }
                }
            } else if (isSeries) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    gridItems(visibleSeries, key = { it.id }) { item ->
                        PosterCard(
                            title = item.displayTitle, posterUrl = item.posterUrl,
                            subtitle = item.year?.toString(), rating = item.rating,
                            onClick = { onOpenSeries(item) },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(145.dp),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    gridItems(visibleMovies, key = { it.id }) { item ->
                        PosterCard(
                            title = item.displayTitle, posterUrl = item.posterUrl,
                            subtitle = item.year?.toString(), rating = item.rating,
                            onClick = { onOpenMovie(item) },
                        )
                    }
                }
            }
        }
    }
    resetTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("Reset watching progress?") },
            text = { Text("Reset only ${item.title} for the current profile?") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetResumeItem(item); resetTarget = null }) { Text("Reset title") }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SidebarItem(label: String, count: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) selectedTeal else if (focused) Color(0xFF22294A) else sideBg)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, Color.White)
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count, color = Color.White, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvResumePoster(
    item: VodViewModel.ResumeItem,
    onPlay: () -> Unit,
    onReset: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(7.dp))
            .combinedClickable(onClick = onPlay, onLongClick = onReset),
    ) {
        Box(Modifier.fillMaxWidth().height(175.dp)) {
            AsyncImage(item.posterUrl, contentDescription = item.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("▶", color = Color.White, fontSize = 29.sp,
                modifier = Modifier.align(Alignment.Center))
        }
        Text(item.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.DarkGray)) {
            Box(Modifier.fillMaxWidth(item.progress).height(4.dp).background(selectedTeal))
        }
    }
}
