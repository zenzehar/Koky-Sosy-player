package app.sokyiptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProgressPresentationTest {
    @Test fun nativeLengthZeroFallsBackToCatalogueEpisodeLength() {
        val result = ResumeProgressPresentation.resolve(
            positionMs = 29 * 60_000L,
            savedDurationMs = 0L,
            catalogueDurationSeconds = 45 * 60,
        )
        assertTrue(result.isKnown)
        assertEquals(45 * 60_000L, result.durationMs)
        assertEquals("64% · 29m / 45m", result.label(29 * 60_000L))
    }

    @Test fun unknownLengthShowsWatchedTimeInsteadOfZeroPercentOrZeroMinutes() {
        val result = ResumeProgressPresentation.resolve(29 * 60_000L, 0L, null)
        assertFalse(result.isKnown)
        assertEquals("29m watched", result.label(29 * 60_000L))
        assertEquals(0f, result.fraction, 0.0001f)
    }

    @Test fun accurateSavedDurationTakesPriorityOverCatalogueRuntime() {
        val result = ResumeProgressPresentation.resolve(10 * 60_000L,
            50 * 60_000L, 60 * 60)
        assertEquals(50 * 60_000L, result.durationMs)
        assertEquals("20% · 10m / 50m", result.label(10 * 60_000L))
    }

    @Test fun invalidOrShortProviderRuntimeCannotProduceFictitiousPercent() {
        val result = ResumeProgressPresentation.resolve(29 * 60_000L,
            savedDurationMs = 0L, catalogueDurationSeconds = 5 * 60)
        assertFalse(result.isKnown)
        assertEquals("29m watched", result.label(29 * 60_000L))
    }

    @Test fun nearEndIsNotMisrepresentedAsFinishedFromUnreliableMetadata() {
        val result = ResumeProgressPresentation.resolve(29 * 60_000L,
            savedDurationMs = 0L, catalogueDurationSeconds = 30 * 60)
        assertFalse(result.isKnown)
        assertEquals("29m watched", result.label(29 * 60_000L))
    }

    @Test fun shortElapsedTimeStillNeverShowsZeroMinutesWatched() {
        val result = ResumeProgressPresentation.resolve(31_000L, 0L, null)
        assertEquals("31s watched", result.label(31_000L))
    }
}
