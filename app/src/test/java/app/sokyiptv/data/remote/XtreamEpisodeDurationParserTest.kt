package app.sokyiptv.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class XtreamEpisodeDurationParserTest {
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun readsStandardSecondsField() {
        val e = obj("""{"info":{"duration_secs":"2886","duration":"00:48:06"}}""")
        assertThat(XtreamEpisodeDurationParser.fromFields(obj("""{"duration_secs":"2886"}"""), e))
            .isEqualTo(2886)
    }

    @Test fun supportsProviderClockFormatsAndFormattedMinutes() {
        assertThat(XtreamEpisodeDurationParser.parse("00:48:06")).isEqualTo(2886)
        assertThat(XtreamEpisodeDurationParser.parse("48:06")).isEqualTo(2886)
        assertThat(XtreamEpisodeDurationParser.parse("48 min")).isEqualTo(2880)
        assertThat(XtreamEpisodeDurationParser.parse("48 minutes")).isEqualTo(2880)
        assertThat(XtreamEpisodeDurationParser.parse("48m")).isEqualTo(2880)
    }

    @Test fun fallsBackToTopLevelOnlyForTheSameEpisode() {
        val info = obj("""{"duration_secs":"0"}""")
        val episode = obj("""{"duration":"00:48:06"}""")
        assertThat(XtreamEpisodeDurationParser.fromFields(info, episode)).isEqualTo(2886)
    }

    @Test fun rejectsAmbiguousShortOrMalformedOrImplausibleLengths() {
        for (bad in listOf(null, "", "0", "48", "29:78", "2:70:05", "hello",
            "99999", "25:00:00", "-180", "NaN", "48:50:99", "48 minutes please")) {
            assertThat(XtreamEpisodeDurationParser.parse(bad)).isNull()
        }
    }
}
