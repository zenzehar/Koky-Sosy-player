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

/**
 * Login is completed by a positive provider response and durable source save.
 * Catalogue sync is deliberately not an input; it runs after Home navigation.
 */
internal object LoginAdmissionPolicy {
    fun canEnterHome(authenticated: Boolean, savedSourceId: Long): Boolean =
        authenticated && savedSourceId > 0L

    fun canReuseSession(
        sourceId: Long, authenticatedSourceIds: Set<Long>, credentialsMatch: Boolean,
    ): Boolean = sourceId > 0L && credentialsMatch && sourceId in authenticatedSourceIds
}
