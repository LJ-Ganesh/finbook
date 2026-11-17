package com.syncedwallpapers.app.delivery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.syncedwallpapers.app.R
import com.syncedwallpapers.app.notifications.Channels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues the background jobs of the delivery engine. */
@Singleton
class DeliveryWork @Inject constructor(private val workManager: WorkManager) {

    /** Reconcile now (app open, push, system events). Expedited, network optional. */
    fun reconcileNow() {
        val req = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("reconcile-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Every 6 hours, as a safety net when pushes are late or lost. */
    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<ReconcileWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork("reconcile-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Download with a network constraint and exponential retry (until the window ends). */
    fun enqueueDownload(proposalId: String) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(DownloadWorker.KEY_ID to proposalId))
            .build()
        workManager.enqueueUniqueWork("download-$proposalId", ExistingWorkPolicy.KEEP, req)
    }

    fun runAlarm(proposalId: String, kind: String) {
        val req = OneTimeWorkRequestBuilder<AlarmWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(AlarmWorker.KEY_ID to proposalId, AlarmWorker.KEY_KIND to kind))
            .build()
        workManager.enqueueUniqueWork("alarm-$kind-$proposalId", ExistingWorkPolicy.REPLACE, req)
    }
}

private fun syncForegroundInfo(context: Context): ForegroundInfo {
    // Only shown when Android runs expedited work as a foreground service (API < 31).
    val n = NotificationCompat.Builder(context, Channels.SYNC)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Updating wallpapers")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(7001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(7001, n)
}

@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: DeliveryEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        engine.reconcile()
        return Result.success()
    }
    override suspend fun getForegroundInfo() = syncForegroundInfo(applicationContext)
}

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: DeliveryEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        return if (engine.download(id)) Result.success() else Result.retry()
    }
    override suspend fun getForegroundInfo() = syncForegroundInfo(applicationContext)
    companion object { const val KEY_ID = "proposal_id" }
}

@HiltWorker
class AlarmWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: DeliveryEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        engine.onAlarm(id)
        return Result.success()
    }
    override suspend fun getForegroundInfo() = syncForegroundInfo(applicationContext)
    companion object { const val KEY_ID = "proposal_id"; const val KEY_KIND = "kind" }
}

/** Exact alarm fired: hand off to an expedited job (receivers only get ~10 seconds). */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var work: DeliveryWork
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmWorker.KEY_ID) ?: return
        work.runAlarm(id, intent.getStringExtra(AlarmWorker.KEY_KIND) ?: "START")
    }
}

/** Boot, clock/time zone changes, app update, exact-alarm permission change → rebuild all alarms. */
@AndroidEntryPoint
class SystemEventsReceiver : BroadcastReceiver() {
    @Inject lateinit var work: DeliveryWork
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        work.reconcileNow()
        work.schedulePeriodic()
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}

/** "Set wallpaper" action of the tap-mode notification. */
@AndroidEntryPoint
class TapToSetReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: DeliveryEngine
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmWorker.KEY_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { engine.onTapToSet(id) } finally { pending.finish() }
        }
    }
}
