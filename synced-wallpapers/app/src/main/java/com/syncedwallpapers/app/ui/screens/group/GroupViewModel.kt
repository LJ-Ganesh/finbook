package com.syncedwallpapers.app.ui.screens.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.data.remote.AdminActionDto
import com.syncedwallpapers.app.data.remote.AdminSummary
import com.syncedwallpapers.app.data.remote.DeliverySummary
import com.syncedwallpapers.app.data.remote.InviteDto
import com.syncedwallpapers.app.data.remote.JoinRequestRow
import com.syncedwallpapers.app.data.remote.ProposalDto
import com.syncedwallpapers.app.data.remote.VoteSummary
import com.syncedwallpapers.app.data.repo.GroupDetail
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.data.repo.RealtimeRefresher
import com.syncedwallpapers.app.delivery.DeliveryEngine
import com.syncedwallpapers.app.delivery.DeliveryWork
import com.syncedwallpapers.app.ui.Loadable
import com.syncedwallpapers.app.ui.load
import com.syncedwallpapers.app.ui.nav.GroupRoute
import com.syncedwallpapers.app.ui.runAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProposalItem(val proposal: ProposalDto, val votes: VoteSummary?, val delivery: DeliverySummary?)
data class AdminItem(val action: AdminActionDto, val summary: AdminSummary?)

data class GroupData(
    val detail: GroupDetail,
    val proposals: List<ProposalItem>,
    val adminActions: List<AdminItem>,
    val joinRequests: List<JoinRequestRow>,
)

@HiltViewModel
class GroupViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val groups: GroupRepository,
    private val proposals: ProposalRepository,
    private val engine: DeliveryEngine,
    private val work: DeliveryWork,
    val clock: ServerClock,
    realtime: RealtimeRefresher,
) : ViewModel() {
    val groupId: String = handle.toRoute<GroupRoute>().groupId

    private val _data = MutableStateFlow<Loadable<GroupData>>(Loadable.Loading)
    val data = _data.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _left = MutableStateFlow(false)
    /** True when the group is gone for us (left, removed, deleted). */
    val left = _left.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { realtime.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val r = load { fetch() }
        if (r is Loadable.Failed && _data.value is Loadable.Ready) {
            // Keep showing the old data; the group may have been deleted meanwhile.
            if (runCatching { groups.myGroups().none { it.id == groupId } }.getOrDefault(false)) _left.value = true
            return@launch
        }
        _data.value = r
    }

    private suspend fun fetch(): GroupData = coroutineScope {
        val detail = async { groups.detail(groupId) }
        val list = proposals.forGroup(groupId, clock.now())
        val items = list.map { p ->
            async {
                val votes = runCatching { proposals.summary(p.id) }.getOrNull()
                val delivery = if (p.status in setOf("APPROVED", "LIVE")) runCatching { proposals.deliverySummary(p.id) }.getOrNull() else null
                ProposalItem(p, votes, delivery)
            }
        }
        val actions = async {
            groups.adminActions(groupId).filter { it.status == "PENDING" }.map { a -> AdminItem(a, runCatching { groups.adminSummary(a.id) }.getOrNull()) }
        }
        val joins = async { groups.joinRequests(groupId) }
        GroupData(detail.await(), items.awaitAll(), actions.await(), joins.await())
    }

    fun setDeliveryMode(mode: String) = act { groups.setDeliveryMode(groupId, mode); work.reconcileNow() }
    fun voteJoin(requestId: String, approve: Boolean, reason: String?) = act { groups.voteJoin(requestId, approve, reason) }
    fun voteAdmin(actionId: String, approve: Boolean, reason: String?) = act { groups.castAdminVote(actionId, approve, reason) }
    fun acceptAdmin() = act { groups.acceptAdmin(groupId) }
    fun declineAdmin() = act { groups.declineAdmin(groupId) }
    fun skip(proposalId: String) = act { engine.skip(proposalId) }

    fun leave() = viewModelScope.launch {
        val err = runAction {
            groups.leave(groupId)
            engine.forgetGroup(groupId) // alarms cancelled, images deleted, wallpaper restored now
        }
        if (err == null) _left.value = true else _message.value = err
    }

    fun dismiss() { _message.value = null }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runAction(block)?.let { _message.value = it }
        refresh()
    }
}

@HiltViewModel
class InviteViewModel @Inject constructor(private val groups: GroupRepository) : ViewModel() {
    private val _invite = MutableStateFlow<InviteDto?>(null)
    val invite = _invite.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun create(groupId: String, email: String?) = viewModelScope.launch {
        _busy.value = true
        val err = runAction { _invite.value = groups.createInvite(groupId, email) }
        _message.value = err ?: if (!email.isNullOrBlank()) {
            if (_invite.value?.joinRequestId != null) "Invite sent to $email. They'll be asked to review the group's schedules."
            else "$email hasn't signed up yet. The invite will be waiting when they do."
        } else null
        _busy.value = false
    }
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(private val groups: GroupRepository) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun create(name: String, zone: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        _busy.value = true
        var id: String? = null
        _error.value = runAction { id = groups.createGroup(name, zone) }
        _busy.value = false
        id?.let(onCreated)
    }
}
