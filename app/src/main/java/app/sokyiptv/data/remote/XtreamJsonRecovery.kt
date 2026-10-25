package app.sokyiptv.data.remote

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Conservative compatibility fallback for non-standard Xtream catalogue JSON.
 *
 * Production parser always runs FIRST. This is only used after a full response
 * fails to decode; no best-effort partial catalogue is ever imported.
 * The lenient token reader canonicalises unescaped control chars, stray commas
 * and related panel quirks into valid JSON before revalidating with Kotlinx.
 */
internal object XtreamJsonRecovery {
    private const val MAX_INPUT_CHARS = 12_000_000
    private const val MAX_NESTING = 80

    fun recover(raw: String, decoder: Json, requireArray: Boolean): JsonElement? {
        if (raw.length > MAX_INPUT_CHARS) return null
        val stripped = raw.trimEnd()
        // Some Xtream panels omit the final ] after the final category object.
        // Only accept the repair after a SECOND, successful Kotlinx full parse.
        if (requireArray && stripped.startsWith("[") && stripped.endsWith("}")) {
            runCatching { decoder.parseToJsonElement("$stripped]") }
                .getOrNull()?.takeIf { it is JsonArray }?.let { return it }
        }
        return runCatching {
            val input = JsonReader(StringReader(raw)).apply { isLenient = true }
            val output = StringWriter(raw.length.coerceAtMost(4_000_000))
            val writer = JsonWriter(output).apply {
                isLenient = true
                serializeNulls = true
            }
            writeValue(input, writer, 0)
            require(input.peek() == JsonToken.END_DOCUMENT) {
                "Trailing content after catalogue"
            }
            writer.flush()
            val result = decoder.parseToJsonElement(output.toString())
            require(!requireArray || result is JsonArray) { "Invalid catalogue root" }
            result
        }.getOrNull()
    }

    private fun writeValue(input: JsonReader, output: JsonWriter, depth: Int) {
        require(depth < MAX_NESTING) { "JSON nesting exceeds safety limit" }
        when (input.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                input.beginArray()
                output.beginArray()
                while (input.hasNext()) writeValue(input, output, depth + 1)
                input.endArray()
                output.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                input.beginObject()
                output.beginObject()
                while (input.hasNext()) {
                    output.name(input.nextName())
                    writeValue(input, output, depth + 1)
                }
                input.endObject()
                output.endObject()
            }
            JsonToken.STRING -> output.value(input.nextString())
            JsonToken.NUMBER -> {
                // Do not emit NaN, Infinity or malformed number tokens.
                val number = input.nextString()
                val value = number.toBigDecimalOrNull()
                if (value != null) output.value(value) else output.value(number)
            }
            JsonToken.BOOLEAN -> output.value(input.nextBoolean())
            JsonToken.NULL -> {
                input.nextNull()
                output.nullValue()
            }
            else -> error("Unsupported catalogue token")
        }
    }
}
