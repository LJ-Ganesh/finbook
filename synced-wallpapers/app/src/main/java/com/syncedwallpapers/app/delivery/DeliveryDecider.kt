package com.syncedwallpapers.app.delivery

import com.syncedwallpapers.app.data.local.LocalState
import com.syncedwallpapers.app.data.local.ScheduleEntity
import java.time.Duration
import java.time.Instant

/** What the engine should do with one schedule right now. Pure and unit-tested. */
enum class Plan {
    /** Before the start: make sure the image is downloading and the alarms are set. */
    PREPARE,
    /** Inside the window, image ready, auto mode: apply now (APPLIED_LATE if well past the start). */
    APPLY,
    APPLY_LATE,
    /** Inside the window, tap mode: post the "Set wallpaper" notification once. */
    NOTIFY_TAP,
    /** Inside the window but the image isn't here yet: keep downloading until the end. */
    DOWNLOAD_NOT_READY,
    /** Already applied or waiting for a tap: just keep the end alarm. */
    KEEP,
    /** The window is over and our wallpaper was applied: restore (if still ours) and purge. */
    END,
    /** The window is over and nothing was applied: report MISSED and purge. */
    MISSED,
    /** Tap mode, the user never tapped: report NOT_TAPPED, nothing changed, purge. */
    NOT_TAPPED,
    /** Skipped on this phone: restore if it is live, purge, never apply. */
    SKIP,
}

object DeliveryDecider {
    /** A wallpaper applied more than this after the start counts as APPLIED_LATE. */
    val LATE_AFTER: Duration = Duration.ofMinutes(5)

    fun decide(e: ScheduleEntity, now: Instant): Plan {
        val start = Instant.ofEpochMilli(e.startAt)
        val end = Instant.ofEpochMilli(e.endAt)
        if (e.skipped) return Plan.SKIP
        if (!now.isBefore(end)) {
            return when {
                e.isApplied -> Plan.END
                e.deliveryMode == "TAP" && e.tapNotified -> Plan.NOT_TAPPED
                else -> Plan.MISSED
            }
        }
        if (now.isBefore(start)) return Plan.PREPARE
        // Inside [start, end)
        if (e.isApplied) return Plan.KEEP
        if (!e.isReady) return Plan.DOWNLOAD_NOT_READY
        if (e.deliveryMode == "TAP") return if (e.tapNotified) Plan.KEEP else Plan.NOTIFY_TAP
        return if (Duration.between(start, now) > LATE_AFTER) Plan.APPLY_LATE else Plan.APPLY
    }

    /** Applying is only ever allowed inside the window (spec: never outside it). */
    fun mayApply(e: ScheduleEntity, now: Instant): Boolean =
        !e.skipped && !now.isBefore(Instant.ofEpochMilli(e.startAt)) && now.isBefore(Instant.ofEpochMilli(e.endAt))

    /** The delivery state reported to the server for a local state. */
    fun reportedState(s: LocalState): String? = when (s) {
        LocalState.DOWNLOADING -> "DOWNLOADING"
        LocalState.NOT_READY -> "NOT_READY"
        LocalState.READY, LocalState.WAITING_TAP -> "READY"
        LocalState.LIVE -> "LIVE"
        LocalState.APPLIED_LATE -> "APPLIED_LATE"
        LocalState.NOT_TAPPED -> "NOT_TAPPED"
        LocalState.MISSED -> "MISSED"
        LocalState.ENDED -> "ENDED"
        LocalState.SKIPPED -> null // the server records skips itself (skip_on_my_phone)
    }
}

/** Restore only if the wallpaper we set is still the current one (the user may have changed it). */
object RestorePolicy {
    fun shouldRestore(appliedId: Int?, currentId: Int): Boolean = appliedId != null && appliedId > 0 && currentId == appliedId
}
