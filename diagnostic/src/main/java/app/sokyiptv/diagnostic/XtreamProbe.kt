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
        .callTimeout(100, TimeUnit.SECONDS)
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
                    val contentType = response.header("Content-Type").orEmpty().take(80)
                        .ifBlank { "(missing)" }
                    val inspection = response.body?.byteStream()?.use {
                        CatalogueJsonInspector.inspect(it, contentType)
                    } ?: CatalogueJsonInspector.Inspection("Empty HTTP response body")
                    val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    val changedOrigin = response.request.url.host != url.host ||
                        response.request.url.scheme != url.scheme ||
                        response.request.url.port != url.port
                    val redirect = if (response.priorResponse != null) {
                        "redirected ${if (changedOrigin) "to different origin" else "on same origin"}"
                    } else "not redirected"
                    val info = "HTTP ${response.code}, ${millis}ms, type=$contentType, " +
                        "$redirect, ${inspection.detail}"
                    val safeHtml = inspection.html?.let { sanitise(it, source) }
                    val title = safeHtml?.let { extractTitle(it) }.orEmpty()
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
