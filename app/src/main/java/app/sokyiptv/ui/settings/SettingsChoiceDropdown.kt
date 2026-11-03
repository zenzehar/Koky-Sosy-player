package app.sokyiptv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One ordinary button for mutually exclusive choices instead of many radio rows.
 * DropdownMenu gives its entries native D-pad focus and touch selection.
 */
@Composable
internal fun SettingsChoiceDropdown(
    label: String,
    choices: List<String>,
    selectedIndex: Int,
    selectedOverride: String? = null,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = selectedOverride ?: choices.getOrElse(selectedIndex) { choices.firstOrNull().orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text("▾", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false },
                modifier = Modifier.heightIn(max = 360.dp)) {
                choices.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = {
                            Text((if (index == selectedIndex) "✓  " else "") + option,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        onClick = { open = false; onSelect(index) },
                    )
                }
            }
        }
    }
}
