package com.syncedwallpapers.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.syncedwallpapers.app.MainActivity
import com.syncedwallpapers.app.R
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.local.ScheduleEntity
import com.syncedwallpapers.app.data.local.Settings
import com.syncedwallpapers.app.delivery.AlarmWorker
import com.syncedwallpapers.app.delivery.TapToSetReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

object Channels {
    const val PROPOSALS = "proposals"
    const val REMINDERS = "vote_reminders"
    const val RESULTS = "results"
    const val LIVE = "going_live"
    const val LIVE_QUIET = "now_live"
    const val MEMBERSHIP = "membership"
    const val ADMIN = "admin"
    const val SYNC = "sync"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup("groups", "Groups"))
        listOf(
            NotificationChannel(PROPOSALS, "New proposals", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(REMINDERS, "Vote reminders", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(RESULTS, "Approved / not approved", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(LIVE, "Set wallpaper (tap mode)", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(LIVE_QUIET, "Now live", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(MEMBERSHIP, "Members and schedules", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(ADMIN, "Admin requests", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SYNC, "Background sync", NotificationManager.IMPORTANCE_MIN),
        ).forEach { it.group = "groups"; nm.createNotificationChannel(it) }
    }
}

/** A notification built from an outbox event (also used by the Activity inbox). */
data class EventText(val channel: String, val title: String, val body: String)

object EventTexts {
    private fun JsonObject.s(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    private fun adminLabel(type: String?) = when (type) {
        "RENAME" -> "rename the group"
        "TIMEZONE" -> "change the time zone"
        "MEMBER_LIMIT" -> "change the member limit"
        "REMOVE_MEMBER" -> "remove a member"
        "TRANSFER_ADMIN" -> "hand over admin"
        "DELETE_GROUP" -> "delete the group"
        else -> "change the group"
    }

    /** Null = don't show a notification for this type (data-only events). */
    fun forEvent(type: String, p: JsonObject): EventText? {
        val group = p.s("group_name") ?: "Your group"
        val zone = p.s("time_zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val start = p.s("start_at")?.let { runCatching { com.syncedwallpapers.app.data.remote.InstantSerializer.parse(it) }.getOrNull() }
        val whenText = start?.let { TimeFormat.groupAndLocal(it, zone) }?.let { " · ${TimeFormat.dayLabel(start, zone)} $it" } ?: ""
        val proposer = p.s("proposer_name")?.ifBlank { null } ?: "Someone"
        return when (type) {
            "NEW_PROPOSAL" -> EventText(Channels.PROPOSALS, "$group: new wallpaper proposal", "$proposer proposed a wallpaper$whenText. Everyone must approve.")
            "VOTE_REMINDER" -> EventText(Channels.REMINDERS, "$group: vote closes in an hour", "You haven't voted on a wallpaper$whenText yet.")
            "PROPOSAL_APPROVED" -> EventText(Channels.RESULTS, "$group: approved", "Everyone approved the wallpaper$whenText.")
            "PROPOSAL_REJECTED" -> EventText(Channels.RESULTS, "$group: not approved",
                p.s("reason")?.let { "Not approved. Reason: “$it”" } ?: "A wallpaper proposal wasn't approved.")
            "PROPOSAL_EXPIRED" -> EventText(Channels.RESULTS, "$group: expired", "Not everyone voted in time, so the proposal expired.")
            "PROPOSAL_CANCELLED" -> EventText(Channels.MEMBERSHIP, "$group: schedule cancelled", "A wallpaper$whenText was cancelled.")
            "SCHEDULE_SKIPPED" -> EventText(Channels.MEMBERSHIP, "$group", "A member skipped a wallpaper$whenText on their phone.")
            "TIME_SUGGESTION" -> EventText(Channels.PROPOSALS, "$group: another time suggested", "A member suggested another time for your proposal. Tap to re-propose.")
            "JOIN_REQUEST" -> EventText(Channels.MEMBERSHIP, "$group: join request", "${p.s("joiner_name") ?: "Someone"} wants to join. Everyone must approve.")
            "JOIN_RESULT" -> when (p.s("result")) {
                "APPROVED" -> EventText(Channels.MEMBERSHIP, "You joined $group", "Everyone approved. Upcoming wallpapers will appear on your phone.")
                else -> EventText(Channels.MEMBERSHIP, "$group: join not approved", p.s("reason")?.let { "Reason: “$it”" } ?: "Your request to join wasn't approved.")
            }
            "JOIN_TERMS_CHANGED" -> EventText(Channels.MEMBERSHIP, "$group: please review again", "A new schedule was approved. Accept the upcoming schedules again to join.")
            "MEMBER_JOINED" -> EventText(Channels.MEMBERSHIP, group, "${p.s("member_name") ?: "A new member"} joined.")
            "MEMBER_LEFT" -> EventText(Channels.MEMBERSHIP, group, "${p.s("member_name") ?: "A member"} left the group.")
            "MEMBER_REMOVED" -> EventText(Channels.MEMBERSHIP, group, "${p.s("member_name") ?: "A member"} was removed.")
            "REMOVED_FROM_GROUP" -> EventText(Channels.MEMBERSHIP, group, "You were removed from this group. Its wallpapers were removed from your phone.")
            "GROUP_DELETED" -> EventText(Channels.MEMBERSHIP, group, "The group was deleted. Its wallpapers were removed from your phone.")
            "ADMIN_ACTION_REQUEST" -> EventText(Channels.ADMIN, "$group: admin request", "The admin wants to ${adminLabel(p.s("action_type"))}. Everyone must approve.")
            "ADMIN_ACTION_RESULT" -> EventText(Channels.ADMIN, group, when (p.s("result")) {
                "APPROVED" -> "Approved: ${adminLabel(p.s("action_type"))}."
                "REJECTED" -> "Not approved: ${adminLabel(p.s("action_type"))}."
                "EXPIRED" -> "Expired: ${adminLabel(p.s("action_type"))} (not everyone voted in 48h)."
                else -> "Cancelled: ${adminLabel(p.s("action_type"))}."
            })
            "ADMIN_CHANGED" -> EventText(Channels.ADMIN, group, "${p.s("admin_name") ?: "Another member"} is now the admin.")
            "YOU_ARE_ADMIN" -> EventText(Channels.ADMIN, "You're now the admin of $group",
                if (p.s("can_decline") == "true") "The previous admin left. You can decline to pass it on." else "The group approved the handover.")
            else -> null // LEFT_GROUP, JOIN_STARTED: data only
        }
    }

    fun enabled(channel: String, s: Settings): Boolean = when (channel) {
        Channels.PROPOSALS -> s.notifyProposals
        Channels.REMINDERS -> s.notifyReminders
        Channels.RESULTS -> s.notifyResults
        Channels.MEMBERSHIP -> s.notifyMembership
        Channels.ADMIN -> s.notifyAdmin
        else -> true
    }
}

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    private fun canPost() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(route: String? = null): PendingIntent = PendingIntent.getActivity(
        context, route.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_ROUTE, route),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun showEvent(outboxId: Long, type: String, payload: JsonObject, settings: Settings) {
        val text = EventTexts.forEvent(type, payload) ?: return
        if (!EventTexts.enabled(text.channel, settings) || !canPost()) return
        val groupId = payload["group_id"]?.jsonPrimitive?.contentOrNull
        val n = NotificationCompat.Builder(context, text.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setAutoCancel(true)
            .setContentIntent(openApp(groupId?.let { "group/$it" }))
            .build()
        try { nm.notify("event", outboxId.toInt(), n) } catch (_: SecurityException) {}
    }

    /** Tap mode: nothing changes until the user taps "Set wallpaper". */
    fun tapToSet(e: ScheduleEntity) {
        if (!canPost()) return
        val zone = runCatching { ZoneId.of(e.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val end = Instant.ofEpochMilli(e.endAt)
        val action = PendingIntent.getBroadcast(context, e.proposalId.hashCode(),
            Intent(context, TapToSetReceiver::class.java).putExtra(AlarmWorker.KEY_ID, e.proposalId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Channels.LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${e.groupName}: wallpaper is ready")
            .setContentText("Until ${TimeFormat.groupAndLocal(end, zone)}. Nothing changes until you tap.")
            .addAction(0, "Set wallpaper", action)
            .setContentIntent(openApp())
            .setTimeoutAfter((e.endAt - System.currentTimeMillis()).coerceAtLeast(60_000))
            .setOngoing(false)
            .build()
        try { nm.notify("tap", e.proposalId.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun cancelTapToSet(proposalId: String) = nm.cancel("tap", proposalId.hashCode())

    fun nowLive(e: ScheduleEntity) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, Channels.LIVE_QUIET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Now live: ${e.groupName}")
            .setContentText("Open the app to skip it on your phone.")
            .setSilent(true)
            .setContentIntent(openApp())
            .setTimeoutAfter((e.endAt - System.currentTimeMillis()).coerceAtLeast(60_000))
            .build()
        try { nm.notify("live", e.proposalId.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun cancelNowLive(proposalId: String) = nm.cancel("live", proposalId.hashCode())
}
