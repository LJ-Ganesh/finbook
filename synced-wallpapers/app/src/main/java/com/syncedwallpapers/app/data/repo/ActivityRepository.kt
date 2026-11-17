package com.syncedwallpapers.app.data.repo

import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.OutboxDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The Activity inbox is the user's own notification_outbox rows (RLS: own rows only). */
@Singleton
class ActivityRepository @Inject constructor(private val api: Api) {
    suspend fun inbox(): List<OutboxDto> = api.client.from("notification_outbox").select {
        filter { eq("user_id", api.uid()) }
        order("created_at", Order.DESCENDING)
        limit(100)
    }.decodeList()

    suspend fun markAllRead() {
        api.client.from("notification_outbox").update({ set("read_at", Instant.now().toString()) }) {
            filter { eq("user_id", api.uid()); exact("read_at", null) }
        }
    }
}
