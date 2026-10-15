/*
 * Koky & Sosy Player TV VOD catalogue.
 * SQL pages + lazy image views, original branding, reference-proportion sidebar.
 * GPL-3.0-or-later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Category
import app.opentv.data.parser.displayTitle
import app.opentv.ui.VodViewModel
import app.opentv.ui.onboarding.TvNativeTextField
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

private val background = Color(0xFF080D20)
private val sideBackground = Color(0xFF030408)
private val teal = Color(0xFF006779)
private const val PAGE = 72

/** No full-library observations: SQL selects only the visible category/search and a few pages. */
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
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val catalog = remember(context) { ServiceLocator.get(context).catalogRepository }
    val categoriesState by (if (isSeries) viewModel.seriesCategories else viewModel.movieCategories).collectAsState()
    val recentlyMovies by viewModel.recentlyAddedMovies.collectAsState()
    val recentlySeries by viewModel.recentlyAddedSeries.collectAsState()
    val recentResumes by viewModel.continueWatching.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val sourceId by viewModel.selectedVodSource.collectAsState()

    var selection by rememberSaveable(isSeries) { mutableStateOf("all") }
    var categoryQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    var titleQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    fun chooseCategory(id: String) {
        if (selection != id) {
            categoryQuery = ""
            titleQuery = ""
            selection = id
        }
    }
    var sourceMenu by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<VodViewModel.ResumeItem?>(null) }
    var optionsTarget by remember { mutableStateOf<VodViewModel.ResumeItem?>(null) }
    var moviePage by remember(isSeries) { mutableStateOf<List<Movie>>(emptyList()) }
    var seriesPage by remember(isSeries) { mutableStateOf<List<Series>>(emptyList()) }
    var categoryCounts by remember(isSeries, sourceId) { mutableStateOf<Map<String?, Int>>(emptyMap()) }
    var favouriteCount by remember(isSeries, sourceId) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scopedResume = remember(recentResumes, sourceId, isSeries) {
        recentResumes.filter {
            (sourceId == null || it.sourceId == sourceId) &&
                (if (isSeries) it.seriesProviderId != null else it.seriesProviderId == null)
        }
    }
    val categories = remember(categoriesState, categoryQuery) {
        categoriesState.filter { it.name.contains(categoryQuery.trim(), ignoreCase = true) }
    }
    val totalCount = categoryCounts.values.sum()
    val recentCount = if (isSeries) recentlySeries.size else recentlyMovies.size
    val matchCount = if (selection == "continue") scopedResume.size else
        if (selection == "recent") recentCount else
        if (selection == "favourites") favouriteCount else
        if (selection == "all") totalCount else categoryCounts[selection] ?: 0

    LaunchedEffect(sourceId, isSeries) {
        categoryCounts = catalog.vodCounts(sourceId, isSeries).associate { it.categoryId to it.count }
        favouriteCount = catalog.vodFavouriteCount(sourceId, isSeries)
    }

    // The query runs on the Room dispatcher. A keystroke cancels the old query,
    // waits briefly, then starts at page one. No full-table list is copied in Compose.
    LaunchedEffect(isSeries, sourceId, selection, titleQuery) {
        moviePage = emptyList()
        seriesPage = emptyList()
        reachedEnd = false
        loading = false
        grid.scrollToItem(0)
        if (selection == "continue") return@LaunchedEffect
        if (titleQuery.isNotBlank()) delay(180L)
        val query = titleQuery.trim()
        val category = selection.takeUnless { it in setOf("all", "favourites", "recent") }
        val onlyFavourites = selection == "favourites"
        var offset = 0
        suspend fun fetchPage() {
            if (reachedEnd || loading) return
            loading = true
            try {
                val size = if (selection == "recent") 30 else PAGE
                if (isSeries) {
                    val batch = catalog.seriesPage(sourceId, category, onlyFavourites, query, size, offset)
                    seriesPage = seriesPage + batch
                    offset += batch.size
                    reachedEnd = batch.size < size || selection == "recent"
                } else {
                    val batch = catalog.moviePage(sourceId, category, onlyFavourites, query, size, offset)
                    moviePage = moviePage + batch
                    offset += batch.size
                    reachedEnd = batch.size < size || selection == "recent"
                }
            } finally {
                loading = false
            }
        }
        fetchPage()
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                val count = if (isSeries) seriesPage.size else moviePage.size
                if (!reachedEnd && !loading && count > 0 && last >= count - 15) {
                    fetchPage()
                }
            }
    }

    Row(Modifier.fillMaxSize().background(background)) {
        Column(
            Modifier.weight(0.94f).fillMaxSize().background(sideBackground)
                .padding(start = 18.dp, end = 9.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("KOKY & SOSY", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(19.dp))
            TvNativeTextField(categoryQuery, { categoryQuery = it }, "Search in categories",
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (sources.size > 1) {
                Box {
                    CategoryOption(
                        "Provider: " + (sources.firstOrNull { it.id == sourceId }?.name ?: "All"),
                        "", false,
                    ) { sourceMenu = true }
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
                item { CategoryOption("ALL", totalCount.toString(), selection == "all") { chooseCategory("all") } }
                item { CategoryOption("FAVOURITES", favouriteCount.toString(), selection == "favourites") {
                    chooseCategory("favourites")
                } }
                item { CategoryOption("CONTINUE WATCHING", scopedResume.size.toString(), selection == "continue") {
                    chooseCategory("continue")
                } }
                item { CategoryOption("RECENTLY ADDED", recentCount.toString(), selection == "recent") {
                    chooseCategory("recent")
                } }
                items(categories, key = { "cat:${it.sourceId}:${it.id}" }) { category ->
                    CategoryOption(category.name, (categoryCounts[category.id] ?: 0).toString(),
                        selection == category.id) { chooseCategory(category.id) }
                }
            }
        }
        Column(Modifier.weight(2.8f).fillMaxSize().padding(start = 13.dp, end = 13.dp, top = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                TvNativeTextField(titleQuery, { titleQuery = it },
                    if (isSeries) "Search Series" else "Search Movies",
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Master Search", tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            val heading = when (selection) {
                "all" -> "ALL"
                "continue" -> "CONTINUE WATCHING"
                "favourites" -> "FAVOURITES"
                "recent" -> "RECENTLY ADDED"
                else -> categoriesState.firstOrNull { it.id == selection }?.name ?: "ALL"
            }
            Text(heading, color = Color.White.copy(alpha = .91f), fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            if (selection == "continue") {
                if (scopedResume.isEmpty()) {
                    Text("Nothing to continue yet", color = Color.LightGray)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        gridItems(scopedResume.filter {
                            titleQuery.isBlank() || it.title.contains(titleQuery.trim(), ignoreCase = true)
                        }, key = { it.logicalKey }) { item ->
                            ResumePoster(item,
                                onPlay = { onResume(item.mediaKey, item.streamUrl, item.title) },
                                onReset = { resetTarget = item })
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (isSeries) gridItems(seriesPage, key = { "s:${it.id}" }) { item ->
                        TvPoster(item.displayTitle, item.posterUrl) { onOpenSeries(item) }
                    }
                    else gridItems(moviePage, key = { "m:${it.id}" }) { item ->
                        TvPoster(item.displayTitle, item.posterUrl) { onOpenMovie(item) }
                    }
                    if (loading) item(key = "loading") {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            if (!loading && selection != "continue" && (if (isSeries) seriesPage.isEmpty() else moviePage.isEmpty())) {
                Text(if (titleQuery.isNotBlank()) "No matching titles" else "No titles in this category",
                    color = Color.LightGray)
            }
        }
    }
    resetTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("Reset watching progress?") },
            text = { Text("Reset only ${item.title} for this profile?") },
            confirmButton = { TextButton(onClick = {
                viewModel.resetResumeItem(item); resetTarget = null
            }) { Text("Reset title") } },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CategoryOption(label: String, count: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var lastClick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val selectedBackground = if (selected) teal else if (focused) Color(0xFF18243C) else sideBackground
    Row(
        Modifier.fillMaxWidth()
            .background(selectedBackground)
            .onFocusChanged { focused = it.isFocused }
            .clickable {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastClick >= 180L) { lastClick = now; onClick() }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(48.dp)
                .background(if (focused) Color(0xFF69E0EF) else if (selected) Color.White.copy(alpha=.55f) else Color.Transparent),
        )
        Row(Modifier.weight(1f).padding(start = 13.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (focused || selected) Color.White else Color(0xFFDEDFE7),
                fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(count, color = Color(0xFFC5D4DF), fontSize = 14.sp)
        }
    }
}


@Composable
private fun TvPoster(title: String, poster: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var lastPosterClick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(.66f)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, Color(0xFF7DE4F0), RoundedCornerShape(3.dp))
            .clickable(onClick = {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPosterClick >= 500L) {
                    lastPosterClick = now
                    onClick()
                }
            })
            .background(Color(0xFF151B2B)),
    ) {
        AsyncImage(model = poster, contentDescription = title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (focused || poster == null) {
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.85f))))
                    .padding(6.dp),
            ) {
                Text(title, color = Color.White, fontSize = 13.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumePoster(
    item: VodViewModel.ResumeItem,
    onPlay: () -> Unit,
    onReset: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, Color(0xFF7DE4F0), RoundedCornerShape(3.dp))
            .combinedClickable(onClick = onPlay, onLongClick = onReset),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.66f)) {
            AsyncImage(item.posterUrl, contentDescription = item.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text("▶", color = Color.White, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
        }
        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.DarkGray)) {
            Box(Modifier.fillMaxWidth(item.progress).height(4.dp).background(teal))
        }
    }
}
