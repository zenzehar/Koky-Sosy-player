/*
 * Koky & Sosy Player: in-session launch-only catalogue refresh gate.
 * Phases represent actual completed sync stages, not guessed network percentages.
 */
package app.opentv.data.work

import app.opentv.core.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CatalogueGate {
    enum class Phase { IDLE, WAITING, LIVE, MOVIES, SERIES, BUILDING, COMPLETE, FAILED }
    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val completed: Int = 0,
        val message: String = "",
        val liveFraction: Float = 0f,
        val movieFraction: Float = 0f,
        val seriesFraction: Float = 0f,
    )
    private val mutable = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutable.asStateFlow()

    fun due(settings: AppSettings, now: Long = System.currentTimeMillis()): Boolean {
        val hours = settings.catalogueRefreshHours.value
        if (hours == 0) return false
        val last = settings.catalogueSyncedAtMillis
        return last <= 0L || now < last || now - last >= hours * 3_600_000L
    }

    fun pending() { mutable.value = Snapshot(Phase.WAITING, 0, "Waiting for catalogue update…") }
    fun started() { mutable.value = Snapshot(Phase.LIVE, 0, "Updating Live TV…") }
    fun liveDone() { mutable.value = mutable.value.copy(phase = Phase.MOVIES,
        completed = 1, message = "Updating Movies…", liveFraction = 1f) }
    fun moviesDone() { mutable.value = mutable.value.copy(phase = Phase.SERIES, completed = 2,
        message = "Updating Series…", movieFraction = 1f) }
    fun finalizing() {
        mutable.value = mutable.value.copy(phase = Phase.BUILDING, completed = 2,
            seriesFraction = 1f, message = "Preparing guide and catalogue…")
    }
    fun liveImportProgress(fraction: Float) {
        mutable.value = mutable.value.copy(liveFraction = fraction.coerceIn(0f, 0.99f))
    }
    fun done() { mutable.value = mutable.value.copy(phase = Phase.COMPLETE, completed = 3,
        message = "Catalogue is ready", liveFraction = 1f,
        movieFraction = 1f, seriesFraction = 1f) }
    // These values represent confirmed titles written to the local Room database.
    // Download bytes are not measurable here, so the UI says "Fetching" until
    // the provider returns the catalogue and importing begins.
    fun moviesImportProgress(fraction: Float) {
        mutable.value = mutable.value.copy(movieFraction = fraction.coerceIn(0f, 0.99f))
    }
    fun seriesImportProgress(fraction: Float) {
        mutable.value = mutable.value.copy(seriesFraction = fraction.coerceIn(0f, 0.99f))
    }
    fun failed(reason: String) {
        val old = mutable.value
        mutable.value = old.copy(phase = Phase.FAILED, message = reason)
    }
}
