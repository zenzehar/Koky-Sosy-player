package app.sokyiptv.data.work

/**
 * Importers may process thousands of rows in small Room batches. Publishing
 * every batch creates redundant WorkManager database transactions, Flow
 * invalidations and Compose recompositions while the user navigates.
 *
 * This gate is per-worker (never global), and preserves the final 100%.
 */
internal class UiProgressThrottle(private val minimumGapMs: Long = 160L) {
    private var lastPercent = -1
    private var lastAtMs = Long.MIN_VALUE

    fun shouldPublish(fraction: Float, nowMs: Long): Boolean {
        val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
        if (percent == lastPercent) return false
        if (percent != 0 && percent != 100 && lastAtMs != Long.MIN_VALUE &&
            nowMs - lastAtMs < minimumGapMs) return false
        lastPercent = percent
        lastAtMs = nowMs
        return true
    }
    fun publishedPercent(): Int = lastPercent.coerceAtLeast(0)
}
