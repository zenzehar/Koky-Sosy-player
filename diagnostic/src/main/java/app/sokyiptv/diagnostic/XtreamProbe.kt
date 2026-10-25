package app.sokyiptv.diagnostic

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Sends Xtream API actions without downloading whole catalogues or printing credentials. */
internal class XtreamProbe {
    data class Result(val action: String, val summary: String, val html: String? = null)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .retryOnConnectionFailure(true).build()
    private val actions = listOf(
        null, "get_live_categories", "get_live_streams", "get_vod_categories",
        "get_vod_streams", "get_series_categories", "get_series"
    )
    fun run(source: ProviderImport.Provider, report: (Result) -> Unit) {
        val root = source.url.trim().trimEnd('/').toHttpUrlOrNull()
        if (root == null || (root.scheme != "http" && root.scheme != "https")) {
            report(Result("configuration", "Invalid provider base URL (not displayed)."))
            return
        }
        if (source.kind != "XTREAM") {
            report(Result("configuration", "This probe only supports Xtream accounts."))
            return
        }
        actions.forEach { action ->
            val label = action ?: "account/authentication"
            val url = root.newBuilder().encodedPath("/player_api.php")
                .addQueryParameter("username", source.username)
                .addQueryParameter("password", source.password)
                .apply { if (action != null) addQueryParameter("action", action) }
                .build()
            val started = System.nanoTime()
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", source.userAgent).get().build()
                client.newCall(request).execute().use { response ->
                    val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    val sample = ByteArrayOutputStream()
                    val cap = 32 * 1024
                    response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(4096)
                        while (sample.size() < cap) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, cap - sample.size()))
                            if (read < 0) break
                            sample.write(buffer, 0, read)
                        }
                    }
                    val raw = sample.toByteArray().toString(Charsets.UTF_8)
                        .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    val contentType = response.header("Content-Type").orEmpty().take(80)
                        .ifBlank { "(missing)" }
                    val changedOrigin = response.request.url.host != url.host ||
                        response.request.url.scheme != url.scheme ||
                        response.request.url.port != url.port
                    val redirect = if (response.priorResponse != null) {
                        "redirected ${if (changedOrigin) "to different origin" else "on same origin"}"
                    } else "not redirected"
                    val html = looksLikeHtml(raw, contentType)
                    val kind = when {
                        html -> "HTML/website response"
                        raw.startsWith("[") -> "JSON array"
                        raw.startsWith("{") -> "JSON object"
                        raw.isBlank() -> "EMPTY response"
                        else -> "Unexpected non-JSON text/binary"
                    }
                    val jsonExtra = if (!html && sample.size() < cap) {
                        runCatching {
                            when {
                                raw.startsWith("[") -> " · array items ${JSONArray(raw).length()}"
                                raw.startsWith("{") -> {
                                    val obj = JSONObject(raw)
                                    if (action == null) {
                                        val info = obj.optJSONObject("user_info")
                                        " · account auth=${info?.opt("auth") ?: "absent"}" +
                                            " · status=${safeStatus(info?.optString("status"))}"
                                    } else " · JSON object keys ${obj.length()}"
                                }
                                else -> ""
                            }
                        }.getOrDefault(" · JSON parse failed")
                    } else if (!html && (raw.startsWith("[") || raw.startsWith("{"))) {
                        " · first 32 KiB only (not a full validation)"
                    } else ""
                    val info = "HTTP ${response.code}, ${millis}ms, type=$contentType, " +
                        "$redirect, sample=${sample.size()} bytes, $kind$jsonExtra"
                    val safeHtml = if (html) sanitise(raw, source) else null
                    val title = if (html) extractTitle(safeHtml.orEmpty()) else ""
                    report(Result(label, info +
                        if (title.isNotBlank()) " · HTML title: $title" else "", safeHtml))
                }
            } catch (e: Exception) {
                val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                // Exception messages can contain credential-bearing URLs. Never print them.
                val category = when (e) {
                    is java.net.UnknownHostException -> "DNS resolution failed"
                    is javax.net.ssl.SSLException -> "TLS/certificate failure"
                    is java.net.ConnectException -> "Connection refused or unreachable"
                    is SocketTimeoutException -> "Socket timeout"
                    is java.io.InterruptedIOException -> "Network timeout/cancelled"
                    else -> "Transport failure (${e.javaClass.simpleName.take(40)})"
                }
                report(Result(label, "$category after ${millis}ms; secrets omitted"))
            }
        }
    }
    private fun safeStatus(value: String?): String =
        value.orEmpty().take(30).filter { it.isLetterOrDigit() || it == ' ' }.ifBlank { "absent" }

    private fun looksLikeHtml(text: String, type: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return type.lowercase(Locale.ROOT).contains("html") ||
            lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
            lower.contains("<head") || lower.contains("<title") ||
            lower.contains("<body") || lower.contains("<form")
    }
    private fun sanitise(raw: String, source: ProviderImport.Provider): String {
        var safe = raw.take(24 * 1024)
        for (secret in listOf(source.password, source.username, source.url).filter { it.isNotBlank() }) {
            safe = safe.replace(secret, "[REDACTED]", ignoreCase = true)
            val encoded = java.net.URLEncoder.encode(secret, "UTF-8")
            if (encoded != secret) safe = safe.replace(encoded, "[REDACTED]", ignoreCase = true)
        }
        safe = safe.replace(Regex("""(?i)\b(?:https?://|rtsp://)[^\s"'<>]+"""), "[URL REDACTED]")
        safe = safe.replace(Regex("""(?i)\b(?:password|passwd|pass|username|user|token|api_key|secret|authorization|session|key)\s*[=:]\s*[^&\s"'<>]+"""), "[CREDENTIAL FIELD REDACTED]")
        safe = safe.replace(Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""), "[EMAIL REDACTED]")
        return safe
    }
    private fun extractTitle(html: String): String =
        Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(100).orEmpty()
}
