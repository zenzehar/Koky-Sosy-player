/*
 * Soky IPTV Pro TV VOD catalogue.
 * SQL pages + lazy image views, original branding, reference-proportion sidebar.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.ui.vod

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.work.WorkManager
import androidx.work.WorkInfo
import app.sokyiptv.core.AppSettings
import app.sokyiptv.core.BrowseCustomizations
import app.sokyiptv.core.BrowseSort
import app.sokyiptv.ui.BrowseSortMenu
import app.sokyiptv.data.work.VodSectionSyncWorker
import app.sokyiptv.data.model.SourceKind
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
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
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.data.model.Movie
import app.sokyiptv.data.model.Series
import app.sokyiptv.data.model.Category
import app.sokyiptv.data.parser.displayTitle
import app.sokyiptv.data.parser.ArabicSearch
import app.sokyiptv.ui.VodViewModel
import app.sokyiptv.ui.isHandheldPortrait
import app.sokyiptv.ui.isHandheldTouch
import app.sokyiptv.ui.onboarding.TvNativeTextField
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

private val background = Color(0xFF080D20)
private val sideBackground = Color(0xFF030408)
private val teal = Color(0xFF006779)
private const val PAGE = 72
// Five cards per row; the built-in LazyVerticalGrid focus scroll owns the viewport.
// Never manually scroll from a poster focus callback: that competes with Compose.
internal const val VOD_COLUMNS = 5

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val catalog = remember(context) { ServiceLocator.get(context).catalogRepository }
    val settings = remember(context) { AppSettings.get(context) }
    val lastUpdateRevision by settings.vodSectionRevision.collectAsState()
    val categoriesState by (if (isSeries) viewModel.seriesCategories else viewModel.movieCategories).collectAsState()
    val recentlyMovies by viewModel.recentlyAddedMovies.collectAsState()
    val recentlySeries by viewModel.recentlyAddedSeries.collectAsState()
    val recentResumes by viewModel.continueWatching.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val sourceId by viewModel.selectedVodSource.collectAsState()

    val browse = remember(context) { BrowseCustomizations(context) }
    val browseRevision by browse.revision.collectAsState()
    var favouriteMutationRevision by remember { mutableStateOf(0) }
    val kind = if (isSeries) "series" else "movie"
    var selection by rememberSaveable(isSeries) { mutableStateOf("all") }
    var categoryQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    var titleQuery by rememberSaveable(isSeries) { mutableStateOf("") }
    val isFavourites = selection == "favourites"
    val sortMode = browse.sort(kind, isFavourites)
    // Full list only used inside CUSTOM favourites; ordinary browsing stays SQL-paged.
    var customFavouriteKeys by remember(isSeries, sourceId) { mutableStateOf<List<String>>(emptyList()) }
    fun movieKey(item: Movie): String = item.sourceId.toString() + ":" + item.streamId
    fun seriesKey(item: Series): String = item.sourceId.toString() + ":" + item.seriesId
    var grabbedKey by remember { mutableStateOf<String?>(null) }
    fun finishReorder() {
        if (grabbedKey != null) browse.saveFavouriteOrder(kind, customFavouriteKeys)
        grabbedKey = null
    }
    BackHandler(grabbedKey != null) { finishReorder() }


    fun toggleMovie(item: Movie) {
        scope.launch {
            catalog.setMovieFavourite(item.id, !item.favourite)
            favouriteMutationRevision++
        }
    }
    fun toggleSeries(item: Series) {
        scope.launch {
            catalog.setSeriesFavourite(item.id, !item.favourite)
            favouriteMutationRevision++
        }
    }
    fun chooseCategory(id: String) {
        if (selection != id) {
            categoryQuery = ""
            titleQuery = ""
            selection = id
        }
    }
    var sourceMenu by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<VodViewModel.ResumeItem?>(null) }
    // Append to observable lists in place: List + batch copied the entire growing
    // catalogue on EVERY page, causing O(n²) allocation on large IPTV libraries.
    val moviePage = remember(isSeries) { mutableStateListOf<Movie>() }
    val seriesPage = remember(isSeries) { mutableStateListOf<Series>() }
    /** SnapshotStateList swaps are instantaneous; no DB fetches or disk writes on D-pad. */
    fun moveFavourite(key: String, delta: Int) {
        if (!isFavourites || sortMode != BrowseSort.CUSTOM || grabbedKey != key) return
        val from = customFavouriteKeys.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in customFavouriteKeys.indices) return
        customFavouriteKeys = customFavouriteKeys.toMutableList().apply {
            java.util.Collections.swap(this, from, to)
        }
        if (isSeries) {
            val a = seriesPage.indexOfFirst { seriesKey(it) == key }
            val b = seriesPage.indexOfFirst { seriesKey(it) == customFavouriteKeys[from] }
            if (a >= 0 && b >= 0) java.util.Collections.swap(seriesPage, a, b)
        } else {
            val a = moviePage.indexOfFirst { movieKey(it) == key }
            val b = moviePage.indexOfFirst { movieKey(it) == customFavouriteKeys[from] }
            if (a >= 0 && b >= 0) java.util.Collections.swap(moviePage, a, b)
        }
    }

    var categoryCounts by remember(isSeries, sourceId) { mutableStateOf<Map<String?, Int>>(emptyMap()) }
    var favouriteCount by remember(isSeries, sourceId) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    // A focus move must not invalidate/recompose the entire six-column grid.
    // The only Compose state updated per keypress lives inside the focused poster.
    val seriesPrefetch = remember(isSeries) { FocusDwellPrefetch<Series> { it.id } }
    LaunchedEffect(seriesPrefetch, viewModel, isSeries) {
        if (isSeries) {
            // Preserve v88's 320ms focus-dwell policy and async episode loading.
            seriesPrefetch.collectAfterDwell(320L) { viewModel.loadEpisodes(it) }
        }
    }
    var reachedEnd by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scopedResume = remember(recentResumes, sourceId, isSeries) {
        recentResumes.filter {
            (sourceId == null || it.sourceId == sourceId) &&
                (if (isSeries) it.seriesProviderId != null else it.seriesProviderId == null)
        }
    }
    // One profile-scoped progress map for every category. No per-focus I/O.
    val resumeByKey = remember(recentResumes) { recentResumes.associateBy { it.logicalKey } }
    fun movieProgress(item: Movie): VodViewModel.ResumeItem? =
        resumeByKey["movie:" + item.sourceId + ":" +
            item.displayTitle.trim().lowercase() + ":" + (item.year ?: 0)]
    fun seriesProgress(item: Series): VodViewModel.ResumeItem? =
        resumeByKey["series:" + item.sourceId + ":" + item.seriesId]

    suspend fun resumeFavourite(item: VodViewModel.ResumeItem): Boolean? =
        if (item.seriesProviderId != null) {
            catalog.seriesByProviderId(item.sourceId, item.seriesProviderId)?.favourite
        } else {
            item.mediaKey.removePrefix("movie:").toLongOrNull()
                ?.let { catalog.movie(it)?.favourite }
        }

    suspend fun toggleResumeFavourite(item: VodViewModel.ResumeItem): Boolean? {
        val current = resumeFavourite(item) ?: return null
        if (item.seriesProviderId != null) {
            val series = catalog.seriesByProviderId(item.sourceId, item.seriesProviderId)
                ?: return null
            catalog.setSeriesFavourite(series.id, !current)
        } else {
            val movieId = item.mediaKey.removePrefix("movie:").toLongOrNull()
                ?: return null
            catalog.setMovieFavourite(movieId, !current)
        }
        favouriteMutationRevision++
        return !current
    }

    val categories = remember(categoriesState, categoryQuery) {
        categoriesState.filter {
            if (categoryQuery.isBlank()) true
            else if (ArabicSearch.hasArabic(categoryQuery))
                ArabicSearch.matches(it.name, categoryQuery)
            else it.name.contains(categoryQuery.trim(), ignoreCase = true)
        }
    }
    val totalCount = categoryCounts.values.sum()
    val recentCount = if (isSeries) recentlySeries.size else recentlyMovies.size
    val matchCount = if (selection == "continue") scopedResume.size else
        if (selection == "recent") recentCount else
        if (selection == "favourites") favouriteCount else
        if (selection == "all") totalCount else categoryCounts[selection] ?: 0

    LaunchedEffect(sourceId, isSeries, lastUpdateRevision, favouriteMutationRevision) {
        categoryCounts = catalog.vodCounts(sourceId, isSeries).associate { it.categoryId to it.count }
        favouriteCount = catalog.vodFavouriteCount(sourceId, isSeries)
    }

    // Room applies ORDER BY BEFORE paging, so sorted results are globally correct
    // across large libraries. Custom favourites alone use a background snapshot
    // of favourites (not the entire catalogue), then feed 72-card lazy pages.
    LaunchedEffect(selection, sourceId, sortMode) { if (grabbedKey != null) finishReorder() }
    var viewportKey by remember { mutableStateOf("") }
    LaunchedEffect(isSeries, sourceId, selection, titleQuery, lastUpdateRevision,
        sortMode, browseRevision, favouriteMutationRevision) {
        seriesPrefetch.reset()
        moviePage.clear()
        seriesPage.clear()
        customFavouriteKeys = emptyList()
        reachedEnd = false
        loading = false
        val newViewport = listOf(isSeries.toString(), sourceId.toString(),
            selection, titleQuery, sortMode.name).joinToString(":")
        if (newViewport != viewportKey) {
            grid.scrollToItem(0)
            viewportKey = newViewport
        }
        if (selection == "continue") return@LaunchedEffect
        if (titleQuery.isNotBlank()) delay(90L)
        val query = titleQuery.trim()
        val category = selection.takeUnless { it in setOf("all", "favourites", "recent") }
        val onlyFavourites = selection == "favourites"
        val custom = onlyFavourites && sortMode == BrowseSort.CUSTOM

        val customMovies = if (custom && !isSeries) {
            val all = browse.customSort(kind, catalog.movieFavourites(sourceId), ::movieKey)
            customFavouriteKeys = all.map(::movieKey)
            all.filter { item ->
                query.isEmpty() || if (ArabicSearch.hasArabic(query))
                    ArabicSearch.matches(item.name, query)
                else item.name.contains(query, ignoreCase = true)
            }
        } else null
        val customSeries = if (custom && isSeries) {
            val all = browse.customSort(kind, catalog.seriesFavourites(sourceId), ::seriesKey)
            customFavouriteKeys = all.map(::seriesKey)
            all.filter { item ->
                query.isEmpty() || if (ArabicSearch.hasArabic(query))
                    ArabicSearch.matches(item.name, query)
                else item.name.contains(query, ignoreCase = true)
            }
        } else null

        var offset = 0
        suspend fun fetchPage() {
            if (reachedEnd || loading) return
            loading = true
            try {
                val size = when {
                    customSeries != null -> customSeries.size
                    customMovies != null -> customMovies.size
                    selection == "recent" -> 30
                    else -> PAGE
                }.coerceAtLeast(1)
                if (isSeries) {
                    val batch = if (customSeries != null)
                        customSeries.drop(offset).take(size)
                    else catalog.seriesPage(sourceId, category, onlyFavourites, query,
                        size, offset, sortMode)
                    seriesPage.addAll(batch)
                    offset += batch.size
                    reachedEnd = batch.size < size || selection == "recent"
                } else {
                    val batch = if (customMovies != null)
                        customMovies.drop(offset).take(size)
                    else catalog.moviePage(sourceId, category, onlyFavourites, query,
                        size, offset, sortMode)
                    moviePage.addAll(batch)
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

    if (isHandheldPortrait()) {
        // Entire TV/landscape branch below is untouched. The portrait layout
        // keeps source/category, lazy paging, Continue Watching and search.
        val columns = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600)
            3 else 2
        val heading = when (selection) {
            "all" -> "ALL"
            "continue" -> "CONTINUE WATCHING"
            "favourites" -> "FAVOURITES"
            "recent" -> "RECENTLY ADDED"
            else -> categoriesState.firstOrNull { it.id == selection }?.name ?: "ALL"
        }
        Column(Modifier.fillMaxSize().background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(if (isSeries) "SERIES" else "MOVIES",
                    color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Master Search", tint = Color.White)
                }
            }
            TvNativeTextField(titleQuery, { titleQuery = it },
                if (isSeries) "Search Series" else "Search Movies",
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TvNativeTextField(categoryQuery, { categoryQuery = it },
                        "Filter categories", modifier = Modifier.fillMaxWidth())
                }
                if (sources.size > 1) Box {
                    TextButton(onClick = { sourceMenu = true }) {
                        Text("Provider", color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = sourceMenu,
                        onDismissRequest = { sourceMenu = false }) {
                        DropdownMenuItem(text = { Text("All providers") }, onClick = {
                            viewModel.selectVodSource(null); sourceMenu = false
                        })
                        sources.forEach { src ->
                            DropdownMenuItem(
                                text = { Text(src.name, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis) },
                                onClick = { viewModel.selectVodSource(src.id); sourceMenu = false })
                        }
                    }
                }
            }
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item(key = "all") {
                    PortraitCategoryChip("ALL", selection == "all") { chooseCategory("all") }
                }
                item(key = "fav") {
                    PortraitCategoryChip("FAVOURITES", selection == "favourites") {
                        chooseCategory("favourites")
                    }
                }
                item(key = "continue") {
                    PortraitCategoryChip("CONTINUE WATCHING", selection == "continue") {
                        chooseCategory("continue")
                    }
                }
                item(key = "recent") {
                    PortraitCategoryChip("RECENTLY ADDED", selection == "recent") {
                        chooseCategory("recent")
                    }
                }
                items(categories, key = { "p:" + it.sourceId + ":" + it.id }) { cat ->
                    PortraitCategoryChip(cat.name, selection == cat.id) {
                        chooseCategory(cat.id)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(heading, color = Color.White.copy(alpha = .9f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                BrowseSortMenu(kind, isFavourites, sortMode) {
                    browse.setSort(kind, isFavourites, it)
                }
            }
            if (selection == "continue") {
                val filtered = remember(scopedResume, titleQuery) {
                    scopedResume.filter {
                        titleQuery.isBlank() || if (ArabicSearch.hasArabic(titleQuery))
                            ArabicSearch.matches(it.title, titleQuery)
                        else it.title.contains(titleQuery.trim(), ignoreCase = true)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    gridItems(filtered, key = { it.logicalKey }) { item ->
                        ResumePoster(item,
                            onPlay = { onResume(item.mediaKey, item.streamUrl, item.title) },
                            onOpenTitle = {
                                scope.launch {
                                    if (item.seriesProviderId != null) {
                                        catalog.seriesByProviderId(item.sourceId, item.seriesProviderId)
                                            ?.let(onOpenSeries)
                                    } else {
                                        item.mediaKey.removePrefix("movie:").toLongOrNull()
                                            ?.let { catalog.movie(it) }?.let(onOpenMovie)
                                    }
                                }
                            },
                            onReset = { resetTarget = item },
                            onFavouriteStatus = { resumeFavourite(item) },
                            onToggleFavourite = { toggleResumeFavourite(item) })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns), state = grid,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (isSeries) gridItems(seriesPage, key = { "s:" + it.id }) { item ->
                        TvPoster(title = item.displayTitle, poster = item.posterUrl,
                            onClick = { onOpenSeries(item) },
                            onFocusedState = { hasFocus ->
                                seriesPrefetch.onFocusChanged(item, hasFocus)
                            },
                            favourite = item.favourite,
                            resumeProgress = seriesProgress(item),
                            onToggleFavourite = { toggleSeries(item) },
                            onMove = if (isFavourites && sortMode == BrowseSort.CUSTOM &&
                                         titleQuery.isBlank())
                                ({ delta -> moveFavourite(seriesKey(item), delta) }) else null,
                            grabbed = grabbedKey == seriesKey(item),
                            onGrab = { grabbedKey = seriesKey(item) },
                            onDrop = ::finishReorder,
                            columns = if (isHandheldPortrait()) 2 else 5,
                            onReset = { viewModel.resetSeriesProgress(item) })
                    } else gridItems(moviePage, key = { "m:" + it.id }) { item ->
                        TvPoster(title = item.displayTitle, poster = item.posterUrl,
                            onClick = { onOpenMovie(item) },
                            onPlay = { onResume("movie:" + item.id, item.streamUrl, item.displayTitle) },
                            favourite = item.favourite,
                            resumeProgress = movieProgress(item),
                            onToggleFavourite = { toggleMovie(item) },
                            onMove = if (isFavourites && sortMode == BrowseSort.CUSTOM &&
                                         titleQuery.isBlank())
                                ({ delta -> moveFavourite(movieKey(item), delta) }) else null,
                            grabbed = grabbedKey == movieKey(item),
                            onGrab = { grabbedKey = movieKey(item) },
                            onDrop = ::finishReorder,
                            columns = if (isHandheldPortrait()) 2 else 5,
                            onReset = { viewModel.resetMovieProgress(item.id) })
                    }
                    if (loading) item(key = "loading") {
                        Box(Modifier.fillMaxWidth().height(64.dp),
                            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                if (!loading && (if (isSeries) seriesPage.isEmpty() else moviePage.isEmpty())) {
                    Text(if (titleQuery.isBlank()) "No titles in this category"
                        else "No matching titles", color = Color.LightGray, maxLines = 2)
                }
            }
        }
    } else {
    Row(Modifier.fillMaxSize().background(background)) {
        Column(
            Modifier.weight(0.94f).fillMaxSize().background(sideBackground)
                .padding(start = 18.dp, end = 9.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("SOKY IPTV PRO", color = Color.White, fontWeight = FontWeight.Bold,
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(heading, color = Color.White.copy(alpha = .91f), fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                BrowseSortMenu(kind, isFavourites, sortMode) {
                    browse.setSort(kind, isFavourites, it)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (selection == "continue") {
                if (scopedResume.isEmpty()) {
                    Text("Nothing to continue yet", color = Color.LightGray)
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(VOD_COLUMNS),
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        gridItems(scopedResume.filter {
                            titleQuery.isBlank() ||
                                if (ArabicSearch.hasArabic(titleQuery))
                                    ArabicSearch.matches(it.title, titleQuery)
                                else it.title.contains(titleQuery.trim(), ignoreCase = true)
                        }, key = { it.logicalKey }) { item ->
                            ResumePoster(
                                item,
                                onPlay = { onResume(item.mediaKey, item.streamUrl, item.title) },
                                onOpenTitle = {
                                    scope.launch {
                                        if (item.seriesProviderId != null) {
                                            catalog.seriesByProviderId(item.sourceId, item.seriesProviderId)?.let(onOpenSeries)
                                        } else {
                                            item.mediaKey.removePrefix("movie:").toLongOrNull()
                                                ?.let { catalog.movie(it) }?.let(onOpenMovie)
                                        }
                                    }
                                },
                                onReset = { resetTarget = item },
                                onFavouriteStatus = { resumeFavourite(item) },
                                onToggleFavourite = { toggleResumeFavourite(item) },
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(VOD_COLUMNS),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (isSeries) gridItems(seriesPage, key = { "s:${it.id}" }) { item ->
                        TvPoster(
                            title = item.displayTitle, poster = item.posterUrl,
                            onClick = { onOpenSeries(item) },
                            onFocusedState = { hasFocus ->
                                seriesPrefetch.onFocusChanged(item, hasFocus)
                            },
                            favourite = item.favourite,
                            resumeProgress = seriesProgress(item),
                            onToggleFavourite = { toggleSeries(item) },
                            onMove = if (isFavourites && sortMode == BrowseSort.CUSTOM &&
                                         titleQuery.isBlank())
                                ({ delta -> moveFavourite(seriesKey(item), delta) }) else null,
                            grabbed = grabbedKey == seriesKey(item),
                            onGrab = { grabbedKey = seriesKey(item) },
                            onDrop = ::finishReorder,
                            columns = if (isHandheldPortrait()) 2 else 5,
                            onReset = { viewModel.resetSeriesProgress(item) },
                        )
                    }
                    else gridItems(moviePage, key = { "m:${it.id}" }) { item ->
                        TvPoster(
                            title = item.displayTitle, poster = item.posterUrl,
                            onClick = { onOpenMovie(item) },
                            onPlay = { onResume("movie:${item.id}", item.streamUrl, item.displayTitle) },
                            favourite = item.favourite,
                            resumeProgress = movieProgress(item),
                            onToggleFavourite = { toggleMovie(item) },
                            onMove = if (isFavourites && sortMode == BrowseSort.CUSTOM &&
                                         titleQuery.isBlank())
                                ({ delta -> moveFavourite(movieKey(item), delta) }) else null,
                            grabbed = grabbedKey == movieKey(item),
                            onGrab = { grabbedKey = movieKey(item) },
                            onDrop = ::finishReorder,
                            columns = if (isHandheldPortrait()) 2 else 5,
                            onReset = { viewModel.resetMovieProgress(item.id) },
                        )
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
    } // end portrait vs unchanged TV/landscape
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
private fun PortraitCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier
        .background(if (selected) teal else Color(0xFF1A2443), shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CategoryOption(label: String, count: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val selectedBackground = if (selected) teal else if (focused) Color(0xFF18243C) else sideBackground
    Row(
        Modifier.fillMaxWidth()
            .background(selectedBackground)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
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
private fun TvPoster(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onFocusedState: (Boolean) -> Unit = {},
    favourite: Boolean = false,
    resumeProgress: VodViewModel.ResumeItem? = null,
    onToggleFavourite: (() -> Unit)? = null,
    onMove: ((Int) -> Unit)? = null,
    grabbed: Boolean = false,
    onGrab: () -> Unit = {},
    onDrop: () -> Unit = {},
    columns: Int = 5,
    onReset: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(grabbed) {
        if (grabbed) {
            delay(75)
            runCatching { restoreFocus.requestFocus() }
        }
    }
    // One focus target includes both artwork AND its two-line title. The lazy
    // grid naturally scrolls the entire focused tile into view while allowing
    // a neighbouring row to remain partially visible. No second scroll job.
    Column(
        Modifier.fillMaxWidth()
            .focusRequester(restoreFocus)
            .grabFavouriteKeys(grabbed, columns, { delta -> onMove?.invoke(delta) }, onDrop)
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusedState(it.isFocused)
                }
            }
            .tvRemoteActions(
                onClick = onClick,
                onLongPress = { menuOpen = true },
            ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(.69f)
                .border(if (grabbed) 3.dp else if (focused) 2.dp else 0.dp,
                    if (grabbed) Color(0xFFFFD466) else Color(0xFF7DE4F0), RoundedCornerShape(3.dp))
                .background(Color(0xFF151B2B)),
        ) {
            AsyncImage(model = poster, contentDescription = title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            resumeProgress?.let { saved ->
                val fraction = ResumeCardTime.fraction(saved.positionMs, saved.durationMs)
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(
                        Color.Transparent, Color.Black.copy(alpha = .95f))))
                    .padding(horizontal = 6.dp, vertical = 9.dp)) {
                    Text(ResumeCardTime.format(saved.positionMs, saved.durationMs),
                        color = Color.White, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                fraction?.let { progress ->
                    Box(Modifier.fillMaxWidth().height(4.dp)
                        .align(Alignment.BottomCenter).background(Color.DarkGray)) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp)
                            .background(teal))
                    }
                }
            }
            if (grabbed) {
                // A decorative grab affordance; never a new focus target.
                Column(Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = .78f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null,
                            tint = Color(0xFFFFD466), modifier = Modifier.size(23.dp))
                        Icon(Icons.Default.PanTool, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(26.dp))
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null,
                            tint = Color(0xFFFFD466), modifier = Modifier.size(23.dp))
                    }
                    Text("GRABBED", color = Color(0xFFFFD466), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                    Text("OK to drop", color = Color.White, fontSize = 10.sp)
                }
            }
            // Avoid inserting a second focus target when the poster acquires focus.
            // Long-press OK or the Menu key still opens title options.
            if (isHandheldPortrait()) IconButton(
                onClick = { menuOpen = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Title options", tint = Color.White)
            } else if (focused) Icon(Icons.Default.MoreVert, contentDescription = null,
                tint = Color.White, modifier = Modifier.align(Alignment.TopEnd))
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onPlay != null) DropdownMenuItem(
                    text = { Text("Play movie") },
                    onClick = { menuOpen = false; onPlay() },
                )
                DropdownMenuItem(
                    text = { Text(if (onPlay == null) "Open episodes" else "Open title") },
                    onClick = { menuOpen = false; onClick() },
                )
                if (onToggleFavourite != null) {
                    DropdownMenuItem(
                        text = { Text(if (favourite) "Remove from favourites" else "Add to favourites") },
                        onClick = { menuOpen = false; onToggleFavourite() },
                    )
                }
                if (onMove != null) {
                    DropdownMenuItem(
                        text = { Text("Move — grab title") },
                        onClick = { menuOpen = false; onGrab() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Reset progress") },
                    onClick = { menuOpen = false; onReset() },
                )
            }
        }
        if (grabbed) Text("MOVE  ← ↑ ↓ →  ·  OK to drop",
            color = Color(0xFFFFD466), fontSize = 10.sp, maxLines = 1)
        Text(title, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 6.dp))
    }
}

@Composable
private fun ResumePoster(
    item: VodViewModel.ResumeItem,
    onPlay: () -> Unit,
    onOpenTitle: () -> Unit,
    onReset: () -> Unit,
    onFavouriteStatus: suspend () -> Boolean? = { null },
    onToggleFavourite: suspend () -> Boolean? = { null },
) {
    val scope = rememberCoroutineScope()
    var favourite by remember(item.logicalKey) { mutableStateOf<Boolean?>(null) }
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) favourite = onFavouriteStatus()
    }
    // The value displayed must be derived from the exact saved resume timestamp.
    // A missing provider total means "unknown duration", never 0 minutes watched.
    val verifiedProgress = ResumeCardTime.fraction(item.positionMs, item.durationMs)
    val resumeText = ResumeCardTime.format(item.positionMs, item.durationMs)
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, Color(0xFF7DE4F0), RoundedCornerShape(4.dp))
            .tvRemoteActions(onPlay, { menuOpen = true }),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.69f)) {
            AsyncImage(item.posterUrl, contentDescription = item.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (isHandheldPortrait()) IconButton(
                onClick = { menuOpen = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Resume options", tint = Color.White)
            } else if (focused) Icon(Icons.Default.MoreVert, contentDescription = null,
                tint = Color.White, modifier = Modifier.align(Alignment.TopEnd))
            Box(Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = .95f)),
                ))
                .padding(horizontal = 5.dp, vertical = 5.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = Color.White,
                        fontSize = 12.sp, maxLines = 2,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Ellipsis)
                    item.episodeLabel?.let { Text(it, color = Color(0xFF9EE9FF),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Text(resumeText,
                        color = Color.White.copy(alpha = .88f),
                        fontSize = 10.sp, maxLines = 1)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Resume") }, onClick = { menuOpen = false; onPlay() })
                DropdownMenuItem(text = { Text("Open title") }, onClick = { menuOpen = false; onOpenTitle() })
                DropdownMenuItem(
                    text = { Text(if (favourite == true)
                        "Remove from favourites" else "Add to favourites") },
                    onClick = {
                        menuOpen = false
                        scope.launch { favourite = onToggleFavourite() }
                    },
                )
                DropdownMenuItem(text = { Text("Reset progress") }, onClick = { menuOpen = false; onReset() })
            }
        }
        // Unknown total? Do not draw a misleading empty 0% progress bar.
        verifiedProgress?.let { fraction ->
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.DarkGray)) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(teal))
            }
        }
    }
}
