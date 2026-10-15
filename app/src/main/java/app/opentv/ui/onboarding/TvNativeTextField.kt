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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
    smartersStyle: Boolean = false,
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
            label = if (smartersStyle) null else ({ Text(label) }),
            placeholder = if (smartersStyle) ({ Text(label, color = Color(0xFF5B4C5A), fontSize = 21.sp) }) else null,
            shape = RoundedCornerShape(if (smartersStyle) 5.dp else 8.dp),
            colors = if (smartersStyle) OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFE8BCCD),
                unfocusedContainerColor = Color(0xFFDFB4C5),
                focusedTextColor = Color(0xFF171219),
                unfocusedTextColor = Color(0xFF171219),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color(0xFFEBDAE5),
                cursorColor = Color(0xFF2D1C65),
            ) else OutlinedTextFieldDefaults.colors(),
            textStyle = if (smartersStyle) TextStyle(fontSize = 21.sp) else TextStyle.Default,
            singleLine = true,
            readOnly = !editing,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth()
                .then(if (smartersStyle) Modifier.height(60.dp) else Modifier)
                .focusRequester(editingRequester)
                .onFocusChanged { if (editing && !it.hasFocus) editing = false },
        )
        if (!editing) {
            Box(
                Modifier.matchParentSize().focusRequester(navigationRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .border(
                        if (focused) 3.dp else 0.dp,
                        if (smartersStyle) Color.White else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { editing = true },
            )
        }
    }
}
