package app.sokyiptv.data.remote

import android.app.Application
import app.sokyiptv.data.model.Source
import app.sokyiptv.data.model.SourceKind
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class XtreamLiveStreamDecoderTest {
    private val source = Source(
        id = 4L,
        name = "Diagnostic Fixture",
        kind = SourceKind.XTREAM,
        url = "http://example.invalid:80",
        username = "test",
        password = "test",
    )
    private fun decode(raw: String) =
        XtreamLiveStreamDecoder.decode(
            ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), source,
        ) { id -> "http://example.invalid/live/test/test/$id.m3u8" }

    @Test fun readsExactPlatformJsonWithProviderQuirksAndSkipsOnlyUnknownFields() {
        val raw = """[{stream_id:12,name:'Channel One',category_id:'5',num:'3',tv_archive:1,stream_icon:null,unwanted:[{x:'yes'}]},{stream_id:'14',name:'Channel Two',category_id:8,epg_channel_id:'x'}]"""
        val channels = decode(raw)
        assertEquals(2, channels.size)
        assertEquals("12", channels[0].streamId)
        assertEquals("Channel One", channels[0].name)
        assertEquals("5", channels[0].categoryId)
        assertEquals(3, channels[0].number)
        assertTrue(channels[0].tvArchive)
        assertEquals("14", channels[1].streamId)
        assertEquals("8", channels[1].categoryId)
        assertEquals("x", channels[1].epgChannelId)
    }

    @Test fun canValidateAndImportFullSevenThousandFourHundredNinetyEightItemCatalogue() {
        val raw = buildString {
            append("[")
            repeat(7_498) { i ->
                if (i > 0) append(",")
                append("{\"stream_id\":")
                append(100000 + i)
                append(",\"name\":\"TV")
                append(i)
                append("\",\"category_id\":\"2\",\"num\":")
                append(i + 1)
                append("}")
            }
            append("]")
        }
        val channels = decode(raw)
        assertEquals(7_498, channels.size)
        assertEquals("100000", channels.first().streamId)
        assertEquals("107497", channels.last().streamId)
    }

    @Test fun rejectsTruncationOrTrailingContentWithoutImportingAnySnapshot() {
        for (raw in listOf(
            """[{"stream_id":"10","name":"Sample"}""",
            """[{"stream_id":"10","name":"Sample"}] broken""",
            """{"stream_id":"10","name":"Sample"}""",
        )) {
            assertThrows(XtreamLiveStreamDecoder.LiveDecodeException::class.java) {
                decode(raw)
            }
        }
    }

    @Test fun invalidRecordsAreSkippedWithoutFailingOtherwiseCompleteCatalogue() {
        val channels = decode("""[{"name":"No ID"},{"stream_id":27},{"stream_id":31,"name":"Valid Channel"}]""")
        assertEquals(1, channels.size)
        assertEquals("31", channels.single().streamId)
    }
}
