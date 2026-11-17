package com.syncedwallpapers.app.ui.screens.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.remote.JoinPreview
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.components.ConfirmDialog
import com.syncedwallpapers.app.ui.components.ErrorBanner
import com.syncedwallpapers.app.ui.components.ErrorState
import com.syncedwallpapers.app.ui.components.InfoBanner
import com.syncedwallpapers.app.ui.components.Loading
import com.syncedwallpapers.app.ui.components.PhoneFrame
import com.syncedwallpapers.app.ui.components.PrimaryButton
import com.syncedwallpapers.app.ui.components.ProgressRing
import com.syncedwallpapers.app.ui.components.SecureScreen
import com.syncedwallpapers.app.ui.components.SwCard
import com.syncedwallpapers.app.ui.load
import com.syncedwallpapers.app.ui.nav.JoinCodeRoute
import com.syncedwallpapers.app.ui.nav.JoinReviewRoute
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class JoinCodeViewModel @Inject constructor(handle: SavedStateHandle, private val groups: GroupRepository) : ViewModel() {
    val initialCode = handle.toRoute<JoinCodeRoute>().code ?: ""
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun redeem(code: String, onRequest: (String) -> Unit) = viewModelScope.launch {
        _busy.value = true
        var id: String? = null
        _error.value = runAction { id = groups.redeemInvite(code) }
        _busy.value = false
        id?.let(onRequest)
    }
}

@Composable
fun JoinCodeScreen(onBack: () -> Unit, onRequest: (String) -> Unit, vm: JoinCodeViewModel = hiltViewModel()) {
    var code by rememberSaveable { mutableStateOf(vm.initialCode) }
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(Unit) { if (vm.initialCode.isNotBlank()) vm.redeem(vm.initialCode, onRequest) }
    Scaffold(topBar = { TopAppBar(title = { Text("Join a group") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { ErrorBanner(it) }
            OutlinedTextField(code, { code = it.take(80) }, label = { Text("Invite code or link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("You'll see the group's upcoming wallpapers and must accept them. Then every member must approve you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("Continue", onClick = { vm.redeem(code, onRequest) }, enabled = GroupRepository.normalizeCode(code).length >= 6, busy = busy)
        }
    }
}

@HiltViewModel
class JoinReviewViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val groups: GroupRepository,
    private val proposals: ProposalRepository,
    private val engine: DeliveryEngine,
    realtime: RealtimeRefresher,
) : ViewModel() {
    val requestId = handle.toRoute<JoinReviewRoute>().requestId
    data class Data(val preview: JoinPreview, val urls: Map<String, String>)
    private val _data = MutableStateFlow<Loadable<Data>>(Loadable.Loading)
    val data = _data.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { if (it == "join_requests" || it == "notification_outbox") refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val r = load {
            val p = groups.joinPreview(requestId)
            Data(p, p.schedules.mapNotNull { s -> runCatching { s.proposalId to proposals.signedUrl(s.imagePath) }.getOrNull() }.toMap())
        }
        if (r is Loadable.Ready || _data.value !is Loadable.Ready) _data.value = r
    }

    fun accept(accept: Boolean) = viewModelScope.launch {
        _busy.value = true
        _message.value = runAction { groups.acceptJoinTerms(requestId, accept) }
        _busy.value = false
        refresh()
    }

    /** Resolve a conflict by skipping the other schedule on this phone. */
    fun skipOther(proposalId: String) = viewModelScope.launch {
        _message.value = runAction { engine.skip(proposalId) }
        refresh()
    }

    fun cancel() = viewModelScope.launch { _message.value = runAction { groups.cancelJoinRequest(requestId) }; refresh() }
}

@Composable
fun JoinReviewScreen(onBack: () -> Unit, onJoined: (String) -> Unit, vm: JoinReviewViewModel = hiltViewModel()) {
    SecureScreen()
    val data by vm.data.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    var declining by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Join") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        when (val d = data) {
            Loadable.Loading -> Loading(Modifier.padding(pad))
            is Loadable.Failed -> ErrorState(d.error, { vm.refresh() }, Modifier.padding(pad))
            is Loadable.Ready -> {
                val p = d.value.preview
                LaunchedEffect(p.status) { if (p.status == "APPROVED") onJoined(p.groupId) }
                val zone = runCatching { ZoneId.of(p.timeZone) }.getOrDefault(ZoneId.systemDefault())
                val conflicts = p.schedules.flatMap { it.conflicts }
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    message?.let { ErrorBanner(it) }
                    Text(p.groupName, style = MaterialTheme.typography.headlineSmall)
                    Text("${p.memberCount} members · ${p.timeZone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (p.status) {
                        "PENDING" -> if (p.termsAcceptedAt != null) ProgressRing(p.approvals, p.required, "Waiting for members: ${p.approvals} of ${p.required} approved")
                        "REJECTED" -> InfoBanner("Not approved. You can't join this group with this request.")
                        "CANCELLED" -> InfoBanner("This join request was cancelled.")
                        "EXPIRED" -> InfoBanner("This join request expired.")
                    }
                    if (p.status == "PENDING" && p.termsAcceptedAt == null) {
                        Text(if (p.schedules.isEmpty()) "There are no upcoming wallpapers right now." else
                            "To join you must accept these upcoming wallpapers on your phone:", style = MaterialTheme.typography.bodyMedium)
                    }
                    p.schedules.forEach { s ->
                        SwCard {
                            d.value.urls[s.proposalId]?.let { url ->
                                PhoneFrame(Modifier.align(Alignment.CenterHorizontally), url = url, cacheKey = s.proposalId, focalX = s.focalX, focalY = s.focalY,
                                    reminder = s.reminderText, reminderStyle = s.reminderStyle)
                            }
                            Text(TimeFormat.window(s.startAt, s.endAt, zone), style = MaterialTheme.typography.titleSmall)
                            TimeFormat.localWindowIfDifferent(s.startAt, s.endAt, zone)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            s.conflicts.forEach { c ->
                                InfoBanner("Overlaps with “${c.groupName}” ${TimeFormat.window(c.startAt, c.endAt, ZoneId.systemDefault())} on your phone.",
                                    color = MaterialTheme.colorScheme.errorContainer) {
                                    if (c.status != "PENDING") OutlinedButton(onClick = { vm.skipOther(c.proposalId) }) { Text("Skip that one") }
                                }
                            }
                        }
                    }
                    if (p.status == "PENDING" && p.termsAcceptedAt == null) {
                        if (conflicts.isNotEmpty()) Text("Resolve the overlaps first: skip the other schedule on your phone, or leave that group.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        PrimaryButton("Accept and ask to join", onClick = { vm.accept(true) }, enabled = conflicts.isEmpty(), busy = busy)
                        OutlinedButton(onClick = { declining = true }, modifier = Modifier.fillMaxWidth()) { Text("Don't join") }
                    } else if (p.status == "PENDING") {
                        OutlinedButton(onClick = { declining = true }, modifier = Modifier.fillMaxWidth()) { Text("Cancel join request") }
                    }
                }
                if (declining) ConfirmDialog("Cancel joining?", "Your join request is cancelled.", "Cancel request",
                    onConfirm = { declining = false; if (p.termsAcceptedAt == null) vm.accept(false) else vm.cancel() }, onDismiss = { declining = false })
            }
        }
    }
}
