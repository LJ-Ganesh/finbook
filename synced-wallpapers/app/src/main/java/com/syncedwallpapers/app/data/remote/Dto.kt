package com.syncedwallpapers.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

/** Postgres timestamptz ("2026-11-18T16:59:34.377245+00:00") ⇄ java.time.Instant. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())
    fun parse(s: String): Instant = runCatching { OffsetDateTime.parse(s).toInstant() }
        .getOrElse { Instant.parse(s) }
}

typealias Ts = @Serializable(with = InstantSerializer::class) Instant

@Serializable
data class ProfileDto(
    val id: String,
    val email: String? = null,
    val name: String = "",
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("phone_optional") val phone: String? = null,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("max_members") val maxMembers: Int,
    @SerialName("admin_id") val adminId: String,
    @SerialName("admin_handover_pending") val adminHandoverPending: Boolean = false,
    @SerialName("created_at") val createdAt: Ts,
)

@Serializable
data class MemberDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: Ts,
    @SerialName("delivery_mode") val deliveryMode: String,
)

@Serializable
data class ProposalDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("proposer_id") val proposerId: String? = null,
    @SerialName("start_at") val startAt: Ts,
    @SerialName("end_at") val endAt: Ts,
    val target: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("image_ready") val imageReady: Boolean = false,
    @SerialName("focal_x") val focalX: Float = 0.5f,
    @SerialName("focal_y") val focalY: Float = 0.5f,
    @SerialName("reminder_text") val reminderText: String? = null,
    @SerialName("reminder_style") val reminderStyle: Int = 0,
    val status: String,
    @SerialName("vote_deadline") val voteDeadline: Ts,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    @SerialName("created_at") val createdAt: Ts,
)

@Serializable
data class AdminActionDto(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_by") val createdBy: String,
    val status: String,
    @SerialName("result_note") val resultNote: String? = null,
    val deadline: Ts,
    @SerialName("created_at") val createdAt: Ts,
)

@Serializable
data class VoteSummary(
    @SerialName("proposal_id") val proposalId: String,
    val status: String,
    val required: Int,
    val approvals: Int,
    val rejections: Int,
    @SerialName("my_vote") val myVote: String? = null,
    val reasons: List<String> = emptyList(),
    @SerialName("skipped_count") val skippedCount: Int = 0,
)

@Serializable
data class AdminSummary(
    @SerialName("action_id") val actionId: String,
    val status: String,
    val deadline: Ts? = null,
    val required: Int = 0,
    val approvals: Int = 0,
    val rejections: Int = 0,
    @SerialName("can_vote") val canVote: Boolean = false,
    @SerialName("my_vote") val myVote: String? = null,
    val reasons: List<String> = emptyList(),
    @SerialName("group_deleted") val groupDeleted: Boolean = false,
)

@Serializable
data class Commitment(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("start_at") val startAt: Ts,
    @SerialName("end_at") val endAt: Ts,
    val status: String,
)

@Serializable
data class Slot(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("start_at") val startAt: Ts,
    @SerialName("end_at") val endAt: Ts,
    val status: String,
)

@Serializable
data class PendingCounts(
    @SerialName("group_id") val groupId: String,
    val proposals: Int = 0,
    @SerialName("admin_actions") val adminActions: Int = 0,
    @SerialName("join_requests") val joinRequests: Int = 0,
) { val total get() = proposals + adminActions + joinRequests }

@Serializable
data class CreatedProposal(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("image_ready") val imageReady: Boolean,
)

@Serializable
data class InviteDto(
    @SerialName("invite_id") val inviteId: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: Ts,
    val email: String? = null,
    @SerialName("join_request_id") val joinRequestId: String? = null,
)

@Serializable
data class JoinRequestRow(
    @SerialName("request_id") val requestId: String,
    @SerialName("user_id") val userId: String,
    val name: String = "",
    val email: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("created_at") val createdAt: Ts,
    @SerialName("terms_accepted") val termsAccepted: Boolean,
    val required: Int,
    val approvals: Int,
    @SerialName("my_vote") val myVote: String? = null,
)

@Serializable
data class MyJoinRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    val status: String,
    @SerialName("terms_accepted") val termsAccepted: Boolean,
    @SerialName("created_at") val createdAt: Ts,
)

@Serializable
data class JoinSchedule(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("start_at") val startAt: Ts,
    @SerialName("end_at") val endAt: Ts,
    val target: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("focal_x") val focalX: Float = 0.5f,
    @SerialName("focal_y") val focalY: Float = 0.5f,
    @SerialName("reminder_text") val reminderText: String? = null,
    @SerialName("reminder_style") val reminderStyle: Int = 0,
    val conflicts: List<Commitment> = emptyList(),
)

@Serializable
data class JoinPreview(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("terms_accepted_at") val termsAcceptedAt: Ts? = null,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("member_count") val memberCount: Int,
    val required: Int,
    val approvals: Int,
    val schedules: List<JoinSchedule> = emptyList(),
)

@Serializable
data class DeliverySummary(
    @SerialName("proposal_id") val proposalId: String,
    val members: Int,
    val skipped: Int,
    val ready: Int,
    val live: Int,
    val people: List<DeliveryPerson> = emptyList(),
)

@Serializable
data class DeliveryPerson(@SerialName("user_id") val userId: String, val name: String, val state: String)

/** Row of my_schedules(): what a device must deliver. */
@Serializable
data class ScheduleDto(
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    @SerialName("time_zone") val timeZone: String,
    @SerialName("start_at") val startAt: Ts,
    @SerialName("end_at") val endAt: Ts,
    val target: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("focal_x") val focalX: Float = 0.5f,
    @SerialName("focal_y") val focalY: Float = 0.5f,
    @SerialName("reminder_text") val reminderText: String? = null,
    @SerialName("reminder_style") val reminderStyle: Int = 0,
    val status: String,
    @SerialName("delivery_mode") val deliveryMode: String,
    val skipped: Boolean,
    @SerialName("proposer_id") val proposerId: String? = null,
)

@Serializable
data class OutboxDto(
    val id: Long,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: Ts,
    @SerialName("read_at") val readAt: Ts? = null,
)
