/*
 * Soky IPTV Pro Live TV catalogue.
 * Intentionally follows the Movies/Series browser layout, input and focus patterns.
 * EPG enrichment belongs to the separate TV Guide, not to this lightweight browser.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sokyiptv.data.model.Channel
import app.sokyiptv.data.model.Source
import app.sokyiptv.data.model.shownName
import app.sokyiptv.core.BrowseCustomizations
import app.sokyiptv.core.BrowseSort
import app.sokyiptv.ui.BrowseSortMenu
import app.sokyiptv.core.AppSettings
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import app.sokyiptv.ui.ChannelsViewModel
import app.sokyiptv.ui.isHandheldPortrait
import app.sokyiptv.ui.onboarding.TvNativeTextField
import app.sokyiptv.ui.vod.tvRemoteActions
import app.sokyiptv.ui.vod.grabFavouriteKeys
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val background = Color(0xFF080D20)
private val sideBackground = Color(0xFF030408)
private val teal = Color(0xFF006779)
private const val TV_COLUMNS = 5

/**
 * Identical navigation contract to Movies:
 * - TV: dark category rail, TV-native search opened by OK, five lazy columns;
 * - phone portrait: native touch IME, horizontal category chips, two/three columns;
 * - natural built-in LazyVerticalGrid focus scroll, without custom focus/scroll jobs;
 * - long-OK/Menu opens the channel's existing options, short OK plays the channel.
 */
