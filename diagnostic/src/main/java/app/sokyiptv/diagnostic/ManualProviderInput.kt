package app.sokyiptv.diagnostic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Converts manual Xtream credentials into a temporary in-memory account, not a stored login. */
internal object ManualProviderInput {
    fun create(
        server: String,
        username: String,
        password: String,
        userAgent: String,
    ): ProviderImport.Provider {
        val supplied = server.trim()
        require(supplied.isNotEmpty()) { "Enter your server address and port." }
        val withScheme = if (supplied.startsWith("http://", true) ||
            supplied.startsWith("https://", true)) supplied
        else "http://$supplied"
        val parsed = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid HTTP or HTTPS server address.")
        require(parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null) {
            "Enter only the server address and port; credentials belong in separate fields."
        }
        require(parsed.encodedPath == "/" ||
            parsed.encodedPath in setOf("/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php")) {
            "Enter the Xtream server host and port, not a playlist or web page path."
        }
        val user = username.trim()
        val pass = password
        require(user.isNotEmpty() && user.length <= 256 &&
            pass.isNotEmpty() && pass.length <= 256) {
            "Enter a valid Xtream username and password."
        }
        val ua = userAgent.trim().ifEmpty { "SokyIPTV/0.1 (Android)" }
        require(ua.length <= 200 && ua.none { it == '\r' || it == '\n' }) {
            "Invalid User-Agent value."
        }
        val url = parsed.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        return ProviderImport.Provider(
            name = "Manual Xtream provider", kind = "XTREAM",
            url = url, username = user, password = pass, userAgent = ua,
        )
    }
}
