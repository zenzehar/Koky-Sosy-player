package app.sokyiptv.ui.vod

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusDwellPrefetchTest {
    @Test fun rapidDirectionalTraversalOnlyFetchesTheSettledPoster() = runTest {
        val prefetch = FocusDwellPrefetch<Long> { it }
        val fetched = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefetch.collectAfterDwell(320L) { fetched.add(it) }
        }

        prefetch.onFocusChanged(1L, true)
        advanceTimeBy(100L)
        prefetch.onFocusChanged(1L, false)
        prefetch.onFocusChanged(2L, true)
        advanceTimeBy(319L)
        runCurrent()
        assertThat(fetched).isEmpty()

        advanceTimeBy(1L)
        runCurrent()
        assertThat(fetched).containsExactly(2L)
    }

    @Test fun lateBlurFromPreviousPosterDoesNotCancelCurrentPoster() = runTest {
        val prefetch = FocusDwellPrefetch<Long> { it }
        val fetched = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefetch.collectAfterDwell(320L) { fetched.add(it) }
        }
        prefetch.onFocusChanged(1L, true)
        prefetch.onFocusChanged(2L, true)
        // Key events / focus cleanup need not arrive in strict blur-before-focus order.
        prefetch.onFocusChanged(1L, false)
        advanceTimeBy(320L)
        runCurrent()
        assertThat(fetched).containsExactly(2L)
    }

    @Test fun resetCancelsPrefetchWhenCategoryChanges() = runTest {
        val prefetch = FocusDwellPrefetch<Long> { it }
        val fetched = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefetch.collectAfterDwell(320L) { fetched.add(it) }
        }
        prefetch.onFocusChanged(4L, true)
        advanceTimeBy(160L)
        prefetch.reset()
        advanceTimeBy(400L)
        runCurrent()
        assertThat(fetched).isEmpty()
    }

    @Test fun unchangedFocusedPosterDoesNotRestartDwellTimer() = runTest {
        val prefetch = FocusDwellPrefetch<Long> { it }
        val fetched = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            prefetch.collectAfterDwell(320L) { fetched.add(it) }
        }
        prefetch.onFocusChanged(8L, true)
        advanceTimeBy(200L)
        prefetch.onFocusChanged(8L, true)
        advanceTimeBy(120L)
        runCurrent()
        assertThat(fetched).containsExactly(8L)
    }
}
