/*
 * TV-native input with a SINGLE focus target: the focusable preview when idle,
 * the system-IME TextField only after OK is pressed. GPL-3.0-or-later.
 */
package app.opentv.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign

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
    var hasReceivedEditFocus by remember { mutableStateOf(false) }
    val idleRequester = remember { FocusRequester() }
    val editRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(if (smartersStyle) 5.dp else 7.dp)
    val frameColor = if (smartersStyle) Color(0xFFFFDB63) else Color(0xFF69DFF6)
    val bg = if (smartersStyle) Color(0xFFE6BDCB) else Color(0xFF171F36)
    val foreground = if (smartersStyle) Color(0xFF1A1520) else Color.White
    val height = if (smartersStyle) 64.dp else 61.dp

    LaunchedEffect(initialFocus) {
        if (initialFocus && !editing) {
            withFrameNanos { }
            runCatching { idleRequester.requestFocus() }
        }
    }
    LaunchedEffect(editing) {
        withFrameNanos { }
        if (editing) {
            runCatching { editRequester.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            runCatching { idleRequester.requestFocus() }
        }
    }
    BackHandler(enabled = editing) { editing = false }

    Box(modifier) {
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = if (smartersStyle) null else ({ Text(label) }),
                placeholder = if (smartersStyle) ({
                    Text(label, color = Color(0xFF665260), fontSize = 20.sp)
                }) else null,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                trailingIcon = trailingIcon,
                textStyle = TextStyle(
                    fontSize = if (smartersStyle) 20.sp else 17.sp,
                    lineHeight = if (smartersStyle) 28.sp else 26.sp,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = bg,
                    unfocusedContainerColor = bg,
                    focusedTextColor = foreground,
                    unfocusedTextColor = foreground,
                    focusedBorderColor = frameColor,
                    unfocusedBorderColor = Color.Transparent,
                ),
                shape = shape,
                modifier = Modifier.fillMaxWidth().height(height)
                    .focusRequester(editRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            hasReceivedEditFocus = true
                        } else if (editing && hasReceivedEditFocus && !it.hasFocus) {
                            hasReceivedEditFocus = false
                            editing = false
                        }
                    },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(height)
                    .background(bg, shape)
                    .focusRequester(idleRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .border(if (focused) 3.dp else 0.dp,
                        if (focused) frameColor else Color.Transparent, shape)
                    .clickable {
                        hasReceivedEditFocus = false
                        editing = true
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val display = when {
                    value.isBlank() -> label
                    visualTransformation is PasswordVisualTransformation -> "●".repeat(value.length.coerceAtMost(18))
                    else -> value
                }
                Text(
                    display,
                    color = if (value.isBlank()) foreground.copy(alpha = .63f) else foreground,
                    fontSize = if (smartersStyle) 20.sp else 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    lineHeight = if (smartersStyle) 28.sp else 26.sp,
                    modifier = Modifier.weight(1f),
                )
                if (trailingIcon != null) trailingIcon()
            }
        }
    }
}
