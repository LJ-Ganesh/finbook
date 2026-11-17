package com.syncedwallpapers.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.local.ScheduleDao
import com.syncedwallpapers.app.data.local.ScheduleEntity
import com.syncedwallpapers.app.data.remote.GroupDto
import com.syncedwallpapers.app.data.remote.MyJoinRequest
import com.syncedwallpapers.app.data.remote.PendingCounts
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.health.Health
import com.syncedwallpapers.app.health.SetupHealthChecker
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.ConfirmDialog
import com.syncedwallpapers.app.ui.components.EmptyState
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.Pill
import com.syncedwallpapers.app.ui.components.SectionTitle
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.load
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HomeData(val groups: List<GroupDto>, val counts: Map<String, PendingCounts>, val joins: List<MyJoinRequest>)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val groups: GroupRepository,
    dao: ScheduleDao,
    private val engine: DeliveryEngine,
    private val health: SetupHealthChecker,
    val clock: ServerClock,
    realtime: RealtimeRefresher,
) : ViewModel() {
    private val _data = MutableStateFlow<Loadable<HomeData>>(Loadable.Loading)
    val data = _data.asStateFlow()
    val schedules = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _healthRed = MutableStateFlow(false)
    val healthRed = _healthRed.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { refresh(quiet = true) } }
    }

    fun refresh(quiet: Boolean = false) = viewModelScope.launch {
        if (!quiet) _refreshing.value = true
        _healthRed.value = health.worst() == Health.RED
        val r = load { HomeData(groups.myGroups(), groups.pendingCounts(), groups.myJoinRequests()) }
        if (r is Loadable.Ready || _data.value !is Loadable.Ready) _data.value = r
        _refreshing.value = false
    }

    fun skip(id: String) = viewModelScope.launch { runAction { engine.skip(id) }?.let { _message.value = it } }
    fun dismiss() { _message.value = null }
}

@Composable
fun HomeTab(
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onJoin: () -> Unit,
    onOpenJoin: (String) -> Unit,
    onOpenHealth: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val data by vm.data.collectAsState()
    val schedules by vm.schedules.collectAsState()
    val red by vm.healthRed.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val message by vm.message.collectAsState()
    var now by remember { mutableStateOf(vm.clock.now()) }
    LaunchedEffect(Unit) { while (true) { now = vm.clock.now(); delay(30_000) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(quiet = true) }

    val active = schedules.filter { !it.skipped && Instant.ofEpochMilli(it.endAt).isAfter(now) }
    val live = active.firstOrNull { it.isApplied || (Instant.ofEpochMilli(it.startAt) <= now) }
    val next = active.firstOrNull { Instant.ofEpochMilli(it.startAt).isAfter(now) && it != live }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            message?.let { m -> item { ErrorBanner(m, onDismiss = vm::dismiss) } }
            if (red) item {
                InfoBanner("Your wallpapers may be late or missed.", color = MaterialTheme.colorScheme.errorContainer) {
                    TextButton(onClick = onOpenHealth) { Text("Fix") }
                }
            }
            item { NowCard(live, now, vm::skip) }
            next?.let { n -> item { NextCard(n, now) } }

            when (val d = data) {
                Loadable.Loading -> item { Loading(Modifier.padding(32.dp)) }
                is Loadable.Failed -> item { ErrorState(d.error, onRetry = { vm.refresh() }) }
                is Loadable.Ready -> {
                    if (d.value.joins.isNotEmpty()) {
                        item { SectionTitle("Joining") }
                        items(d.value.joins, key = { it.requestId }) { j ->
                            SwCard(onClick = { onOpenJoin(j.requestId) }, outlined = true) {
                                Text(j.groupName, style = MaterialTheme.typography.titleMedium)
                                Text(if (j.termsAccepted) "Waiting for every member to approve" else "Review the upcoming wallpapers to continue",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Groups", Modifier.weight(1f))
                            TextButton(onClick = onJoin) { Text("Join") }
                            TextButton(onClick = onCreateGroup) { Text("New group") }
                        }
                    }
                    if (d.value.groups.isEmpty()) item {
                        EmptyState(Icons.Outlined.Groups, "No groups yet",
                            "Create a group and invite the people you trust, or join one with an invite code. A group of one works too, for your own routine reminders.") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onJoin) { Text("Join with code") }
                                OutlinedButton(onClick = onCreateGroup) { Text("Create group") }
                            }
                        }
                    }
                    items(d.value.groups, key = { it.id }) { g ->
                        val pending = d.value.counts[g.id]?.total ?: 0
                        SwCard(onClick = { onOpenGroup(g.id) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                                    Text(g.timeZone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (pending > 0) Badge { Text("$pending") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowCard(live: ScheduleEntity?, now: Instant, onSkip: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SwCard(container = if (live != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text("Now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (live == null) {
            Text("Your own wallpaper", style = MaterialTheme.typography.titleMedium)
        } else {
            Text(live.groupName, style = MaterialTheme.typography.titleLarge)
            val left = Duration.between(now, Instant.ofEpochMilli(live.endAt))
            val status = when {
                live.isApplied -> "Live · ${TimeFormat.remaining(left)} left"
                live.deliveryMode == "TAP" -> "Ready · tap the notification to set it · ${TimeFormat.remaining(left)} left"
                !live.isReady -> "Downloading… · ${TimeFormat.remaining(left)} left"
                else -> "Starting… · ${TimeFormat.remaining(left)} left"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { confirm = true }) { Text("Skip on my phone") }
        }
    }
    if (confirm && live != null) ConfirmDialog("Skip on your phone?",
        "Your own wallpaper comes back now. Only your phone is affected; the group is told a member skipped, not who.",
        "Skip", onConfirm = { confirm = false; onSkip(live.proposalId) }, onDismiss = { confirm = false })
}

@Composable
private fun NextCard(next: ScheduleEntity, now: Instant) {
    val zone = runCatching { ZoneId.of(next.timeZone) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(next.startAt)
    SwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Next", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Pill(if (next.isReady) "Ready" else "Downloading")
        }
        Text(next.groupName, style = MaterialTheme.typography.titleMedium)
        Text(TimeFormat.window(start, Instant.ofEpochMilli(next.endAt), zone), style = MaterialTheme.typography.bodyMedium)
        TimeFormat.localWindowIfDifferent(start, Instant.ofEpochMilli(next.endAt), zone)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Starts in ${TimeFormat.remaining(Duration.between(now, start))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier)
    }
}
