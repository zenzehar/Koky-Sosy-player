package app.sokyiptv.ui.vod

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.sokyiptv.player.ManualSeriesSkips
import app.sokyiptv.player.ManualTimelineModel
import app.sokyiptv.player.MarkerRegion

internal val IntroBandColor = Color(0xFF40D5C4)
internal val CreditsBandColor = Color(0xFFFFBD61)

/**
 * Overlay across EVERY episode's seekbar, regardless of player renderer/style.
 * Ranges are exact user-defined times; incomplete marks show just thin ticks.
 * Call after drawing track/progress and before the playhead (when possible).
 */
internal fun DrawScope.drawManualTimelineBands(
    markers: ManualSeriesSkips,
    totalMs: Long,
    xStart: Float,
    xEnd: Float,
    centerY: Float,
    thickness: Float,
) {
    if (totalMs <= 0L || xEnd <= xStart) return
    val span = xEnd - xStart
    for (region in ManualTimelineModel.regions(markers, totalMs)) {
        val colour = if (region.kind == MarkerRegion.INTRO) IntroBandColor
            else CreditsBandColor
        drawLine(
            color = colour,
            start = Offset(xStart + region.startFraction * span, centerY),
            end = Offset(xStart + region.endFraction * span, centerY),
            strokeWidth = thickness,
            cap = StrokeCap.Butt,
        )
    }
    for (boundary in ManualTimelineModel.boundaries(markers, totalMs)) {
        val colour = if (boundary.kind == MarkerRegion.INTRO) IntroBandColor
            else CreditsBandColor
        val x = xStart + boundary.positionFraction * span
        drawLine(
            color = colour,
            start = Offset(x, centerY - thickness * 1.45f),
            end = Offset(x, centerY + thickness * 1.45f),
            strokeWidth = (thickness * 0.42f).coerceAtLeast(1f),
            cap = StrokeCap.Round,
        )
    }
}
