package app.sokyiptv.core

/**
 * Startup access decision. An unavailable provider is not equivalent to a
 * revoked account, and a never-completed initial login is not offline access.
 */
internal sealed class StartupLoginGate {
    data object Checking : StartupLoginGate()
    data object Ready : StartupLoginGate()
    data class Offline(val warning: String) : StartupLoginGate()
    data class LoginRequired(val sourceId: Long, val explanation: String) : StartupLoginGate()
}
