/*
 * Koky & Sosy Player TV: deliberately entered native Android IME fields.
 * Based on the GPL-3.0-or-later project. This component never opens a keyboard on hover.
 */
package app.opentv.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The overlay is the D-pad focus target while not editing. The actual TextField is
 * not focused until OK/click, so the installed system keyboard cannot open on hover.
 * Back leaves editing and does not re-trigger the IME.
 */
@Composable
fun TvNativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    initialFocus: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val navigationRequester = remember { FocusRequester() }
    val editingRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(initialFocus) {
        if (initialFocus) navigationRequester.requestFocus()
    }
    LaunchedEffect(editing) {
        if (editing) {
            editingRequester.requestFocus()
            keyboard?.show()
        } else if (focused) {
            // Keep the navigation target focused after leaving editing.
            navigationRequester.requestFocus()
        }
    }
    BackHandler(enabled = editing) {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        editing = false
    }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            readOnly = !editing,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth().focusRequester(editingRequester)
                .onFocusChanged { if (editing && !it.hasFocus) editing = false },
        )
        if (!editing) {
            Box(
                Modifier.matchParentSize().focusRequester(navigationRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .border(
                        if (focused) 3.dp else 0.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { editing = true },
            )
        }
    }
}
