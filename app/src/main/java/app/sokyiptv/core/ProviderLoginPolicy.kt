package app.sokyiptv.core

import android.content.Context
import app.sokyiptv.data.model.Source
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * Distinguishes explicit rejection from transient network/server outages.
 * NEVER infer "wrong password" merely from an unreachable provider.
 */
internal object ProviderLoginPolicy {
    enum class Reason { REJECTED, UNAVAILABLE, CONFIGURATION }

    data class Failure(val reason: Reason, val message: String)

    fun classify(error: Throwable?): Failure {
        val stack = generateSequence(error) { it.cause }.take(6).toList()
        val raw = stack.joinToString(" ") { it.message.orEmpty() }
        val text = raw.lowercase()
        val rejected = listOf("username or password rejected", "account is disabled",
            "account is banned", "account is expired", "subscription has expired",
            "this account is", "http 401", "http 403")
        if (rejected.any(text::contains)) return Failure(
            Reason.REJECTED,
            "The provider rejected this account or its access. Check the username, password, " +
                "subscription status and provider User-Agent in Advanced settings."
        )
        if (stack.any { it is UnknownHostException }) return Failure(
            Reason.UNAVAILABLE,
            "Internet or provider DNS is unavailable. Check the connection and server address."
        )
        if (stack.any { it is SocketTimeoutException || it is ConnectException ||
                    it is SSLException } ||
            listOf("timeout", "timed out", "connection refused",
                "connection reset", "failed to connect", "unreachable",
                "http 429", "http 500", "http 502", "http 503", "http 504")
                .any(text::contains)) return Failure(
            Reason.UNAVAILABLE,
            "The internet or provider server is temporarily unavailable. Check connectivity " +
                "and try again; saved channels can be used only after a prior verified login."
        )
        if (listOf("not a valid", "no xtream api", "http 404", "invalid",
            "did not confirm a valid account", "did not return account information",
            "playlist is unavailable").any(text::contains)) return Failure(
            Reason.CONFIGURATION,
            "The provider did not return a valid login response. Check the server URL, " +
                "port and account details."
        )
        // Unknown HTTP/provider exceptions are unverified rather than
        // silently treated as authentication success.
        return Failure(
            Reason.UNAVAILABLE,
            "Provider validation could not finish. The server may be unavailable or its " +
                "response may have changed. Check your connection and try again."
        )
    }

    fun allowCachedAccess(previouslyVerified: Boolean, reason: Reason): Boolean =
        previouslyVerified && reason == Reason.UNAVAILABLE
}

/**
 * Store only an SHA-256 digest of source identity + credentials; no secrets or
 * raw provider addresses are logged. Existing v79 accounts with a successful
 * source-specific catalogue stamp are treated as previously verified.
 */
internal class ProviderLoginTrust(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "soky_verified_provider_login_v1", Context.MODE_PRIVATE)

    private fun digest(source: Source): String {
        val identity = listOf(source.kind.name, source.url.trim().trimEnd('/'),
            source.username.orEmpty(), source.password.orEmpty(),
            source.macAddress.orEmpty()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun markVerified(source: Source) {
        if (source.id <= 0L) return
        prefs.edit().putString("source_${source.id}", digest(source)).apply()
    }

    fun isVerified(source: Source, hasLegacySuccess: Boolean): Boolean {
        if (source.id <= 0L) return false
        val stored = prefs.getString("source_${source.id}", null)
        return if (stored != null) stored == digest(source) else hasLegacySuccess
    }
}
