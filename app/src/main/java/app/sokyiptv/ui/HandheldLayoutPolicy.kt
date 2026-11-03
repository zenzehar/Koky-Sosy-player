/*
 * GPL-3.0-or-later. All mobile adaptations are opt-in:
 * no TV D-pad/navigation or any landscape layouts are changed.
 */
package app.sokyiptv.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.sokyiptv.isRunningOnTelevision

/** Keep actual TV devices on their existing ten-foot interface. */
internal object HandheldLayoutPolicy {
    fun portrait(isTelevision: Boolean, orientation: Int): Boolean =
        !isTelevision && orientation == Configuration.ORIENTATION_PORTRAIT
    fun touch(isTelevision: Boolean): Boolean = !isTelevision
}

@Composable
internal fun isHandheldPortrait(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return HandheldLayoutPolicy.portrait(
        isRunningOnTelevision(context), configuration.orientation)
}

@Composable
internal fun isHandheldTouch(): Boolean = !isRunningOnTelevision(LocalContext.current)
