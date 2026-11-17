package com.syncedwallpapers.app.data.repo

import com.syncedwallpapers.app.data.remote.AdminActionDto
import com.syncedwallpapers.app.data.remote.AdminSummary
import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.GroupDto
import com.syncedwallpapers.app.data.remote.InviteDto
import com.syncedwallpapers.app.data.remote.JoinPreview
import com.syncedwallpapers.app.data.remote.JoinRequestRow
import com.syncedwallpapers.app.data.remote.MemberDto
import com.syncedwallpapers.app.data.remote.MyJoinRequest
import com.syncedwallpapers.app.data.remote.PendingCounts
import com.syncedwallpapers.app.data.remote.ProfileDto
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class Member(val userId: String, val profile: ProfileDto?, val joinedAt: Instant, val deliveryMode: String, val isAdmin: Boolean) {
    val name get() = profile?.name?.ifBlank { null } ?: "Member"
}

data class GroupDetail(val group: GroupDto, val members: List<Member>, val me: Member?) {
    val isAdmin get() = me?.isAdmin == true
}

@Singleton
class GroupRepository @Inject constructor(private val api: Api, private val profiles: ProfileRepository) {

    suspend fun myGroups(): List<GroupDto> =
        api.client.from("groups").select { order("created_at", Order.ASCENDING) }.decodeList()

    suspend fun pendingCounts(): Map<String, PendingCounts> =
        api.rpcList<PendingCounts>("my_pending_counts").associateBy { it.groupId }

    suspend fun detail(groupId: String): GroupDetail {
        val group = api.client.from("groups").select { filter { eq("id", groupId) } }.decodeSingle<GroupDto>()
        val rows = api.client.from("group_members").select { filter { eq("group_id", groupId) }; order("joined_at", Order.ASCENDING) }
            .decodeList<MemberDto>()
        val people = profiles.profiles(rows.map { it.userId })
        val members = rows.map { Member(it.userId, people[it.userId], it.joinedAt, it.deliveryMode, it.userId == group.adminId) }
        return GroupDetail(group, members, members.firstOrNull { it.userId == api.uidOrNull() })
    }

    suspend fun createGroup(name: String, timeZone: String): String = api.rpc("create_group") {
        put("p_name", name.trim()); put("p_time_zone", timeZone)
    }

    suspend fun setDeliveryMode(groupId: String, mode: String) = api.rpcUnit("set_delivery_mode") {
        put("p_group_id", groupId); put("p_mode", mode)
    }

    suspend fun createInvite(groupId: String, email: String? = null): InviteDto = api.rpc("create_invite") {
        put("p_group_id", groupId); put("p_email", email?.trim()?.ifBlank { null })
    }

    suspend fun redeemInvite(code: String): String = api.rpc("redeem_invite") { put("p_code", normalizeCode(code)) }

    suspend fun joinPreview(requestId: String): JoinPreview = api.rpc("join_request_preview") { put("p_request_id", requestId) }

    suspend fun acceptJoinTerms(requestId: String, accept: Boolean): JsonObject = api.rpc("accept_join_terms") {
        put("p_request_id", requestId); put("p_accept", accept)
    }

    suspend fun cancelJoinRequest(requestId: String) = api.rpcUnit("cancel_join_request") { put("p_request_id", requestId) }

    suspend fun voteJoin(requestId: String, approve: Boolean, reason: String?): JsonObject = api.rpc("vote_join") {
        put("p_request_id", requestId); put("p_decision", if (approve) "APPROVE" else "REJECT"); put("p_reason", reason)
    }

    suspend fun joinRequests(groupId: String): List<JoinRequestRow> = api.rpcList("group_join_requests") { put("p_group_id", groupId) }

    suspend fun myJoinRequests(): List<MyJoinRequest> = api.rpcList("my_join_requests")

    suspend fun leave(groupId: String) = api.rpcUnit("leave_group") { put("p_group_id", groupId) }

    // Admin --------------------------------------------------------------------------

    suspend fun adminActions(groupId: String): List<AdminActionDto> =
        api.client.from("admin_actions").select {
            filter { eq("group_id", groupId) }
            order("created_at", Order.DESCENDING)
            limit(30)
        }.decodeList()

    suspend fun adminAction(actionId: String): AdminActionDto =
        api.client.from("admin_actions").select { filter { eq("id", actionId) } }.decodeSingle()

    suspend fun createAdminAction(groupId: String, type: AdminType, payload: JsonObject): String = api.rpc("create_admin_action") {
        put("p_group_id", groupId); put("p_type", type.name); put("p_payload", payload)
    }

    suspend fun castAdminVote(actionId: String, approve: Boolean, reason: String?): AdminSummary = api.rpc("cast_admin_vote") {
        put("p_action_id", actionId); put("p_decision", if (approve) "APPROVE" else "REJECT"); put("p_reason", reason)
    }

    suspend fun cancelAdminAction(actionId: String) = api.rpcUnit("cancel_admin_action") { put("p_action_id", actionId) }

    suspend fun adminSummary(actionId: String): AdminSummary = api.rpc("admin_action_summary") { put("p_action_id", actionId) }

    suspend fun declineAdmin(groupId: String) = api.rpcUnit("decline_admin") { put("p_group_id", groupId) }

    suspend fun acceptAdmin(groupId: String) = api.rpcUnit("accept_admin") { put("p_group_id", groupId) }

    companion object {
        /** Accepts "abcd-efgh", a full invite link, or extra spaces. */
        fun normalizeCode(input: String): String {
            val tail = input.trim().substringAfterLast('/').substringAfterLast("code=")
            return tail.uppercase().filter { it.isLetterOrDigit() }
        }

        fun inviteLink(code: String) = "syncedwallpapers://join/$code"
    }
}

enum class AdminType(val label: String) {
    RENAME("Rename the group"),
    TIMEZONE("Change the time zone"),
    MEMBER_LIMIT("Change the member limit"),
    REMOVE_MEMBER("Remove a member"),
    TRANSFER_ADMIN("Hand over admin"),
    DELETE_GROUP("Delete the group"),
}

