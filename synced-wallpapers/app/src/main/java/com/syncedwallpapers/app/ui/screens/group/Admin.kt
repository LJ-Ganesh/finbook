package com.syncedwallpapers.app.ui.screens.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.AdminActionDto
import com.syncedwallpapers.app.data.remote.AdminSummary
import com.syncedwallpapers.app.data.remote.ProposalDto
import com.syncedwallpapers.app.data.repo.AdminType
import com.syncedwallpapers.app.data.repo.GroupDetail
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.ClickableRow
import com.syncedwallpapers.app.ui.components.ConfirmDialog
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.PrimaryButton
import com.syncedwallpapers.app.ui.components.ProgressRing
import com.syncedwallpapers.app.ui.components.ReasonDialog
import com.syncedwallpapers.app.ui.components.SectionTitle
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.load
import com.syncedwallpapers.app.ui.nav.AdminActionRoute
import com.syncedwallpapers.app.ui.nav.NewAdminActionRoute
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

fun adminTitle(a: AdminActionDto): String = when (a.type) {
    "RENAME" -> "Rename: “${a.payload.s("previous")}” → “${a.payload.s("name")}”"
    "TIMEZONE" -> "Time zone: ${a.payload.s("previous")} → ${a.payload.s("time_zone")}"
    "MEMBER_LIMIT" -> "Member limit: ${a.payload.s("previous")} → ${a.payload.s("max_members")}"
    "REMOVE_MEMBER" -> "Remove ${a.payload.s("member_name") ?: "a member"}"
    "TRANSFER_ADMIN" -> "Hand over admin to ${a.payload.s("member_name") ?: "a member"}"
    "DELETE_GROUP" -> "Delete the group"
    else -> a.type
}

// ------------------------------------------------------------------ Group settings

@Composable
fun GroupSettingsScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onInvite: (String) -> Unit,
    onNewAction: (String, AdminType) -> Unit,
    onOpenAction: (String) -> Unit,
    vm: GroupViewModel = hiltViewModel(),
) {
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    val left by vm.left.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(left) { if (left) onLeft() }

    Scaffold(topBar = { TopAppBar(title = { Text("Group settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, { vm.refresh() }, Modifier.padding(pad))
            is Loadable.Ready -> {
                val g = d.value.detail
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message?.let { ErrorBanner(it, onDismiss = vm::dismiss) }
                    SwCard {
                        ClickableRow("Name", g.group.name, onClick = {})
                        ClickableRow("Time zone", g.group.timeZone, onClick = {})
                        ClickableRow("Member limit", "${g.members.size} of ${g.group.maxMembers}", onClick = {})
                        ClickableRow("Admin", g.members.firstOrNull { it.isAdmin }?.name ?: "—", onClick = {})
                    }
                    if (g.isAdmin) {
                        SectionTitle("Invite")
                        SwCard(onClick = { onInvite(g.group.id) }) {
                            Text("Invite someone", style = MaterialTheme.typography.titleSmall)
                            Text("By code, link or Google email. Joining still needs every member's approval.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SectionTitle("Manage group")
                        SwCard {
                            Text("Each change takes effect only after every other member approves.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AdminType.entries.forEach { t ->
                                val pending = d.value.adminActions.firstOrNull { it.action.type == t.name }
                                ClickableRow(t.label, pending?.let { "Waiting for approval" },
                                    onClick = { if (pending != null) onOpenAction(pending.action.id) else onNewAction(g.group.id, t) })
                            }
                        }
                    } else {
                        InfoBanner("Only the admin can rename the group, change its time zone or member limit, remove members or invite. Their requests appear as approval cards.")
                    }
                    SectionTitle("Leave")
                    OutlinedButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) { Text("Leave group", color = MaterialTheme.colorScheme.error) }
                }
                if (confirmLeave) ConfirmDialog("Leave ${g.group.name}?",
                    "You leave right away. Your proposals and schedules in this group are cancelled for everyone, and any of its wallpapers on your phone are removed." +
                        if (g.isAdmin) " Admin passes to the longest-standing member." else "",
                    "Leave", onConfirm = { confirmLeave = false; vm.leave() }, onDismiss = { confirmLeave = false })
            }
        }
    }
}

// ------------------------------------------------------------------ Invite

@Composable
fun InviteScreen(groupId: String, onBack: () -> Unit, vm: InviteViewModel = hiltViewModel()) {
    val invite by vm.invite.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    var email by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Invite") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { InfoBanner(it) }
            Text("Anyone who joins must accept the group's upcoming wallpapers, and every member must approve them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwCard {
                Text("Code or link", style = MaterialTheme.typography.titleSmall)
                Text("Single use, expires in 24 hours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val inv = invite
                if (inv != null && inv.email == null) {
                    Text(inv.code.chunked(4).joinToString("-"), style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(GroupRepository.inviteLink(inv.code))) }) { Text("Copy link") }
                        OutlinedButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, "Join my group on Synced Wallpapers: ${GroupRepository.inviteLink(inv.code)} (code ${inv.code})")
                            context.startActivity(android.content.Intent.createChooser(send, "Share invite"))
                        }) { Text("Share") }
                    }
                }
                PrimaryButton(if (inv == null || inv.email != null) "Create code" else "Create another", onClick = { vm.create(groupId, null) }, busy = busy)
            }
            SwCard {
                Text("Google email", style = MaterialTheme.typography.titleSmall)
                Text("If they haven't signed up yet, the invite waits until they do.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(email, { email = it.trim() }, label = { Text("name@gmail.com") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                PrimaryButton("Send invite", onClick = { vm.create(groupId, email) }, enabled = email.contains("@"), busy = busy)
            }
        }
    }
}

