/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncEnabledKey
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncIntervalHoursKey
import com.metrolist.music.constants.DownloadedPlaylistAutoSyncPlaylistIdsKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import java.util.concurrent.TimeUnit

object DownloadedPlaylistAutoSyncScheduler {
    const val DEFAULT_INTERVAL_HOURS = 6
    private const val PERIODIC_WORK_NAME = "downloaded_playlist_auto_sync_periodic"
    private const val IMMEDIATE_WORK_NAME = "downloaded_playlist_auto_sync_immediate"
    val intervalOptionsHours = listOf(2, 6, 12, 24)

    suspend fun registerPlaylistForAutoSync(context: Context, playlistId: String) {
        context.dataStore.edit { settings ->
            val currentIds = readCsvSet(settings[DownloadedPlaylistAutoSyncPlaylistIdsKey])
            settings[DownloadedPlaylistAutoSyncPlaylistIdsKey] = writeCsvSet(currentIds + playlistId)
        }
        syncScheduleFromSettings(context)
        enqueueImmediateSync(context)
    }

    suspend fun unregisterPlaylistForAutoSync(context: Context, playlistId: String) {
        context.dataStore.edit { settings ->
            val currentIds = readCsvSet(settings[DownloadedPlaylistAutoSyncPlaylistIdsKey])
            settings[DownloadedPlaylistAutoSyncPlaylistIdsKey] = writeCsvSet(currentIds - playlistId)
        }
        syncScheduleFromSettings(context)
        enqueueImmediateSync(context)
    }

    suspend fun syncScheduleFromSettings(context: Context) {
        val enabled = context.dataStore.get(DownloadedPlaylistAutoSyncEnabledKey, false)
        val ytmSyncEnabled = context.dataStore.get(YtmSyncKey, true)
        val trackedPlaylistIds = readCsvSet(context.dataStore.get(DownloadedPlaylistAutoSyncPlaylistIdsKey, ""))
        val intervalHours = context.dataStore.get(
            DownloadedPlaylistAutoSyncIntervalHoursKey,
            DEFAULT_INTERVAL_HOURS
        ).coerceAtLeast(2)

        val workManager = WorkManager.getInstance(context)
        if (!enabled || !ytmSyncEnabled || trackedPlaylistIds.isEmpty()) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
            return
        }

        val periodicWork = PeriodicWorkRequestBuilder<DownloadedPlaylistAutoSyncWorker>(
            intervalHours.toLong(),
            TimeUnit.HOURS,
        )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )
    }

    fun enqueueImmediateSync(context: Context) {
        val immediateRequest = OneTimeWorkRequestBuilder<DownloadedPlaylistAutoSyncWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateRequest
        )
    }

    suspend fun enqueueImmediateSyncIfEligible(context: Context, playlistId: String? = null) {
        val enabled = context.dataStore.get(DownloadedPlaylistAutoSyncEnabledKey, false)
        val ytmSyncEnabled = context.dataStore.get(YtmSyncKey, true)
        val trackedPlaylistIds = readCsvSet(context.dataStore.get(DownloadedPlaylistAutoSyncPlaylistIdsKey, ""))
        if (!enabled || !ytmSyncEnabled || trackedPlaylistIds.isEmpty()) return
        if (playlistId != null && playlistId !in trackedPlaylistIds) return
        enqueueImmediateSync(context)
    }

    fun readCsvSet(raw: String?): Set<String> =
        raw.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun writeCsvSet(values: Set<String>): String =
        values.filter { it.isNotBlank() }
            .sorted()
            .joinToString(",")
}
