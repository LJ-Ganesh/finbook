package com.syncedwallpapers.app.ui.screens.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.OutboxDto
import com.syncedwallpapers.app.data.repo.ActivityRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.notifications.EventTexts
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.EmptyState
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ActivityViewModel @Inject constructor(private val repo: ActivityRepository, realtime: RealtimeRefresher) : ViewModel() {
    private val _items = MutableStateFlow<Loadable<List<OutboxDto>>>(Loadable.Loading)
    val items = _items.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { if (it == "notification_outbox") refresh(quiet = true) } }
    }

    fun refresh(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _refreshing.value = true
        val r = load { repo.inbox() }
        if (r is Loadable.Ready || _items.value !is Loadable.Ready) _items.value = r
        _refreshing.value = false
        if (r is Loadable.Ready && r.value.any { it.readAt == null }) runCatching { repo.markAllRead() }
    }
}

/** Inbox of proposals, admin actions, results and membership events. */
@Composable
fun ActivityTab(onOpenGroup: (String) -> Unit, vm: ActivityViewModel = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        when (val s = items) {
            Loadable.Loading -> Loading()
            is Loadable.Failed -> ErrorState(s.error, onRetry = { vm.refresh() })
            is Loadable.Ready -> {
                val shown = s.value.mapNotNull { o -> EventTexts.forEvent(o.type, o.payload)?.let { o to it } }
                if (shown.isEmpty()) EmptyState(Icons.Outlined.Inbox, "Nothing yet", "Proposals, votes, results and group changes will appear here.")
                else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.first.id }) { (o, text) ->
                        val gid = o.payload["group_id"]?.jsonPrimitive?.contentOrNull
                        SwCard(onClick = gid?.let { { onOpenGroup(it) } }, outlined = o.readAt != null) {
                            Text(text.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (o.readAt == null) FontWeight.Bold else FontWeight.SemiBold)
                            Text(text.body, style = MaterialTheme.typography.bodyMedium)
                            Text("${TimeFormat.dayLabel(o.createdAt, ZoneId.systemDefault())} · ${TimeFormat.clock(o.createdAt, ZoneId.systemDefault())}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