// ------------------------------------------------------------------ Create group

@Composable
fun CreateGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: CreateGroupViewModel = hiltViewModel()) {
    var name by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("New group") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { ErrorBanner(it) }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ZonePicker(zone, { zone = it })
            Text("Everyone enters and sees times in this zone (plus their own local time if it differs). You'll be the admin, with one vote like everyone else.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Create", onClick = { vm.create(name, zone, onCreated) }, enabled = name.isNotBlank(), busy = busy)
        }
    }
}

/** Searchable IANA zone picker. */
@Composable
fun ZonePicker(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    val all = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV") }.sorted() }
    val matches = remember(query) { all.filter { it.contains(query.replace(' ', '_'), ignoreCase = true) }.take(30) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(query, { query = it; expanded = true }, label = { Text("Time zone") }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable))
        ExposedDropdownMenu(expanded, { expanded = false }) {
            matches.forEach { z ->
                DropdownMenuItem(text = { Text(z.replace('_', ' ')) }, onClick = { onChange(z); query = z; expanded = false })
            }
        }
    }
}

// ------------------------------------------------------------------ New admin action

@HiltViewModel
class NewAdminActionViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val groups: GroupRepository,
    private val proposals: ProposalRepository,
    val clock: ServerClock,
) : ViewModel() {
    val route = handle.toRoute<NewAdminActionRoute>()
    val type = AdminType.valueOf(route.type)
    private val _detail = MutableStateFlow<Loadable<Pair<GroupDetail, List<ProposalDto>>>>(Loadable.Loading)
    val detail = _detail.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init { viewModelScope.launch {
        _detail.value = load { groups.detail(route.groupId) to proposals.forGroup(route.groupId, clock.now()).filter { it.status in setOf("APPROVED", "LIVE") } }
    } }

    fun submit(payload: JsonObject, onDone: (String) -> Unit) = viewModelScope.launch {
        _busy.value = true
        var id: String? = null
        _error.value = runAction { id = groups.createAdminAction(route.groupId, type, payload) }
        _busy.value = false
        id?.let(onDone)
    }
}

