package com.syncedwallpapers.app.data.repo

import android.util.Log
import com.syncedwallpapers.app.delivery.DeliveryWork
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is open, Supabase Realtime tells screens to refresh instead of waiting
 * for a push. Realtime applies RLS, so only rows the user may read arrive. Rows are
 * used only as a "something changed" signal; screens re-read through the normal APIs.
 */
@Singleton
class RealtimeRefresher @Inject constructor(private val client: SupabaseClient, private val work: DeliveryWork) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val changes: SharedFlow<String> = _changes
    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (channel != null) return
        scope.launch {
            runCatching {
                val ch = client.channel("sw-changes")
                for (table in listOf("groups", "group_members", "proposals", "admin_actions", "join_requests", "notification_outbox")) {
                    jobs += ch.postgresChangeFlow<PostgresAction>(schema = "public") { this.table = table }
                        .onEach {
                            _changes.emit(table)
                            if (table == "proposals" || table == "group_members") work.reconcileNow()
                        }
                        .launchIn(scope)
                }
                ch.subscribe()
                channel = ch
            }.onFailure { Log.w("Realtime", "subscribe failed: ${it.message}") }
        }
    }

    fun stop() {
        val ch = channel ?: return
        channel = null
        jobs.forEach { it.cancel() }; jobs.clear()
        scope.launch { runCatching { client.realtime.removeChannel(ch) } }
    }

    /** Lets a screen trigger its own refresh after a local action. */
    fun poke(table: String) { _changes.tryEmit(table) }
}
