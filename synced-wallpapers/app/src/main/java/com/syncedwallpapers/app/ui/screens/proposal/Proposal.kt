package com.syncedwallpapers.app.ui.screens.proposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.syncedwallpapers.app.core.rules.ScheduleRules
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.Commitment
import com.syncedwallpapers.app.data.remote.DeliverySummary
import com.syncedwallpapers.app.data.remote.ProposalDto
import com.syncedwallpapers.app.data.remote.VoteSummary
import com.syncedwallpapers.app.data.repo.GroupDetail
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.data.repo.Target
import com.syncedwallpapers.app.data.repo.TimeSuggestionDto
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.ConfirmDialog
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.PhoneFrame
import com.syncedwallpapers.app.ui.components.ProgressRing
import com.syncedwallpapers.app.ui.components.ReasonDialog
import com.syncedwallpapers.app.ui.components.SecureScreen
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.load
import com.syncedwallpapers.app.ui.nav.ProposalRoute
import com.syncedwallpapers.app.ui.runAction
import com.syncedwallpapers.app.ui.screens.group.statusLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class ProposalData(
    val proposal: ProposalDto,
    val votes: VoteSummary,
    val conflicts: List<Commitment>,
    val detail: GroupDetail,
    val imageUrl: String?,
    val delivery: DeliverySummary?,
    val suggestions: List<TimeSuggestionDto>,
) {
    val isProposer get() = proposal.proposerId == detail.me?.userId
}

@HiltViewModel
class ProposalViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val proposals: ProposalRepository,
    private val groups: GroupRepository,
    private val engine: DeliveryEngine,
    val clock: ServerClock,
    realtime: RealtimeRefresher,
) : ViewModel() {
    val proposalId = handle.toRoute<ProposalRoute>().proposalId
    private val _data = MutableStateFlow<Loadable<ProposalData>>(Loadable.Loading)
    val data = _data.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { if (it == "proposals" || it == "group_members") refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val r = load { fetch() }
        if (r is Loadable.Ready || _data.value !is Loadable.Ready) _data.value = r
    }

    private suspend fun fetch(): ProposalData = coroutineScope {
        val p = proposals.get(proposalId)
        val votes = async { proposals.summary(proposalId) }
        val conflicts = async { if (p.status == "PENDING") proposals.conflicts(proposalId) else emptyList() }
        val detail = async { groups.detail(p.groupId) }
        val url = async { if (p.status in setOf("PENDING", "APPROVED", "LIVE")) runCatching { proposals.signedUrl(p.imagePath) }.getOrNull() else null }
        val delivery = async { if (p.status in setOf("APPROVED", "LIVE")) runCatching { proposals.deliverySummary(proposalId) }.getOrNull() else null }
        val d = detail.await()
        val suggestions = async { if (p.proposerId == d.me?.userId) runCatching { proposals.suggestions(proposalId) }.getOrDefault(emptyList()) else emptyList() }
        ProposalData(p, votes.await(), conflicts.await(), d, url.await(), delivery.await(), suggestions.await())
    }

    fun vote(approve: Boolean, reason: String?) = act { proposals.vote(proposalId, approve, reason) }
    fun suggest(start: Instant, end: Instant) = act { proposals.suggestTime(proposalId, start, end); _message.value = "Suggestion sent to the proposer." }
    fun cancel() = act { proposals.cancel(proposalId) }
    fun skip() = act { engine.skip(proposalId) }
    fun dismiss() { _message.value = null }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runAction(block)?.let { _message.value = it }
        refresh()
    }
}