@Composable
fun NewAdminActionScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: NewAdminActionViewModel = hiltViewModel()) {
    val detail by vm.detail.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf("") }
    var member by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(vm.type.label) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        when (val d = detail) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, null, Modifier.padding(pad))
            is Loadable.Ready -> {
                val (g, upcoming) = d.value
                val others = g.members.filter { !it.isAdmin }
                Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { ErrorBanner(it) }
                    InfoBanner(if (others.isEmpty()) "You're the only member, so this applies straight away."
                               else "This needs approval from all ${others.size} other member${if (others.size == 1) "" else "s"}" +
                                   (if (vm.type == AdminType.REMOVE_MEMBER) " (except the person being removed, who isn't told until the outcome)" else "") +
                                   ". It expires after 48 hours without everyone's approval.")
                    when (vm.type) {
                        AdminType.RENAME -> OutlinedTextField(text, { text = it.take(40) }, label = { Text("New name (now “${g.group.name}”)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        AdminType.TIMEZONE -> {
                            ZonePicker(zone.ifBlank { g.group.timeZone }) { zone = it }
                            if (zone.isNotBlank() && zone != g.group.timeZone) TimeZonePreview(upcoming, ZoneId.of(g.group.timeZone), ZoneId.of(zone))
                        }
                        AdminType.MEMBER_LIMIT -> OutlinedTextField(text, { text = it.filter(Char::isDigit).take(2) },
                            label = { Text("New limit (${g.members.size}–${AppConfig.MAX_MEMBERS}, now ${g.group.maxMembers})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        AdminType.REMOVE_MEMBER, AdminType.TRANSFER_ADMIN -> {
                            if (others.isEmpty()) Text("There's nobody else in the group.")
                            others.forEach { m ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(member == m.userId, { member = m.userId })
                                    Text(m.name)
                                }
                            }
                            if (vm.type == AdminType.TRANSFER_ADMIN) Text("The new admin must also accept (their approval counts as acceptance).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AdminType.DELETE_GROUP -> Text("When approved, every schedule is cancelled and all members are removed, as if everyone left.")
                    }
                    val payload = when (vm.type) {
                        AdminType.RENAME -> if (text.isBlank()) null else buildJsonObject { put("name", text.trim()) }
                        AdminType.TIMEZONE -> if (zone.isBlank() || zone == g.group.timeZone) null else buildJsonObject { put("time_zone", zone) }
                        AdminType.MEMBER_LIMIT -> text.toIntOrNull()?.takeIf { it in g.members.size..AppConfig.MAX_MEMBERS }?.let { n -> buildJsonObject { put("max_members", n) } }
                        AdminType.REMOVE_MEMBER, AdminType.TRANSFER_ADMIN -> member?.let { m -> buildJsonObject { put("user_id", m) } }
                        AdminType.DELETE_GROUP -> buildJsonObject { }
                    }
                    PrimaryButton("Ask for approval", onClick = { confirm = true }, enabled = payload != null, busy = busy)
                    if (confirm && payload != null) ConfirmDialog("Ask the group?", "Nothing changes until every other member approves.", "Send",
                        destructive = vm.type == AdminType.DELETE_GROUP || vm.type == AdminType.REMOVE_MEMBER,
                        onConfirm = { confirm = false; vm.submit(payload, onCreated) }, onDismiss = { confirm = false })
                }
            }
        }
    }
}

/** Upcoming schedules keep their absolute instants; only the displayed time changes. */
@Composable
fun TimeZonePreview(upcoming: List<ProposalDto>, from: ZoneId, to: ZoneId) {
    SwCard {
        Text("Upcoming schedules stay at the same moment. Only how times are shown changes:", style = MaterialTheme.typography.bodySmall)
        if (upcoming.isEmpty()) Text("No upcoming schedules.", style = MaterialTheme.typography.bodySmall)
        upcoming.forEach { p ->
            Text("${TimeFormat.window(p.startAt, p.endAt, from)}\n→ ${TimeFormat.window(p.startAt, p.endAt, to)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------ Admin action card / screen

@HiltViewModel
class AdminActionViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val groups: GroupRepository,
    private val proposals: ProposalRepository,
    val clock: ServerClock,
    realtime: RealtimeRefresher,
) : ViewModel() {
    val actionId = handle.toRoute<AdminActionRoute>().actionId
    data class Data(val action: AdminActionDto, val summary: AdminSummary, val detail: GroupDetail, val upcoming: List<ProposalDto>)
    private val _data = MutableStateFlow<Loadable<Data>>(Loadable.Loading)
    val data = _data.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { if (it == "admin_actions") refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        _data.value = load {
            val a = groups.adminAction(actionId)
            Data(a, groups.adminSummary(actionId), groups.detail(a.groupId),
                if (a.type == "TIMEZONE") proposals.forGroup(a.groupId, clock.now()).filter { it.status in setOf("APPROVED", "LIVE") } else emptyList())
        }
    }

    fun vote(approve: Boolean, reason: String?) = viewModelScope.launch {
        _message.value = runAction { groups.castAdminVote(actionId, approve, reason) }
        refresh()
    }
    fun cancel() = viewModelScope.launch { _message.value = runAction { groups.cancelAdminAction(actionId) }; refresh() }
}

@Composable
fun AdminActionScreen(onBack: () -> Unit, vm: AdminActionViewModel = hiltViewModel()) {
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    var rejecting by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Admin request") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, { vm.refresh() }, Modifier.padding(pad))
            is Loadable.Ready -> {
                val (a, s, g, upcoming) = d.value
                val admin = g.members.firstOrNull { it.userId == a.createdBy }?.name ?: "The admin"
                Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message?.let { ErrorBanner(it) }
                    Text(adminTitle(a), style = MaterialTheme.typography.titleLarge)
                    Text("Started by $admin (admin) in ${g.group.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (a.type == "TIMEZONE") {
                        val from = runCatching { ZoneId.of(a.payload.s("previous")) }.getOrNull()
                        val to = runCatching { ZoneId.of(a.payload.s("time_zone")) }.getOrNull()
                        if (from != null && to != null) TimeZonePreview(upcoming, from, to)
                    }
                    if (a.type == "DELETE_GROUP") InfoBanner("If approved, every schedule is cancelled and everyone is removed.")
                    ProgressRing(s.approvals, s.required, "${s.approvals} of ${s.required} approved")
                    val left = s.deadline?.let { Duration.between(vm.clock.now(), it) }
                    Text(when (s.status) {
                        "PENDING" -> "Expires in ${left?.let { TimeFormat.remaining(it) } ?: "48h"} unless everyone approves."
                        "APPROVED" -> "Approved and applied."
                        "REJECTED" -> "Not approved." + if (s.reasons.isNotEmpty()) " Reasons: " + s.reasons.joinToString("; ") { "“$it”" } else ""
                        "EXPIRED" -> "Expired: not everyone approved within 48 hours."
                        else -> "Cancelled."
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (s.canVote) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.vote(true, null) }, modifier = Modifier.weight(1f)) { Text(if (a.type == "TRANSFER_ADMIN" && a.payload.s("user_id") == g.me?.userId) "Accept" else "Approve") }
                        OutlinedButton(onClick = { rejecting = true }, modifier = Modifier.weight(1f)) { Text("Reject") }
                    } else if (s.myVote != null) Text("You voted.", style = MaterialTheme.typography.bodySmall)
                    if (g.isAdmin && s.status == "PENDING") TextButton(onClick = { cancelling = true }) { Text("Cancel request") }
                }
                if (rejecting) ReasonDialog("Reject?", "Rejections are anonymous.", "Reject", onConfirm = { rejecting = false; vm.vote(false, it) }, onDismiss = { rejecting = false })
                if (cancelling) ConfirmDialog("Cancel this request?", "Nothing will change.", "Cancel request", onConfirm = { cancelling = false; vm.cancel() }, onDismiss = { cancelling = false })
            }
        }
    }
}
