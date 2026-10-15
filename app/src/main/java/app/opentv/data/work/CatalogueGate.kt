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
    enum class Phase { IDLE, WAITING, LIVE, MOVIES, SERIES, COMPLETE, FAILED }
    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val completed: Int = 0,
        val message: String = "",
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
    fun liveDone() { mutable.value = Snapshot(Phase.MOVIES, 1, "Updating Movies…") }
    fun moviesDone() { mutable.value = Snapshot(Phase.SERIES, 2, "Updating Series…") }
    fun done() { mutable.value = Snapshot(Phase.COMPLETE, 3, "Catalogue is ready") }
    fun failed(reason: String) {
        val old = mutable.value
        mutable.value = old.copy(phase = Phase.FAILED, message = reason)
    }
}
