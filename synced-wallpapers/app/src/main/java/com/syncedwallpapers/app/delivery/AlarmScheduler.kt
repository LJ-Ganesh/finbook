package com.syncedwallpapers.app.delivery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.syncedwallpapers.app.core.time.ServerClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AlarmKind { START, END }

/**
 * Exact alarms (setExactAndAllowWhileIdle) when the user allowed them; otherwise a
 * WorkManager job at the target time (inexact: Doze may delay it by minutes, which
 * the spec accepts; the engine never applies outside the window either way).
 * Times are true (server) instants, converted to the device clock with the measured skew.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: ServerClock,
    private val workManager: WorkManager,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    fun schedule(proposalId: String, kind: AlarmKind, at: Instant) {
        val deviceAt = clock.toDeviceTime(at)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deviceAt.toEpochMilli(), pendingIntent(proposalId, kind))
            workManager.cancelUniqueWork(fallbackName(proposalId, kind))
        } else {
            val delay = Duration.between(clock.deviceNow(), deviceAt).toMillis().coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<AlarmWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(AlarmWorker.KEY_ID to proposalId, AlarmWorker.KEY_KIND to kind.name))
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(fallbackName(proposalId, kind), ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancel(proposalId: String) {
        AlarmKind.entries.forEach { kind ->
            alarmManager.cancel(pendingIntent(proposalId, kind))
            workManager.cancelUniqueWork(fallbackName(proposalId, kind))
        }
    }

    private fun pendingIntent(proposalId: String, kind: AlarmKind): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction("com.syncedwallpapers.ALARM.${kind.name}")
            .setData(android.net.Uri.parse("sw://alarm/${kind.name}/$proposalId")) // unique per schedule+kind
            .putExtra(AlarmWorker.KEY_ID, proposalId)
            .putExtra(AlarmWorker.KEY_KIND, kind.name)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun fallbackName(id: String, kind: AlarmKind) = "alarm-fallback-$kind-$id"

    companion object { const val TAG = "sw-alarm" }
}
