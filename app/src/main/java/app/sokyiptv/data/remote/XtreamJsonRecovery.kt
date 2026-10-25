package app.sokyiptv.data.remote

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fallback for non-standard Xtream catalogue JSON. The normal Kotlinx decoder is
 * always tried first by XtreamApi. Diagnostic D3 proved Android's *platform*
 * JsonReader can consume the actual 7,498-channel response rejected by Kotlinx.
 *
 * This intentionally uses android.util.JsonReader, NOT Gson's similar-looking
 * JsonReader (whose fallback in v48 failed on the same provider response).
 * A failed or partially consumed document is discarded in its entirety.
 */
internal object XtreamJsonRecovery {
    private const val MAX_INPUT_CHARS = 12_000_000
    private const val MAX_DEPTH = 90
    private const val MAX_NODES = 1_500_000

    fun recover(raw: String, decoder: Json, requireArray: Boolean): JsonElement? {
        if (raw.length > MAX_INPUT_CHARS) return null
        val trimmed = raw.trimEnd()
        // Provider occasionally omits the final ] after a complete last object.
        // This repair is acceptable ONLY after the ENTIRE corrected JSON parses.
        if (requireArray && trimmed.startsWith("[") && trimmed.endsWith("}")) {
            runCatching { decoder.parseToJsonElement("$trimmed]") }
                .getOrNull()?.takeIf { it is JsonArray }?.let { return it }
        }
        return runCatching {
            JsonReader(StringReader(raw)).use { reader ->
                reader.isLenient = true
                val counter = NodeCounter()
                val value = readValue(reader, decoder, counter, 0)
                check(reader.peek() == JsonToken.END_DOCUMENT) {
                    "Unexpected trailing catalogue content"
                }
                check(!requireArray || value is JsonArray) {
                    "Catalogue root must be an array"
                }
                value
            }
        }.getOrNull()
    }

    private class NodeCounter {
        var count = 0
        fun next() {
            check(++count <= MAX_NODES) { "Catalogue too large" }
        }
    }

    private fun readValue(
        reader: JsonReader,
        decoder: Json,
        counter: NodeCounter,
        depth: Int,
    ): JsonElement {
        check(depth < MAX_DEPTH) { "Catalogue too deeply nested" }
        counter.next()
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val items = ArrayList<JsonElement>()
                while (reader.hasNext()) {
                    items.add(readValue(reader, decoder, counter, depth + 1))
                }
                reader.endArray()
                JsonArray(items)
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val fields = LinkedHashMap<String, JsonElement>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    fields[name] = readValue(reader, decoder, counter, depth + 1)
                }
                reader.endObject()
                JsonObject(fields)
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val rawNumber = reader.nextString()
                // Preserve numeric JSON values when valid; unusual vendor number
                // tokens are preserved as strings, which Xtream's accessors support.
                runCatching { decoder.parseToJsonElement(rawNumber) }
                    .getOrElse { JsonPrimitive(rawNumber) }
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull
            }
            else -> error("Unsupported Xtream catalogue token")
        }
    }
}
