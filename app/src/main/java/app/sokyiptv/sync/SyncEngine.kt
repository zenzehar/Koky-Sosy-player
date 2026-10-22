/*
 * This file is part of SokyIPTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.sokyiptv.sync

import app.sokyiptv.sync.cloud.CloudMediaKeys
import app.sokyiptv.core.ServiceLocator
import app.sokyiptv.data.model.PlaybackPosition
import app.sokyiptv.data.model.Profile
import app.sokyiptv.data.model.Recording
import app.sokyiptv.data.model.RecordingStatus
import app.sokyiptv.recording.SmbClient
import kotlinx.serialization.json.Json

/**
 * Reads local state into a [SyncBundle] and applies a received one. The single place that knows
 * how SokyIPTV's tables map onto the portable, cross-device bundle, so the LAN server/client and the
 * NAS sync all agree on what travels and how it merges.
 *
 * Merge policy (see [SyncBundle]): watch positions are two-way newest-wins; curation (profiles,
 * favourites, hidden channels, hidden categories) and NAS recordings are applied **additively** —
 * we spread what a peer has without deleting anything locally. That keeps a sync from ever wiping a
 * favourite you set on one box, un-hiding the structural separator rows the importer hides, or
 * dropping a recording that only one device knows about.
 */
class SyncEngine(private val graph: ServiceLocator.Graph) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What a single [apply] actually changed — summed across peers for the NAS "Synced N" line. */
    data class MergeResult(
        val positions: Int = 0,
        val favourites: Int = 0,
        val hidden: Int = 0,
        val recordings: Int = 0,
    ) {
        val total: Int get() = positions + favourites + hidden + recordings

        operator fun plus(other: MergeResult): MergeResult = MergeResult(
            positions = positions + other.positions,
            favourites = favourites + other.favourites,
            hidden = hidden + other.hidden,
            recordings = recordings + other.recordings,
        )
    }

    suspend fun gather(): SyncBundle {
        // Profile propagation is deferred. All transports use this device's
        // currently selected viewing profile and one newest position per title.
        val positions = decode(WatchHistorySync.snapshot(graph)).positions
        // Only finished recordings that live on the shared NAS travel — a device-local (file://)
        // capture can't be opened from another box, so sharing its metadata would just create a
        // library row that fails to play everywhere but here.
        val recordings = graph.recordingRepository.all()
            .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
            .map { r ->
                SyncRecording(
                    channelName = r.channelName,
                    logoUrl = r.logoUrl,
                    title = r.title,
                    description = r.description,
                    filePath = r.filePath,
                    streamUrl = r.streamUrl,
                    userAgent = r.userAgent,
                    scheduledStartMillis = r.scheduledStartMillis,
                    scheduledEndMillis = r.scheduledEndMillis,
                    startedAtMillis = r.startedAtMillis,
                    endedAtMillis = r.endedAtMillis,
                    sizeBytes = r.sizeBytes,
                )
            }
        return SyncBundle(
            generatedAtMillis = System.currentTimeMillis(),
            profiles = emptyList(),
            positions = positions,
            favouriteChannels = graph.catalogRepository.favouriteChannelUrls(),
            hiddenChannels = graph.catalogRepository.hiddenChannelUrls(),
            favouriteMovies = graph.catalogRepository.favouriteMovieUrls(),
            favouriteSeries = graph.catalogRepository.favouriteSeriesIds(),
            hiddenCategories = graph.settings.hiddenCategories.value.toList(),
            recordings = recordings,
        )
    }

    fun encode(bundle: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), bundle)

    fun decode(bundleJson: String): SyncBundle =
        json.decodeFromString(SyncBundle.serializer(), bundleJson)

    /** Applies a received bundle. Returns what changed, so callers can report "Synced N items". */
    suspend fun apply(bundle: SyncBundle): MergeResult {
        // No profile creation, copying or name matching in this release.
        // Curation — additive union, keyed by stable identifiers. Count only genuinely new keys
        // (ones we hadn't already curated) so the "Synced N" line means "new to this device".
        val localFavChannels = graph.catalogRepository.favouriteChannelUrls().toHashSet()
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls().toHashSet()
        val localFavMovies = graph.catalogRepository.favouriteMovieUrls().toHashSet()
        val localFavSeries = graph.catalogRepository.favouriteSeriesIds().toHashSet()

        var favourites = 0
        var hidden = 0
        for (url in bundle.favouriteChannels) {
            graph.catalogRepository.markChannelFavouriteByUrl(url)
            if (localFavChannels.add(url)) favourites++
        }
        for (url in bundle.hiddenChannels) {
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(url)) hidden++
        }
        for (url in bundle.favouriteMovies) {
            graph.catalogRepository.markMovieFavouriteByUrl(url)
            if (localFavMovies.add(url)) favourites++
        }
        for (seriesId in bundle.favouriteSeries) {
            graph.catalogRepository.markSeriesFavouriteBySeriesId(seriesId)
            if (localFavSeries.add(seriesId)) favourites++
        }
        if (bundle.hiddenCategories.isNotEmpty()) {
            graph.settings.setHiddenCategories(
                graph.settings.hiddenCategories.value + bundle.hiddenCategories,
            )
        }

        // NAS recordings — insert any smb:// recording we don't already have, keyed by its locator.
        // Never touch an existing row; a peer's copy and ours are the same file on the same NAS.
        var recordings = 0
        for (rec in bundle.recordings) {
            if (!SmbClient.isSmb(rec.filePath)) continue
            if (graph.recordingRepository.byFilePath(rec.filePath) != null) continue
            val inserted = runCatching {
                graph.recordingRepository.insert(
                    Recording(
                        // Ids from the source device mean nothing here; the row is self-contained.
                        channelId = 0,
                        sourceId = 0,
                        channelName = rec.channelName,
                        logoUrl = rec.logoUrl,
                        title = rec.title,
                        description = rec.description,
                        filePath = rec.filePath,
                        streamUrl = rec.streamUrl,
                        userAgent = rec.userAgent,
                        scheduledStartMillis = rec.scheduledStartMillis,
                        scheduledEndMillis = rec.scheduledEndMillis,
                        startedAtMillis = rec.startedAtMillis,
                        endedAtMillis = rec.endedAtMillis,
                        status = RecordingStatus.COMPLETED,
                        sizeBytes = rec.sizeBytes,
                    ),
                )
            }.isSuccess
            if (inserted) recordings++
        }

        val positions = WatchHistorySync.apply(graph, bundle).updated
        return MergeResult(
            positions = positions,
            favourites = favourites,
            hidden = hidden,
            recordings = recordings,
        )
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }
}