@Composable
fun ProposalScreen(onBack: () -> Unit, onRepropose: (groupId: String, replaces: String, start: Instant, end: Instant) -> Unit, vm: ProposalViewModel = hiltViewModel()) {
    SecureScreen()
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    var rejecting by remember { mutableStateOf(false) }
    var suggesting by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var skipping by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Proposal") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, { vm.refresh() }, Modifier.padding(pad))
            is Loadable.Ready -> {
                val x = d.value
                val p = x.proposal
                val zone = runCatching { ZoneId.of(x.detail.group.timeZone) }.getOrDefault(ZoneId.systemDefault())
                val proposer = x.detail.members.firstOrNull { it.userId == p.proposerId }?.name ?: "A former member"
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message?.let { ErrorBanner(it, onDismiss = vm::dismiss) }
                    if (x.imageUrl != null) PhoneFrame(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally), url = x.imageUrl, cacheKey = p.id,
                        focalX = p.focalX, focalY = p.focalY, reminder = p.reminderText, reminderStyle = p.reminderStyle)
                    else InfoBanner("The photo is no longer available.")

                    SwCard {
                        Text("${x.detail.group.name} · ${statusLabel(p.status)}", style = MaterialTheme.typography.titleMedium)
                        Text(TimeFormat.window(p.startAt, p.endAt, zone))
                        TimeFormat.localWindowIfDifferent(p.startAt, p.endAt, zone)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("${Target.valueOf(p.target).label} · proposed by $proposer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.status == "PENDING") Text("Voting closes ${TimeFormat.groupAndLocal(p.voteDeadline, zone)} · in ${TimeFormat.remaining(Duration.between(vm.clock.now(), p.voteDeadline))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    when (p.status) {
                        "PENDING" -> {
                            ProgressRing(x.votes.approvals, x.votes.required, "${x.votes.approvals} of ${x.votes.required} approved")
                            x.conflicts.firstOrNull()?.let { c ->
                                InfoBanner("Overlaps with “${c.groupName}” ${TimeFormat.window(c.startAt, c.endAt, ZoneId.systemDefault())} on your phone.",
                                    color = MaterialTheme.colorScheme.errorContainer)
                            }
                            if (x.votes.myVote == null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.vote(true, null) }, enabled = x.conflicts.isEmpty(), modifier = Modifier.weight(1f)) { Text("Approve") }
                                    OutlinedButton(onClick = { rejecting = true }, modifier = Modifier.weight(1f)) { Text("Reject") }
                                }
                                if (x.detail.me?.deliveryMode == "AUTO") Text("Approving means it is set on your phone automatically at the start time.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text(if (x.isProposer) "You proposed this." else "You voted ${if (x.votes.myVote == "APPROVE") "Approve" else "Reject"}.",
                                style = MaterialTheme.typography.bodyMedium)
                            if (!x.isProposer) TextButton(onClick = { suggesting = true }) { Text("Suggest another time") }
                            if (x.isProposer && x.suggestions.isNotEmpty()) {
                                Text("Suggested times", style = MaterialTheme.typography.titleSmall)
                                x.suggestions.forEach { sug ->
                                    SwCard(outlined = true) {
                                        Text(TimeFormat.window(sug.start, sug.end, zone))
                                        Button(onClick = { onRepropose(p.groupId, p.id, sug.start, sug.end) }) { Text("Re-propose at this time") }
                                        Text("Creates a new proposal; votes start again.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        "APPROVED", "LIVE" -> {
                            x.delivery?.let { s ->
                                val active = s.members - s.skipped
                                ProgressRing(s.ready, active, "${s.ready} of $active ready" + if (s.skipped > 0) " · ${s.skipped} skipped" else "")
                                if (s.people.isNotEmpty()) Text(s.people.joinToString { "${it.name} (${it.state.lowercase()})" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = { skipping = true }, modifier = Modifier.fillMaxWidth()) { Text("Skip on my phone") }
                        }
                        "REJECTED" -> {
                            Text("Not approved.", style = MaterialTheme.typography.titleSmall)
                            if (x.isProposer) x.votes.reasons.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
                        }
                        "EXPIRED" -> Text("Expired: not everyone voted before the deadline.")
                        "CANCELLED" -> Text("Cancelled.")
                    }
                    if (x.isProposer && p.status in setOf("PENDING", "APPROVED", "LIVE"))
                        TextButton(onClick = { cancelling = true }) { Text("Cancel for everyone", color = MaterialTheme.colorScheme.error) }
                }
                if (rejecting) ReasonDialog("Reject?", "The proposer sees “Not approved” and your reason, never your name.", "Reject",
                    onConfirm = { rejecting = false; vm.vote(false, it) }, onDismiss = { rejecting = false })
                if (cancelling) ConfirmDialog("Cancel this ${if (p.status == "PENDING") "proposal" else "schedule"}?",
                    "It's cancelled on every member's phone. If it's live, everyone's own wallpaper comes back.", "Cancel it",
                    onConfirm = { cancelling = false; vm.cancel() }, onDismiss = { cancelling = false })
                if (skipping) ConfirmDialog("Skip on your phone?", "Only your phone is affected. The group is told a member skipped, not who.", "Skip",
                    onConfirm = { skipping = false; vm.skip() }, onDismiss = { skipping = false })
                if (suggesting) SuggestDialog(p, zone, vm.clock, onDismiss = { suggesting = false }) { s, e -> suggesting = false; vm.suggest(s, e) }
            }
        }
    }
}

/** Shift the window by whole hours/days; keeps the same length. Simple and hard to get wrong. */
@Composable
private fun SuggestDialog(p: ProposalDto, zone: ZoneId, clock: ServerClock, onDismiss: () -> Unit, onSend: (Instant, Instant) -> Unit) {
    var shiftHours by remember { mutableStateOf(24L) }
    val start = p.startAt.plus(Duration.ofHours(shiftHours))
    val end = p.endAt.plus(Duration.ofHours(shiftHours))
    val problem = ScheduleRules.validate(start, end, clock.now())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Suggest another time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(TimeFormat.window(start, end, zone), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-24L to "−1 day", -1L to "−1 h", 1L to "+1 h", 24L to "+1 day").forEach { (h, label) ->
                        OutlinedButton(onClick = { shiftHours += h }) { Text(label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                problem?.let { Text(ScheduleRules.message(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = { onSend(start, end) }, enabled = problem == null) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