@Composable
internal fun LiveCatalogueScreen(
    rows: List<ChannelsViewModel.Row>,
    categories: List<ChannelsViewModel.CategoryGroup>,
    selectedCategory: String?,
    favouritesOnly: Boolean,
    sources: List<Source>,
    selectedSource: Long?,
    onCategory: (String?) -> Unit,
    onFavourites: () -> Unit,
    onSource: (Long?) -> Unit,
    onPlay: (Channel) -> Unit,
    onOptions: (ChannelsViewModel.Row) -> Unit,
    onToggleFavourite: (ChannelsViewModel.Row) -> Unit,
    onGuide: () -> Unit,
    onExit: () -> Unit,
) {
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var titleQuery by rememberSaveable { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val browse = remember(context) { BrowseCustomizations(context) }
    val browseRevision by browse.revision.collectAsState()
    val profileId by remember(context) { AppSettings.get(context) }.activeProfileId.collectAsState()
    val history = remember(profileId, browseRevision) { browse.recentLive(profileId) }
    val sortMode = browse.sort("live", favouritesOnly)
    val grid = rememberLazyGridState()
    // Reorder in memory on arrows and save once on OK/Drop, never write prefs on
    // each D-pad repeat and never take focus away from the grabbed poster.
    var grabbedKey by remember { mutableStateOf<String?>(null) }
    var tentativeOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    val beginMove: (String, List<String>) -> Unit = { key, keys ->
        tentativeOrder = keys
        grabbedKey = key
    }
    val finishMove: () -> Unit = {
        if (grabbedKey != null) browse.saveFavouriteOrder("live", tentativeOrder)
        grabbedKey = null
        tentativeOrder = emptyList()
    }
    val stepMove: (String, Int) -> Unit = { key, delta ->
        val index = tentativeOrder.indexOf(key)
        val target = index + delta
        if (index >= 0 && target in tentativeOrder.indices) {
            tentativeOrder = tentativeOrder.toMutableList().apply {
                java.util.Collections.swap(this, index, target)
            }
        }
    }
    BackHandler(grabbedKey != null) { finishMove() }
    val portrait = isHandheldPortrait()
    val categoriesFiltered = remember(categories, categoryQuery) {
        val q = categoryQuery.trim()
        if (q.isEmpty()) categories else categories.filter {
            LiveCatalogueSearch.matches(it.label, q)
        }
    }

    // No EPG data, no per-focus scroll jobs; sort and filter off-main for large
    // playlists. Newly watched channels update only the per-profile Recently list.
    val visibleRows by produceState(initialValue = rows, rows, titleQuery, recentOnly,
                                    favouritesOnly, sortMode, profileId, browseRevision, tentativeOrder) {
        val q = titleQuery.trim()
        if (q.isNotBlank()) delay(90L)
        value = withContext(Dispatchers.Default) {
            val historyRanks = history.withIndex().associate { it.value to it.index }
            val scoped = rows.filter { row ->
                (!recentOnly || browse.orderKey(row.primary) in historyRanks) &&
                    LiveCatalogueSearch.matches(row.primary.shownName, q)
            }
            val tie = compareBy<ChannelsViewModel.Row> {
                it.primary.shownName.lowercase(java.util.Locale.ROOT)
            }.thenBy { it.primary.id }
            when {
                favouritesOnly && sortMode == BrowseSort.CUSTOM -> {
                    if (grabbedKey != null && tentativeOrder.isNotEmpty()) {
                        val rank = tentativeOrder.withIndex().associate { it.value to it.index }
                        scoped.withIndex().sortedWith(
                            compareBy<IndexedValue<ChannelsViewModel.Row>> {
                                rank[browse.orderKey(it.value.primary)] ?: Int.MAX_VALUE
                            }.thenBy { it.index }
                        ).map { it.value }
                    } else browse.customSort("live", scoped) { browse.orderKey(it.primary) }
                }
                recentOnly && sortMode == BrowseSort.DEFAULT ->
                    scoped.sortedBy { historyRanks[browse.orderKey(it.primary)] ?: Int.MAX_VALUE }
                sortMode == BrowseSort.NAME_ASC -> scoped.sortedWith(tie)
                sortMode == BrowseSort.NAME_DESC ->
                    scoped.sortedWith(compareByDescending<ChannelsViewModel.Row> {
                        it.primary.shownName.lowercase(java.util.Locale.ROOT)
                    }.thenBy { it.primary.id })
                sortMode == BrowseSort.NUMBER_ASC ->
                    scoped.sortedWith(compareBy<ChannelsViewModel.Row> {
                        it.primary.number ?: Int.MAX_VALUE
                    }.then(tie))
                sortMode == BrowseSort.NUMBER_DESC ->
                    scoped.sortedWith(compareByDescending<ChannelsViewModel.Row> {
                        it.primary.number ?: -1
                    }.then(tie))
                else -> scoped // Keep provider order (and legacy channel order) as default.
            }
        }
    }

    LaunchedEffect(selectedCategory, favouritesOnly, selectedSource, recentOnly) {
        grid.scrollToItem(0) // Only category transition; remote focus owns ordinary scrolling.
    }

    val heading = when {
        recentOnly -> "RECENTLY WATCHED"
        favouritesOnly -> "FAVOURITES"
        selectedCategory == null -> "ALL"
        else -> categories.firstOrNull { it.key == selectedCategory }?.label ?: "ALL"
    }
    val chooseCategory: (String?) -> Unit = {
        recentOnly = false
        categoryQuery = ""
        titleQuery = ""
        onCategory(it)
    }
    val chooseFavourites: () -> Unit = {
        recentOnly = false
        categoryQuery = ""
        titleQuery = ""
        onFavourites()
    }
    val chooseRecent: () -> Unit = {
        recentOnly = true
        categoryQuery = ""
        titleQuery = ""
        onCategory(null) // Recent history is not limited to a previously selected category.
    }
    LaunchedEffect(favouritesOnly, selectedSource, selectedCategory, sortMode, titleQuery) {
        if (grabbedKey != null) finishMove()
    }

    @Composable
    fun ProviderMenu() {
        if (sources.size > 1) {
            Box {
                LiveCategoryOption(
                    "Provider: " + (sources.firstOrNull { it.id == selectedSource }?.name ?: "All"),
                    selected = false,
                    onClick = { sourceMenu = true },
                )
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    DropdownMenuItem(text = { Text("All providers") }, onClick = {
                        onSource(null)
                        sourceMenu = false
                    })
                    sources.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { onSource(source.id); sourceMenu = false },
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ChannelsGrid(modifier: Modifier, columns: Int) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            gridItems(visibleRows, key = { it.primary.id }) { row ->
                LivePoster(
                    channel = row.primary,
                    onClick = { onPlay(row.primary) },
                    onOptions = { onOptions(row) },
                    onToggleFavourite = { onToggleFavourite(row) },
                    canMove = favouritesOnly && sortMode == BrowseSort.CUSTOM &&
                        titleQuery.isBlank() && !recentOnly,
                    grabbed = grabbedKey == browse.orderKey(row.primary),
                    columns = columns,
                    onGrab = {
                        beginMove(browse.orderKey(row.primary),
                            visibleRows.map { browse.orderKey(it.primary) })
                    },
                    onMove = { delta -> stepMove(browse.orderKey(row.primary), delta) },
                    onDrop = finishMove,
                )
            }
        }
    }

    if (portrait) {
        val columns =
            if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
        Column(
            Modifier.fillMaxSize().background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("LIVE TV", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onGuide) {
                    Icon(Icons.Default.LiveTv, contentDescription = "TV guide", tint = Color.White)
                    Text("Guide", maxLines = 1, softWrap = false, color = Color.White)
                }
            }
            TvNativeTextField(titleQuery, { titleQuery = it }, "Search Live TV",
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TvNativeTextField(categoryQuery, { categoryQuery = it },
                        "Filter categories", modifier = Modifier.fillMaxWidth())
                }
                if (sources.size > 1) {
                    Box {
                        TextButton(onClick = { sourceMenu = true }) {
                            Text("Provider", color = Color.White, maxLines = 1)
                        }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            DropdownMenuItem(text = { Text("All providers") },
                                onClick = { onSource(null); sourceMenu = false })
                            sources.forEach { source ->
                                DropdownMenuItem(text = { Text(source.name) },
                                    onClick = { onSource(source.id); sourceMenu = false })
                            }
                        }
                    }
                }
            }
            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item(key = "all") {
                    LivePortraitCategoryChip("ALL", !recentOnly && !favouritesOnly && selectedCategory == null) {
                        chooseCategory(null)
                    }
                }
                item(key = "favourites") {
                    LivePortraitCategoryChip("FAVOURITES", favouritesOnly) { chooseFavourites() }
                }
                item(key = "recently-watched") {
                    LivePortraitCategoryChip("RECENTLY WATCHED", recentOnly) { chooseRecent() }
                }
                items(categoriesFiltered, key = { it.key }) { group ->
                    LivePortraitCategoryChip(
                        group.label,
                        !recentOnly && !favouritesOnly && selectedCategory == group.key,
                    ) { chooseCategory(group.key) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(heading, color = Color.White.copy(alpha = .91f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BrowseSortMenu("live", favouritesOnly, sortMode) {
                    browse.setSort("live", favouritesOnly, it)
                }
            }
            Text("Tap ☆ to add a channel to Favourites",
                color = Color(0xFFB8D5DA), fontSize = 11.sp, maxLines = 1)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ChannelsGrid(Modifier.fillMaxSize(), columns)
                if (visibleRows.isEmpty()) {
                    Text("No channels in this category or search",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.LightGray)
                }
            }
        }
    } else {
        // TV / landscape: reproduce Movies' 0.94 : 2.8 sidebar-to-grid proportions,
        // typography, rail focus and five-column lazy grid scrolling.
        Row(Modifier.fillMaxSize().background(background)) {
            Column(
                Modifier.weight(0.94f).fillMaxSize().background(sideBackground)
                    .padding(start = 18.dp, end = 9.dp, top = 16.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text("SOKY IPTV PRO", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(19.dp))
                TvNativeTextField(categoryQuery, { categoryQuery = it },
                    "Search in categories", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                ProviderMenu()
                LazyColumn(Modifier.weight(1f)) {
                    item(key = "all") {
                        LiveCategoryOption("ALL", !recentOnly && !favouritesOnly && selectedCategory == null) {
                            chooseCategory(null)
                        }
                    }
                    item(key = "favourites") {
                        LiveCategoryOption("FAVOURITES", favouritesOnly) { chooseFavourites() }
                    }
                    item(key = "recently-watched") {
                        LiveCategoryOption("RECENTLY WATCHED", recentOnly) { chooseRecent() }
                    }
                    items(categoriesFiltered, key = { it.key }) { group ->
                        LiveCategoryOption(
                            group.label, !recentOnly && !favouritesOnly && selectedCategory == group.key,
                        ) { chooseCategory(group.key) }
                    }
                }
            }
            Column(
                Modifier.weight(2.8f).fillMaxSize()
                    .padding(start = 13.dp, end = 13.dp, top = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TvNativeTextField(titleQuery, { titleQuery = it }, "Search Live TV",
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onGuide) {
                        Icon(Icons.Default.LiveTv, contentDescription = "TV guide", tint = Color.White)
                        Text("TV guide", color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(heading, color = Color.White.copy(alpha = .91f), fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    BrowseSortMenu("live", favouritesOnly, sortMode) {
                        browse.setSort("live", favouritesOnly, it)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("★ Hold OK / Menu: favourites  ·  Yellow key: toggle",
                    color = Color(0xFFB8D5DA), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f)) {
                    ChannelsGrid(Modifier.fillMaxSize(), TV_COLUMNS)
                    if (visibleRows.isEmpty()) {
                        Text("No channels in this category or search",
                            color = Color.LightGray,
                            modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/** Same category highlight and single natural D-pad focus stop as Movies. */
@Composable
private fun LiveCategoryOption(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val selectedBackground =
        if (selected) teal else if (focused) Color(0xFF18243C) else sideBackground
    Row(Modifier.fillMaxWidth().background(selectedBackground)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(48.dp)
            .background(if (focused) Color(0xFF69E0EF)
                else if (selected) Color.White.copy(alpha = .55f) else Color.Transparent))
        Row(Modifier.weight(1f).padding(start = 13.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (focused || selected) Color.White else Color(0xFFDEDFE7),
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LivePortraitCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.background(if (selected) teal else Color(0xFF1A2443), shape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Same Movies poster focus action and natural viewport scroll; channel artwork keeps its aspect. */
@Composable
private fun LivePoster(
    channel: Channel,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    onToggleFavourite: () -> Unit,
    canMove: Boolean,
    grabbed: Boolean,
    columns: Int,
    onGrab: () -> Unit,
    onMove: (Int) -> Unit,
    onDrop: () -> Unit,
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
    Column(
        Modifier.fillMaxWidth()
            .focusRequester(restoreFocus)
            .onFocusChanged { focused = it.isFocused }
            .grabFavouriteKeys(grabbed, columns, onMove, onDrop)
            // One D-pad focus target per channel, as in Movies.
            // A hardware yellow key makes favouriting a single keypress on TV
            // remotes that offer it. Ordinary Menu/long-OK opens the full menu.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_PROG_YELLOW) {
                    if (event.type == KeyEventType.KeyUp) onToggleFavourite()
                    true
                } else false
            }
            .tvRemoteActions(onClick = onClick, onLongPress = { menuOpen = true }),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(.69f)
                .border(if (grabbed) 3.dp else if (focused) 2.dp else 0.dp,
                    if (grabbed) Color(0xFFFFD466) else Color(0xFF7DE4F0), RoundedCornerShape(3.dp))
                .background(Color(0xFF151B2B)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logoUrl.isNullOrBlank()) {
                Icon(Icons.Default.Tv, contentDescription = null,
                    tint = Color.White.copy(alpha = .58f), modifier = Modifier.fillMaxSize(.4f))
            } else {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.shownName,
                    modifier = Modifier.fillMaxSize().padding(7.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            // Direct one-tap favourite action on touch screens, not hidden in
            // a three-dot submenu. TV shows the same state as a non-focusable
            // indicator to keep all five columns D-pad smooth.
            if (isHandheldPortrait()) {
                IconButton(
                    onClick = onToggleFavourite,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        if (channel.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (channel.favourite)
                            "Remove from favourites" else "Add to favourites",
                        tint = if (channel.favourite) Color(0xFFFFD466) else Color.White,
                    )
                }
            } else {
                Icon(
                    if (channel.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (channel.favourite) "Favourite" else "Not favourite",
                    tint = if (channel.favourite) Color(0xFFFFD466) else Color.White.copy(alpha = .75f),
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                )
            }
            if (isHandheldPortrait()) IconButton(
                onClick = { menuOpen = true }, modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Channel options", tint = Color.White)
            } else if (focused) Icon(Icons.Default.MoreVert,
                contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Watch channel") },
                onClick = { menuOpen = false; onClick() })
            DropdownMenuItem(
                text = { Text(if (channel.favourite) "Remove from favourites" else "Add to favourites") },
                onClick = { menuOpen = false; onToggleFavourite() })
            if (canMove) DropdownMenuItem(
                text = { Text("Move — grab title") },
                onClick = { menuOpen = false; onGrab() },
            )
            DropdownMenuItem(text = { Text("More channel options") },
                onClick = { menuOpen = false; onOptions() })
        }
        if (grabbed) Text("MOVING · Arrows to move · OK to drop",
            color = Color(0xFFFFD466), maxLines = 1, fontSize = 10.sp)
        Text(
            channel.shownName, color = Color.White,
            fontSize = 12.sp, lineHeight = 16.sp,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 6.dp),
        )
    }
}
