/*
 * Soky IPTV Pro — original category-and-channel catalogue UI.
 * TV focus + touch-friendly lazy grid; the full EPG remains accessible separately.
 */
package app.sokyiptv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sokyiptv.data.model.Channel
import app.sokyiptv.data.model.Source
import app.sokyiptv.data.model.shownName
import app.sokyiptv.ui.ChannelsViewModel
import app.sokyiptv.ui.isHandheldPortrait
import coil.compose.AsyncImage

private val catalogueBackground = Color(0xFF070C19)
private val catalogueSelected = Color(0xFF087D92)

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
    onGuide: () -> Unit,
) {
    var searchChannels by remember { mutableStateOf("") }
    var searchCategories by remember { mutableStateOf("") }
    var categoriesOpen by remember { mutableStateOf(false) }
    val phonePortrait = isHandheldPortrait()
    // Avoid materialising a second catalogue or fetching logos until cards enter the lazy grid.
    val filteredRows = remember(rows, searchChannels) {
        val query = searchChannels.trim()
        if (query.isBlank()) rows
        else rows.filter { it.primary.shownName.contains(query, ignoreCase = true) }
    }
    val displayedCategories = remember(categories, searchCategories) {
        categories.filter { it.label.contains(searchCategories.trim(), ignoreCase = true) }
    }
    val heading = when {
        favouritesOnly -> "Favourites"
        selectedCategory == null -> "All channels"
        else -> categories.firstOrNull { it.key == selectedCategory }?.label ?: "Live TV"
    }
    val chooseCategory: (String?) -> Unit = { onCategory(it); categoriesOpen = false }
    val chooseFavourites: () -> Unit = { onFavourites(); categoriesOpen = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(catalogueBackground)) {
        val sideWidth = (maxWidth * .30f).coerceIn(204.dp, 290.dp)
        val narrow = phonePortrait || maxWidth < 620.dp
        val gridWidth = if (narrow) maxWidth else maxWidth - sideWidth
        val columns = (gridWidth.value / if (narrow) 150f else 138f)
            .toInt().coerceIn(if (narrow) 2 else 3, 5)
        Row(Modifier.fillMaxSize()) {
            if (!narrow) {
                CatalogueRail(
                    categories = displayedCategories, selectedCategory = selectedCategory,
                    favouritesOnly = favouritesOnly,
                    sources = sources, selectedSource = selectedSource,
                    categoryQuery = searchCategories, onCategoryQuery = { searchCategories = it },
                    onCategory = chooseCategory, onFavourites = chooseFavourites,
                    onSource = onSource, modifier = Modifier.width(sideWidth).fillMaxHeight(),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (narrow) {
                        TextButton(onClick = { categoriesOpen = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Choose category")
                            Text("Categories", maxLines = 1, softWrap = false)
                        }
                    }
                    Text(heading.uppercase(), color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onGuide) {
                        Icon(Icons.Filled.LiveTv, contentDescription = null)
                        Text("TV guide", maxLines = 1, softWrap = false)
                    }
                }
                OutlinedTextField(
                    value = searchChannels, onValueChange = { searchChannels = it },
                    label = { Text("Search channels", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                if (filteredRows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (rows.isEmpty()) "Loading channels or no channels in this category"
                            else "No matching channels",
                            color = Color.White.copy(alpha = .7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(22.dp))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(filteredRows, key = { it.primary.id }) { row ->
                            CatalogueChannelCard(row.primary, onClick = { onPlay(row.primary) })
                        }
                    }
                }
            }
        }
    }
    if (phonePortrait && categoriesOpen) {
        Dialog(onDismissRequest = { categoriesOpen = false }) {
            CatalogueRail(
                categories = displayedCategories, selectedCategory = selectedCategory,
                favouritesOnly = favouritesOnly,
                sources = sources, selectedSource = selectedSource,
                categoryQuery = searchCategories, onCategoryQuery = { searchCategories = it },
                onCategory = chooseCategory, onFavourites = chooseFavourites,
                onSource = { onSource(it); categoriesOpen = false },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(.78f)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}

@Composable
private fun CatalogueRail(
    categories: List<ChannelsViewModel.CategoryGroup>,
    selectedCategory: String?,
    favouritesOnly: Boolean,
    sources: List<Source>,
    selectedSource: Long?,
    categoryQuery: String,
    onCategoryQuery: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onFavourites: () -> Unit,
    onSource: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(Color(0xFF090C15)).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("SOKY LIVE", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
                maxLines = 1, softWrap = false)
        }
        OutlinedTextField(
            value = categoryQuery, onValueChange = onCategoryQuery,
            label = { Text("Search categories", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (sources.size > 1) {
                item(key = "all-providers") {
                    CatalogueCategoryRow("All providers", selectedSource == null) {
                        onSource(null)
                    }
                }
                items(sources, key = { "src-" + it.id }) { source ->
                    CatalogueCategoryRow(source.name, selectedSource == source.id) {
                        onSource(source.id)
                    }
                }
            }
            item(key = "all") {
                CatalogueCategoryRow("ALL", !favouritesOnly && selectedCategory == null) {
                    onCategory(null)
                }
            }
            item(key = "favourites") {
                CatalogueCategoryRow("FAVOURITES", favouritesOnly, onFavourites)
            }
            items(categories, key = { it.key }) { group ->
                CatalogueCategoryRow(group.label, !favouritesOnly && selectedCategory == group.key) {
                    onCategory(group.key)
                }
            }
        }
    }
}

@Composable
private fun CatalogueCategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (focused || selected) catalogueSelected else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White,
            fontSize = 15.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CatalogueChannelCard(channel: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier.fillMaxWidth().aspectRatio(1.07f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF232B3B), Color(0xFF10141E))))
            .border(if (focused) 2.dp else 1.dp,
                if (focused) Color(0xFF78DCE8) else Color.White.copy(alpha = .12f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(6.dp), contentAlignment = Alignment.Center) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.Tv, contentDescription = null,
                    tint = Color.White.copy(alpha = .58f),
                    modifier = Modifier.fillMaxHeight(.55f))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .background(Color.Black.copy(alpha = .52f))
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(channel.shownName,
                color = Color.White, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
