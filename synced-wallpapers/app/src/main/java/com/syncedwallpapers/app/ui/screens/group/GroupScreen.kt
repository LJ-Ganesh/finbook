package com.syncedwallpapers.app.ui.screens.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import coil3.compose.AsyncImage
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.JoinRequestRow
import com.syncedwallpapers.app.data.repo.Member
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.EmptyState
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Legend
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.Pill
import com.syncedwallpapers.app.ui.components.ProgressRing
import com.syncedwallpapers.app.ui.components.ReasonDialog
import com.syncedwallpapers.app.ui.components.SectionTitle
import com.syncedwallpapers.app.ui.components.SlotKind
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.components.TimelineSlot
import com.syncedwallpapers.app.ui.components.WeekTimeline
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun GroupScreen(
    onBack: () -> Unit,
    onSettings: (String) -> Unit,
    onPropose: (String) -> Unit,
    onOpenProposal: (String) -> Unit,
    onOpenAdminAction: (String) -> Unit,
    vm: GroupViewModel = hiltViewModel(),
) {
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    val left by vm.left.collectAsState()
    LaunchedEffect(left) { if (left) onBack() }
    val title = (data as? Loadable.Ready)?.value?.detail?.group?.name ?: ""

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { onSettings(vm.groupId) }) { Icon(Icons.Outlined.Settings, "Group settings") } })
        },
        floatingActionButton = {
            if (data is Loadable.Ready) ExtendedFloatingActionButton(onClick = { onPropose(vm.groupId) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Propose") })
        },
    ) { pad ->
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, onRetry = { vm.refresh() }, modifier = Modifier.padding(pad))
            is Loadable.Ready -> {
                val g = d.value
                val zone = runCatching { ZoneId.of(g.detail.group.timeZone) }.getOrDefault(ZoneId.systemDefault())
                LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message?.let { m -> item { ErrorBanner(m, onDismiss = vm::dismiss) } }
                    item {
                        Text("Times in ${g.detail.group.timeZone} · ${TimeFormat.zoneShort(zone, vm.clock.now())}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (g.detail.isAdmin && g.detail.group.adminHandoverPending) item {
                        InfoBanner("You're now the admin because the previous admin left.") {
                            Row {
                                TextButton(onClick = vm::declineAdmin) { Text("Decline") }
                                TextButton(onClick = vm::acceptAdmin) { Text("OK") }
                            }
                        }
                    }

                    item {
                        SwCard {
                            val slots = g.proposals.filter { it.proposal.status in setOf("PENDING", "APPROVED", "LIVE") }.map {
                                TimelineSlot(it.proposal.startAt, it.proposal.endAt, if (it.proposal.status == "PENDING") SlotKind.PENDING else SlotKind.APPROVED)
                            }
                            WeekTimeline(LocalDate.now(zone), zone, slots)
                            Legend(listOf("Approved" to MaterialTheme.colorScheme.primary, "Pending" to MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
                        }
                    }

                    if (g.joinRequests.isNotEmpty()) {
                        item { SectionTitle("Join requests") }
                        items(g.joinRequests, key = { it.requestId }) { JoinRequestCard(it, vm::voteJoin) }
                    }
                    if (g.adminActions.isNotEmpty()) {
                        item { SectionTitle("Admin requests") }
                        items(g.adminActions, key = { it.action.id }) { a ->
                            SwCard(onClick = { onOpenAdminAction(a.action.id) }, outlined = true) {
                                Text(adminTitle(a.action), style = MaterialTheme.typography.titleSmall)
                                a.summary?.let { s ->
                                    ProgressRing(s.approvals, s.required, if (s.canVote) "Your approval is needed" else "${s.approvals} of ${s.required} approved", size = 44.dp)
                                }
                            }
                        }
                    }

                    item { SectionTitle("Wallpapers") }
                    if (g.proposals.isEmpty()) item {
                        EmptyState(Icons.Outlined.Wallpaper, "Nothing scheduled", "Propose a photo and a time. It goes live only if everyone approves.")
                    }
                    items(g.proposals, key = { it.proposal.id }) { item ->
                        val p = item.proposal
                        SwCard(onClick = { onOpenProposal(p.id) }, outlined = p.status == "PENDING",
                            container = if (p.status == "LIVE") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(TimeFormat.window(p.startAt, p.endAt, zone), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Pill(statusLabel(p.status))
                            }
                            TimeFormat.localWindowIfDifferent(p.startAt, p.endAt, zone)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val by = g.detail.members.firstOrNull { it.userId == p.proposerId }?.name ?: "A former member"
                            Text("Proposed by $by", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.status == "PENDING") item.votes?.let { v ->
                                ProgressRing(v.approvals, v.required, "${v.approvals} of ${v.required} approved" + if (v.myVote == null) " · your vote is needed" else "", size = 44.dp)
                            }
                            item.delivery?.let { s ->
                                val active = s.members - s.skipped
                                Text("${s.ready} of $active ready" + (if (s.live > 0) " · ${s.live} live" else "") +
                                    (if (s.skipped > 0) " · ${s.skipped} skipped" else ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    item { SectionTitle("Members (${g.detail.members.size} of ${g.detail.group.maxMembers})") }
                    items(g.detail.members, key = { it.userId }) { MemberRow(it) }

                    item { SectionTitle("On my phone") }
                    item {
                        SwCard {
                            val mode = g.detail.me?.deliveryMode ?: "AUTO"
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(mode == "AUTO", { vm.setDeliveryMode("AUTO") }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Auto") }
                                SegmentedButton(mode == "TAP", { vm.setDeliveryMode("TAP") }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Tap to set") }
                            }
                            Text(if (mode == "AUTO") "Approving means the wallpaper is set automatically at the start time."
                                 else "At the start time you get a notification. Nothing changes until you tap “Set wallpaper”.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(m: Member) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (m.profile?.photoUrl != null) AsyncImage(m.profile.photoUrl, null, modifier = Modifier.padding(end = 12.dp).size(36.dp).clip(CircleShape))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.name, style = MaterialTheme.typography.bodyLarge)
                if (m.isAdmin) Pill("Admin")
            }
            Text(listOfNotNull(m.profile?.email, m.profile?.phone).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun JoinRequestCard(r: JoinRequestRow, onVote: (String, Boolean, String?) -> Unit) {
    var rejecting by remember { mutableStateOf(false) }
    SwCard(outlined = true) {
        Text("${r.name.ifBlank { "Someone" }} wants to join", style = MaterialTheme.typography.titleSmall)
        r.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ProgressRing(r.approvals, r.required, if (r.termsAccepted) "${r.approvals} of ${r.required} approved" else "Waiting for them to accept the upcoming wallpapers", size = 44.dp)
        if (r.myVote == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onVote(r.requestId, true, null) }) { Text("Approve") }
            OutlinedButton(onClick = { rejecting = true }) { Text("Reject") }
        } else Text("You voted", style = MaterialTheme.typography.bodySmall)
    }
    if (rejecting) ReasonDialog("Reject this join request?", "They'll see “Not approved” and your reason, but not your name.", "Reject",
        onConfirm = { rejecting = false; onVote(r.requestId, false, it) }, onDismiss = { rejecting = false })
}

fun statusLabel(s: String) = when (s) {
    "PENDING" -> "Voting"
    "APPROVED" -> "Approved"
    "LIVE" -> "Live"
    "REJECTED" -> "Not approved"
    "EXPIRED" -> "Expired"
    "CANCELLED" -> "Cancelled"
    "ENDED", "PURGED" -> "Ended"
    else -> s
}
