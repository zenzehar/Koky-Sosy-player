package app.sokyiptv.data.remote

import android.util.JsonReader
import android.util.JsonToken
import app.sokyiptv.data.model.Channel
import app.sokyiptv.data.model.Source
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Live channel import through the EXACT Android framework parser which validated
 * the user's 7,498-channel response in the isolated D3 probe.
 *
 * Crucially, this never reconstructs or reserializes a huge JsonElement tree.
 * The catalogue is only returned after its closing ] AND END_DOCUMENT were read.
 * Parsing failures discard the ENTIRE unsaved snapshot (no partial database import).
 */
internal object XtreamLiveStreamDecoder {
    private const val MAX_ITEMS = 100_000

    fun decode(
        input: InputStream,
        source: Source,
        streamUrl: (String) -> String,
    ): List<Channel> {
        val channels = ArrayList<Channel>()
        var index = 0
        try {
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                require(reader.peek() == JsonToken.BEGIN_ARRAY) {
                    "Live catalogue root is not an array"
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    check(++index <= MAX_ITEMS) { "Live catalogue exceeds channel safety limit" }
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    var streamId: String? = null
                    var name: String? = null
                    var categoryId: String? = null
                    var icon: String? = null
                    var epgId: String? = null
                    var archive: Int? = null
                    var archiveDays: Int? = null
                    var number: Int? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "stream_id" -> streamId = textOrNull(reader)
                            "name" -> name = textOrNull(reader)
                            "category_id" -> categoryId = textOrNull(reader)
                            "stream_icon" -> icon = textOrNull(reader)
                            "epg_channel_id" -> epgId = textOrNull(reader)
                            "tv_archive" -> archive = textOrNull(reader)?.toIntOrNull()
                            "tv_archive_duration" -> archiveDays = textOrNull(reader)?.toIntOrNull()
                            "num" -> number = textOrNull(reader)?.toIntOrNull()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    val validId = streamId?.takeIf { it.isNotBlank() } ?: continue
                    val validName = name?.takeIf { it.isNotBlank() } ?: continue
                    channels.add(
                        Channel(
                            sourceId = source.id,
                            streamId = validId,
                            name = validName,
                            categoryId = categoryId,
                            logoUrl = icon?.takeIf { it.isNotBlank() },
                            epgChannelId = epgId?.takeIf { it.isNotBlank() },
                            tvArchive = archive == 1,
                            tvArchiveDays = archiveDays ?: 0,
                            number = number,
                            streamUrl = streamUrl(validId),
                            sortIndex = number ?: index - 1,
                        )
                    )
                }
                reader.endArray()
                check(reader.peek() == JsonToken.END_DOCUMENT) {
                    "Live catalogue has unconsumed trailing data"
                }
            }
        } catch (e: Exception) {
            // Never quote parser.message: it may contain secret-bearing provider data.
            throw LiveDecodeException(index, e.javaClass.simpleName)
        }
        return channels
    }

    private fun textOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> { reader.skipValue(); null }
    }

    class LiveDecodeException(val itemIndex: Int, val failureType: String) :
        Exception("Live catalogue stream validation failed near item $itemIndex ($failureType).")
}
