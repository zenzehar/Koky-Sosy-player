package app.sokyiptv.ui.channels

/**
 * One-shot in-memory handoff from fullscreen Live to the guide.
 * This deliberately doesn't survive process death: returning from a killed player
 * should not unexpectedly start loud preview audio on the next app launch.
 */
object LiveReturnState {
    private var pendingChannelId: Long? = null

    @Synchronized
    fun handOff(id: Long?) { pendingChannelId = id }

    @Synchronized
    fun consume(): Long? = pendingChannelId.also { pendingChannelId = null }
}
