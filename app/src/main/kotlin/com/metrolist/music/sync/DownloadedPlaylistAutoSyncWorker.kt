/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.metrolist.music.R
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
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val NOTIFICATION_CHANNEL_ID = "downloaded_playlist_sync"
        private const val FAILURE_NOTIFICATION_ID = 21001
    }

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
        if (!context.isSyncEnabled()) {
            return Result.success()
        }
        if (!context.isInternetConnected()) {
            return retryOrFailWithNotification()
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
            onFailure = { retryOrFailWithNotification() }
        )
    }

    private fun retryOrFailWithNotification(): Result {
        if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) return Result.retry()

        showFailureNotification()
        return Result.failure()
    }

    private fun showFailureNotification() {
        val context = applicationContext
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.downloaded_playlist_sync_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description =
                    context.getString(R.string.downloaded_playlist_sync_notification_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.sync)
            .setContentTitle(context.getString(R.string.downloaded_playlist_sync_failed_title))
            .setContentText(context.getString(R.string.downloaded_playlist_sync_failed_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(FAILURE_NOTIFICATION_ID, notification)
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
