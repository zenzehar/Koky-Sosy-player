/* Shared sorting picker for Live TV, Movies and Series. GPL-3.0-or-later. */
package app.sokyiptv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import app.sokyiptv.core.BrowseSort

@Composable
internal fun BrowseSortMenu(
    kind: String,
    favourites: Boolean,
    current: BrowseSort,
    onChange: (BrowseSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Sort: " + current.title, maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BrowseSort.options(kind, favourites).forEach { mode ->
                DropdownMenuItem(
                    text = { Text((if (mode == current) "✓ " else "") + mode.title) },
                    onClick = { expanded = false; onChange(mode) },
                )
            }
        }
    }
}
