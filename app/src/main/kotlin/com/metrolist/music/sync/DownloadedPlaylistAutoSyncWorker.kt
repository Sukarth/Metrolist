/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncEnabledKey
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncManagedSongIdsKey
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncPlaylistIdsKey
import com.metrolist.music.extensions.isInternetConnected
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

class DownloadedPlaylistAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun syncUtils(): SyncUtils
        fun downloadUtil(): DownloadUtil
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(context, WorkerEntryPoint::class.java)
        val syncUtils = entryPoint.syncUtils()
        val database = entryPoint.downloadUtil().database

        if (!context.dataStore.get(DownloadedPlaylistAutoSyncEnabledKey, false)) {
            return Result.success()
        }
        if (!context.isSyncEnabled() || !context.isInternetConnected()) {
            return Result.success()
        }

        val trackedPlaylistIds = DownloadedPlaylistAutoSyncScheduler.readCsvSet(
            context.dataStore.get(DownloadedPlaylistAutoSyncPlaylistIdsKey, "")
        ).toMutableSet()
        if (trackedPlaylistIds.isEmpty()) {
            cleanupManagedDownloads(emptySet())
            return Result.success()
        }

        return runCatching {
            val requiredSongIds = linkedSetOf<String>()
            val stalePlaylistIds = mutableSetOf<String>()

            trackedPlaylistIds.forEach { playlistId ->
                val playlist = database.playlistBlocking(playlistId)
                val browseId = playlist?.playlist?.browseId
                if (browseId == null) {
                    stalePlaylistIds.add(playlistId)
                    return@forEach
                }

                syncUtils.syncSinglePlaylistSuspend(
                    browseId = browseId,
                    playlistId = playlistId,
                    preserveDownloadedSongs = false
                )

                val songs = database.playlistSongsBlocking(playlistId)
                songs.forEach { playlistSong ->
                    requiredSongIds.add(playlistSong.song.id)
                    val request = DownloadRequest.Builder(playlistSong.song.id, playlistSong.song.id.toUri())
                        .setCustomCacheKey(playlistSong.song.id)
                        .setData(playlistSong.song.song.title.toByteArray())
                        .build()
                    DownloadService.sendAddDownload(
                        context,
                        ExoDownloadService::class.java,
                        request,
                        false,
                    )
                }
            }

            if (stalePlaylistIds.isNotEmpty()) {
                trackedPlaylistIds.removeAll(stalePlaylistIds)
            }

            cleanupManagedDownloads(requiredSongIds)

            context.dataStore.edit { settings ->
                settings[DownloadedPlaylistAutoSyncPlaylistIdsKey] =
                    DownloadedPlaylistAutoSyncScheduler.writeCsvSet(trackedPlaylistIds)
            }
        }.onFailure { error ->
            Timber.e(error, "DownloadedPlaylistAutoSyncWorker failed")
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    private suspend fun cleanupManagedDownloads(requiredSongIds: Set<String>) {
        val context = applicationContext
        val previousManagedSongIds = DownloadedPlaylistAutoSyncScheduler.readCsvSet(
            context.dataStore.get(DownloadedPlaylistAutoSyncManagedSongIdsKey, "")
        )
        val staleSongIds = previousManagedSongIds - requiredSongIds

        staleSongIds.forEach { songId ->
            DownloadService.sendRemoveDownload(
                context,
                ExoDownloadService::class.java,
                songId,
                false,
            )
        }

        context.dataStore.edit { settings ->
            settings[DownloadedPlaylistAutoSyncManagedSongIdsKey] =
                DownloadedPlaylistAutoSyncScheduler.writeCsvSet(requiredSongIds)
        }
    }
}
