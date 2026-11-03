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
import app.sokyiptv.ui.ChannelsViewModel
import app.sokyiptv.ui.isHandheldPortrait
import app.sokyiptv.ui.onboarding.TvNativeTextField
import app.sokyiptv.ui.vod.tvRemoteActions
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    onGuide: () -> Unit,
    onExit: () -> Unit,
) {
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var titleQuery by rememberSaveable { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val portrait = isHandheldPortrait()
    val categoriesFiltered = remember(categories, categoryQuery) {
        val q = categoryQuery.trim()
        if (q.isEmpty()) categories else categories.filter {
            LiveCatalogueSearch.matches(it.label, q)
        }
    }

    // Movies/Series avoid transforming large databases on the Compose UI thread.
    // Filtering this light, logo/name-only catalogue also happens off-main.
    val visibleRows by produceState(initialValue = rows, rows, titleQuery) {
        val q = titleQuery.trim()
        if (q.isBlank()) {
            value = rows
        } else {
            delay(90L) // Cancel the previous request when the user types again.
            value = withContext(Dispatchers.Default) {
                rows.filter { LiveCatalogueSearch.matches(it.primary.shownName, q) }
            }
        }
    }

    LaunchedEffect(selectedCategory, favouritesOnly, selectedSource) {
        grid.scrollToItem(0) // Category transition only; NEVER scroll on each focus change.
    }

    val heading = when {
        favouritesOnly -> "FAVOURITES"
        selectedCategory == null -> "ALL"
        else -> categories.firstOrNull { it.key == selectedCategory }?.label ?: "ALL"
    }
    val chooseCategory: (String?) -> Unit = {
        categoryQuery = ""
        titleQuery = ""
        onCategory(it)
    }
    val chooseFavourites: () -> Unit = {
        categoryQuery = ""
        titleQuery = ""
        onFavourites()
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
                    LivePortraitCategoryChip("ALL", !favouritesOnly && selectedCategory == null) {
                        chooseCategory(null)
                    }
                }
                item(key = "favourites") {
                    LivePortraitCategoryChip("FAVOURITES", favouritesOnly) { chooseFavourites() }
                }
                items(categoriesFiltered, key = { it.key }) { group ->
                    LivePortraitCategoryChip(
                        group.label,
                        !favouritesOnly && selectedCategory == group.key,
                    ) { chooseCategory(group.key) }
                }
            }
            Text(heading, color = Color.White.copy(alpha = .91f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        LiveCategoryOption("ALL", !favouritesOnly && selectedCategory == null) {
                            chooseCategory(null)
                        }
                    }
                    item(key = "favourites") {
                        LiveCategoryOption("FAVOURITES", favouritesOnly) { chooseFavourites() }
                    }
                    items(categoriesFiltered, key = { it.key }) { group ->
                        LiveCategoryOption(
                            group.label, !favouritesOnly && selectedCategory == group.key,
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
                Text(heading, color = Color.White.copy(alpha = .91f), fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
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
private fun LivePoster(channel: Channel, onClick: () -> Unit, onOptions: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvRemoteActions(onClick = onClick, onLongPress = onOptions),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(.69f)
                .border(if (focused) 2.dp else 0.dp, Color(0xFF7DE4F0), RoundedCornerShape(3.dp))
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
            if (isHandheldPortrait()) IconButton(
                onClick = onOptions, modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Channel options", tint = Color.White)
            } else if (focused) Icon(Icons.Default.MoreVert,
                contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd))
        }
        Text(
            channel.shownName, color = Color.White,
            fontSize = 12.sp, lineHeight = 16.sp,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 6.dp),
        )
    }
}
