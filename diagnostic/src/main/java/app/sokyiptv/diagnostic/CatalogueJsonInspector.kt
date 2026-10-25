package app.sokyiptv.diagnostic

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * D3: validate the ENTIRE server response, not just a 32KiB JSON-looking prefix.
 * For small payloads use the exact JSON configuration used by Soky's XtreamApi.
 * For larger payloads, parse a bounded stream without building a huge catalogue tree.
 * No provider response bodies are persisted or included in the diagnostic report.
 */
internal object CatalogueJsonInspector {
    private const val SMALL_LIMIT = 2 * 1024 * 1024
    private const val FULL_LIMIT = 64L * 1024 * 1024
    private val appJson = Json { ignoreUnknownKeys = true; isLenient = true }
    data class Inspection(val detail: String, val html: String? = null)

    fun inspect(stream: InputStream, contentType: String): Inspection {
        val buffered = BufferedInputStream(stream, 32 * 1024)
        buffered.mark(SMALL_LIMIT + 1)
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var complete = false
        while (bytes.size() <= SMALL_LIMIT) {
            val remaining = SMALL_LIMIT + 1 - bytes.size()
            if (remaining <= 0) break
            val count = buffered.read(chunk, 0, minOf(chunk.size, remaining))
            if (count < 0) { complete = true; break }
            bytes.write(chunk, 0, count)
        }
        val prefix = bytes.toByteArray()
        val header = prefix.copyOfRange(0, minOf(prefix.size, 3_000))
            .toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val lower = header.lowercase(Locale.ROOT)
        val html = contentType.contains("html", ignoreCase = true) ||
            lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
            lower.contains("<head") || lower.contains("<body") || lower.contains("<title")
        if (html) return Inspection(
            "HTML response, sampled ${prefix.size} bytes; no JSON catalogue",
            prefix.copyOf(minOf(24 * 1024, prefix.size)).toString(Charsets.UTF_8),
        )
        if (complete) {
            val raw = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF')
            if (raw.isBlank()) return Inspection("Empty response (0 JSON items)")
            return try {
                val value = appJson.parseToJsonElement(raw)
                val count = when (value) {
                    is JsonArray -> "array items=${value.size}"
                    is JsonObject -> "object keys=${value.size}"
                    else -> "unexpected JSON root type"
                }
                val android = runCatching {
                    if (raw.trimStart().startsWith("[")) org.json.JSONArray(raw).length()
                    else -1
                }.fold(
                    onSuccess = { if (it >= 0) "Android org.json also accepts" else "N/A" },
                    onFailure = { "Android org.json REJECTS (parser disagreement)" }
                )
                Inspection("FULL response ${prefix.size} bytes; Soky's kotlinx parser ACCEPTS; $count; $android")
            } catch (e: Exception) {
                // Do not expose exception.message; a parser error can quote private data.
                val offset = Regex("""(?i)\b(?:offset|position)\s*[:= ]\s*(\d+)""")
                    .find(e.message.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
                Inspection("FULL response ${prefix.size} bytes; Soky's kotlinx parser REJECTS" +
                    (if (offset.isNotBlank()) " near character $offset" else "") +
                    "; failure=${e.javaClass.simpleName.take(50)}")
            }
        }

        // Potentially huge IPTV catalogs: reset to beginning and consume JSON tokens
        // instead of buffering the entire document or storing a sensitive raw catalogue.
        buffered.reset()
        val counted = CountLimitedStream(buffered, FULL_LIMIT)
        return try {
            val reader = JsonReader(InputStreamReader(counted, Charsets.UTF_8))
            reader.isLenient = true
            val top = reader.peek()
            var items = 0
            if (top == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                while (reader.hasNext()) {
                    consume(reader, 1)
                    items++
                    if (items > 3_000_000) throw IOException("Too many catalogue items")
                }
                reader.endArray()
            } else if (top == JsonToken.BEGIN_OBJECT) {
                consume(reader, 0)
            } else {
                throw IOException("Unexpected JSON root")
            }
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing JSON content")
            Inspection("FULL response ${counted.total} bytes; streaming JSON validator ACCEPTS" +
                (if (top == JsonToken.BEGIN_ARRAY) "; array items=$items" else "; object root") +
                "; large-body validation (not exactly Soky's in-memory parser)")
        } catch (e: Exception) {
            val clipped = counted.total >= FULL_LIMIT
            Inspection(
                if (clipped) "Response exceeds 64MiB safety cap; cannot fully validate"
                else "Streaming JSON validator REJECTS after approximately ${counted.total} bytes" +
                    "; failure=${e.javaClass.simpleName.take(50)} (no response text retained)"
            )
        }
    }

    private fun consume(reader: JsonReader, depth: Int) {
        if (depth >= 90) throw IOException("Excessively nested catalogue")
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    reader.nextName() // Discard fields; never log provider metadata.
                    consume(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) consume(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> { reader.nextString() }
            JsonToken.BOOLEAN -> { reader.nextBoolean() }
            JsonToken.NULL -> { reader.nextNull() }
            else -> throw IOException("Invalid JSON token")
        }
    }

    private class CountLimitedStream(private val delegate: InputStream, private val limit: Long): InputStream() {
        var total = 0L
            private set
        override fun read(): Int {
            if (total >= limit) throw IOException("Diagnostic size limit reached")
            val b = delegate.read()
            if (b >= 0) total++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (total >= limit) throw IOException("Diagnostic size limit reached")
            val read = delegate.read(b, off, minOf(len.toLong(), limit - total).toInt())
            if (read > 0) total += read
            return read
        }
    }
}
