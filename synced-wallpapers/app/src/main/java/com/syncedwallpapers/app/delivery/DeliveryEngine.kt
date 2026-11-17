package com.syncedwallpapers.app.delivery

import android.graphics.Bitmap
import android.util.Log
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.data.local.LocalState
import com.syncedwallpapers.app.data.local.ScheduleDao
import com.syncedwallpapers.app.data.local.ScheduleEntity
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.ScheduleDto
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.notifications.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device side of a schedule: download → process → encrypt → alarms → apply → restore.
 * Everything is rebuilt from Room + server by [reconcile], so a lost push, a reboot, a
 * clock change or a killed process never leaves the device in a wrong state.
 */
@Singleton
class DeliveryEngine @Inject constructor(
    private val dao: ScheduleDao,
    private val proposals: ProposalRepository,
    private val api: Api,
    private val clock: ServerClock,
    private val alarms: AlarmScheduler,
    private val applier: WallpaperApplier,
    private val images: ImageProcessor,
    private val store: SecureStore,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val http: OkHttpClient,
    private val work: DeliveryWork,
) {
    private val mutex = Mutex()

    enum class SyncResult { OK, OFFLINE, SIGNED_OUT }

    /** Re-sync with the server and fix the local schedule. Safe to call any time. */
    suspend fun reconcile(): SyncResult = mutex.withLock {
        clock.setOffset(Duration.ofMillis(settings.current().serverOffsetMillis))
        if (api.uidOrNull() == null) {
            dao.all().forEach { cleanUp(it, LocalState.ENDED, report = false) }
            return SyncResult.SIGNED_OUT
        }
        val server: List<ScheduleDto>? = try {
            val sent = clock.deviceNow()
            val serverNow = proposals.serverNow()
            val offset = clock.record(sent, serverNow, clock.deviceNow())
            settings.setServerOffset(offset.toMillis())
            proposals.mySchedules()
        } catch (t: Throwable) {
            Log.w(TAG, "reconcile offline: ${t.message}")
            null
        }

        if (server != null) {
            val byId = server.associateBy { it.proposalId }
            // Gone from the server = cancelled, left, removed, group deleted → undo locally now.
            dao.all().filter { it.proposalId !in byId }.forEach { cleanUp(it, LocalState.ENDED, report = false) }
            server.forEach { dto ->
                val existing = dao.get(dto.proposalId)
                // A skip made offline: tell the server now.
                if (existing?.skipped == true && !dto.skipped) runCatching { proposals.skip(dto.proposalId) }
                dao.upsert(merge(existing, dto))
            }
        }
        dao.all().forEach { act(it) }
        // Remove encrypted files nothing refers to any more (crash leftovers).
        val keep = dao.all().flatMap { listOfNotNull(it.homeFile, it.lockFile) }.toSet() + listOfNotNull(settings.current().homeWallpaperFile)
        store.deleteAllExcept(keep)
        if (server == null) SyncResult.OFFLINE else SyncResult.OK
    }

    /** Alarm fired (exact or fallback). */
    suspend fun onAlarm(proposalId: String) = mutex.withLock {
        dao.get(proposalId)?.let { act(it) }
    }

    /** "Set wallpaper" tapped in tap mode. Only applies inside the window. */
    suspend fun onTapToSet(proposalId: String): Boolean = mutex.withLock {
        val e = dao.get(proposalId) ?: return@withLock false
        val now = clock.now()
        if (!DeliveryDecider.mayApply(e, now) || !e.isReady) return@withLock false
        apply(e, late = false)
        true
    }

    /** "Skip on my phone": revoke consent on this device right away, then tell the server. */
    suspend fun skip(proposalId: String) {
        mutex.withLock {
            dao.get(proposalId)?.let { e ->
                val skipped = e.copy(skipped = true)
                dao.upsert(skipped)
                cleanUp(skipped, LocalState.SKIPPED, report = false)
            }
        }
        proposals.skip(proposalId)
    }

    /** The user left a group (or was removed / it was deleted): undo everything for it immediately. */
    suspend fun forgetGroup(groupId: String) = mutex.withLock {
        dao.forGroup(groupId).forEach { cleanUp(it, LocalState.ENDED, report = false) }
    }

    /** Sign-out / account deletion: restore and wipe everything local. */
    suspend fun wipe() = mutex.withLock {
        dao.all().forEach { cleanUp(it, LocalState.ENDED, report = false) }
        dao.clear()
    }

    /** Downloads and prepares the image. Returns false if it should be retried. */
    suspend fun download(proposalId: String): Boolean {
        val e = mutex.withLock { dao.get(proposalId) } ?: return true
        if (e.isReady || e.skipped) return true
        if (!clock.now().isBefore(Instant.ofEpochMilli(e.endAt))) return true // too late; reconcile reports MISSED
        val prepared = try {
            val url = proposals.signedUrl(e.imagePath)
            val bytes = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("download failed: ${r.code}")
                    r.body.bytes()
                }
            }
            withContext(Dispatchers.Default) { prepare(e, bytes) }
        } catch (t: Throwable) {
            Log.w(TAG, "download $proposalId failed: ${t.message}")
            return false
        }
        mutex.withLock {
            val fresh = dao.get(proposalId)
            if (fresh == null || fresh.skipped) {
                store.delete(prepared.first); store.delete(prepared.second)
                return true
            }
            val ready = fresh.copy(homeFile = prepared.first, lockFile = prepared.second, state = LocalState.READY)
            dao.upsert(ready)
            act(ready)
        }
        return true
    }

    // ------------------------------------------------------------------------------

    private fun merge(existing: ScheduleEntity?, d: ScheduleDto): ScheduleEntity {
        val base = existing ?: ScheduleEntity(
            proposalId = d.proposalId, groupId = d.groupId, groupName = d.groupName, timeZone = d.timeZone,
            startAt = d.startAt.toEpochMilli(), endAt = d.endAt.toEpochMilli(), target = d.target, imagePath = d.imagePath,
            focalX = d.focalX, focalY = d.focalY, reminderText = d.reminderText, reminderStyle = d.reminderStyle,
            deliveryMode = d.deliveryMode,
        )
        return base.copy(
            groupName = d.groupName, timeZone = d.timeZone, deliveryMode = d.deliveryMode,
            skipped = base.skipped || d.skipped,
        )
    }

    /** Applies the decided plan for one schedule. Caller holds the mutex. */
    private suspend fun act(e: ScheduleEntity) {
        val now = clock.now()
        when (DeliveryDecider.decide(e, now)) {
            Plan.PREPARE -> {
                if (!e.isReady) work.enqueueDownload(e.proposalId)
                alarms.schedule(e.proposalId, AlarmKind.START, Instant.ofEpochMilli(e.startAt))
                alarms.schedule(e.proposalId, AlarmKind.END, Instant.ofEpochMilli(e.endAt))
                report(e, if (e.isReady) LocalState.READY else LocalState.DOWNLOADING)
            }
            Plan.APPLY -> apply(e, late = false)
            Plan.APPLY_LATE -> apply(e, late = true)
            Plan.NOTIFY_TAP -> {
                notifier.tapToSet(e)
                val updated = e.copy(tapNotified = true, state = LocalState.WAITING_TAP)
                dao.upsert(updated)
                alarms.schedule(e.proposalId, AlarmKind.END, Instant.ofEpochMilli(e.endAt))
                report(updated, LocalState.WAITING_TAP)
            }
            Plan.DOWNLOAD_NOT_READY -> {
                work.enqueueDownload(e.proposalId)
                alarms.schedule(e.proposalId, AlarmKind.END, Instant.ofEpochMilli(e.endAt))
                val updated = e.copy(state = LocalState.NOT_READY)
                dao.upsert(updated)
                report(updated, LocalState.NOT_READY)
            }
            Plan.KEEP -> alarms.schedule(e.proposalId, AlarmKind.END, Instant.ofEpochMilli(e.endAt))
            Plan.END -> cleanUp(e, LocalState.ENDED, report = true)
            Plan.MISSED -> cleanUp(e, LocalState.MISSED, report = true)
            Plan.NOT_TAPPED -> cleanUp(e, LocalState.NOT_TAPPED, report = true)
            Plan.SKIP -> cleanUp(e, LocalState.SKIPPED, report = false)
        }
    }

    private suspend fun apply(e: ScheduleEntity, late: Boolean) {
        var homeId: Int? = e.appliedHomeId
        var lockId: Int? = e.appliedLockId
        try {
            if (e.target == "HOME" || e.target == "BOTH") {
                withBitmap(e.homeFile) { homeId = applier.setHome(it) }
            }
            if (e.target == "LOCK" || e.target == "BOTH") {
                withBitmap(e.lockFile ?: e.homeFile) { lockId = applier.setLock(it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "apply ${e.proposalId} failed", t)
            return
        }
        val state = if (late) LocalState.APPLIED_LATE else LocalState.LIVE
        val updated = e.copy(appliedHomeId = homeId, appliedLockId = lockId, state = state, appliedLate = late)
        dao.upsert(updated)
        notifier.cancelTapToSet(e.proposalId)
        alarms.schedule(e.proposalId, AlarmKind.END, Instant.ofEpochMilli(e.endAt))
        if (settings.current().quietLiveNotice && e.deliveryMode == "AUTO") notifier.nowLive(updated)
        report(updated, state)
    }

    /**
     * Ends local delivery: restore the wallpaper only if ours is still set, delete the
     * images, cancel alarms and notifications, report the final state.
     */
    private suspend fun cleanUp(e: ScheduleEntity, finalState: LocalState, report: Boolean) {
        if (e.isApplied) restoreIfStillOurs(e)
        alarms.cancel(e.proposalId)
        notifier.cancelTapToSet(e.proposalId)
        notifier.cancelNowLive(e.proposalId)
        store.delete(e.homeFile)
        store.delete(e.lockFile)
        if (report) report(e, finalState, force = true)
        if (finalState == LocalState.SKIPPED) {
            // Keep a tombstone so a later sync can't bring it back before the server knows.
            dao.upsert(e.copy(skipped = true, state = LocalState.SKIPPED, homeFile = null, lockFile = null,
                appliedHomeId = null, appliedLockId = null))
        } else {
            dao.delete(e.proposalId)
        }
    }

    private suspend fun restoreIfStillOurs(e: ScheduleEntity) {
        runCatching {
            if (RestorePolicy.shouldRestore(e.appliedHomeId, applier.currentHomeId())) {
                val home = settings.current().homeWallpaperFile?.let { store.get(it) }?.let { images.decodeBytes(it) }
                applier.restoreHome(home)
                home?.recycle()
            }
            if (RestorePolicy.shouldRestore(e.appliedLockId, applier.currentLockId())) applier.restoreLock()
        }.onFailure { Log.e(TAG, "restore ${e.proposalId} failed", it) }
    }

    private inline fun withBitmap(file: String?, block: (Bitmap) -> Unit) {
        val bytes = file?.let { store.get(it) } ?: error("image missing")
        val bmp = images.decodeBytes(bytes)
        try { block(bmp) } finally { bmp.recycle() }
    }

    /** Crop for this screen, draw the reminder, encrypt. Returns (homeFile, lockFile). */
    private fun prepare(e: ScheduleEntity, bytes: ByteArray): Pair<String?, String?> {
        val src = images.decodeBytes(bytes)
        try {
            val screen = images.screenSize()
            val rendered = images.renderForScreen(src, screen, e.focalX, e.focalY, e.reminderText, e.reminderStyle)
            val png = try { images.encodePng(rendered) } finally { rendered.recycle() }
            // Same screen for home and lock, so one rendered file serves both targets.
            val file = store.put(png, "wp")
            return when (e.target) {
                "HOME" -> file to null
                "LOCK" -> null to file
                else -> file to file
            }
        } finally {
            src.recycle()
        }
    }

    private suspend fun report(e: ScheduleEntity, state: LocalState, force: Boolean = false) {
        val wire = DeliveryDecider.reportedState(state) ?: return
        if (!force && e.lastReported == wire) return
        runCatching { proposals.reportDelivery(e.proposalId, wire) }
            .onSuccess { dao.get(e.proposalId)?.let { cur -> dao.upsert(cur.copy(lastReported = wire)) } }
    }

    companion object { private const val TAG = "DeliveryEngine" }
}
