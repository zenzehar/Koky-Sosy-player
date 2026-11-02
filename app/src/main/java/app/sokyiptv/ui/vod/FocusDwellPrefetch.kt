/*
 * Focus-dwell work is intentionally independent of Compose snapshot state:
 * rapidly moving the D-pad should only repaint the focused item, not the whole grid.
 * GPL-3.0-or-later.
 */
package app.sokyiptv.ui.vod

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Dispatches a request only when a focus target stays selected for [dwellMillis].
 *
 * The conflated channel keeps just the latest request during rapid D-pad traversal.
 * A blur for an old target must not cancel prefetch for a newer target. In particular,
 * there is no Compose State write on the focus path, avoiding top-level grid recomposition.
 *
 * Only use on the main/UI thread for focus callbacks; the collector is cancellable.
 */
internal class FocusDwellPrefetch<T : Any>(private val idOf: (T) -> Long) {
    private val changes = Channel<T?>(Channel.CONFLATED)
    private var focusedId: Long? = null

    fun onFocusChanged(item: T, focused: Boolean) {
        val id = idOf(item)
        if (focused) {
            if (focusedId != id) {
                focusedId = id
                changes.trySend(item)
            }
        } else if (focusedId == id) {
            focusedId = null
            changes.trySend(null)
        }
    }

    /** Cancel a pending request when provider, filter or displayed catalogue changes. */
    fun reset() {
        focusedId = null
        changes.trySend(null)
    }

    suspend fun collectAfterDwell(dwellMillis: Long, onReady: suspend (T) -> Unit) {
        require(dwellMillis >= 0L)
        // receiveAsFlow does not consume/cancel the Channel on collector cancellation,
        // so the same dispatcher survives normal composition/query lifecycle changes.
        changes.receiveAsFlow().collectLatest { item ->
            if (item != null) {
                delay(dwellMillis)
                onReady(item)
            }
        }
    }
}
