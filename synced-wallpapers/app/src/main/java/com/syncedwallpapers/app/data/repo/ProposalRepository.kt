package com.syncedwallpapers.app.data.repo

import com.syncedwallpapers.app.core.AppConfig
import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.Commitment
import com.syncedwallpapers.app.data.remote.CreatedProposal
import com.syncedwallpapers.app.data.remote.DeliverySummary
import com.syncedwallpapers.app.data.remote.InstantSerializer
import com.syncedwallpapers.app.data.remote.ProposalDto
import com.syncedwallpapers.app.data.remote.ScheduleDto
import com.syncedwallpapers.app.data.remote.Slot
import com.syncedwallpapers.app.data.remote.VoteSummary
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

enum class Target(val label: String) { HOME("Home screen"), LOCK("Lock screen"), BOTH("Both") }

data class NewProposal(
    val groupId: String,
    val start: Instant,
    val end: Instant,
    val target: Target,
    val focalX: Float,
    val focalY: Float,
    val reminderText: String?,
    val reminderStyle: Int,
    /** Re-encoded, metadata-free JPEG (see ImageProcessor.prepareUpload). Null when re-proposing. */
    val jpeg: ByteArray?,
    val replaces: String? = null,
)

@Serializable
data class TimeSuggestionDto(
    val id: String,
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("suggested_start") @Serializable(with = InstantSerializer::class) val start: Instant,
    @SerialName("suggested_end") @Serializable(with = InstantSerializer::class) val end: Instant,
)

@Singleton
class ProposalRepository @Inject constructor(private val api: Api, private val json: Json) {

    /** Active proposals plus recently closed ones (so the proposer sees "Not approved"). No history after the end. */
    suspend fun forGroup(groupId: String, now: Instant): List<ProposalDto> =
        api.client.from("proposals").select {
            filter {
                eq("group_id", groupId)
                isIn("status", listOf("PENDING", "APPROVED", "LIVE", "REJECTED", "EXPIRED", "CANCELLED"))
                gt("end_at", now.toString())
            }
            order("start_at", Order.ASCENDING)
        }.decodeList()

    suspend fun get(id: String): ProposalDto =
        api.client.from("proposals").select { filter { eq("id", id) } }.decodeSingle()

    suspend fun summary(id: String): VoteSummary = api.rpc("proposal_vote_summary") { put("p_proposal_id", id) }

    suspend fun conflicts(id: String): List<Commitment> =
        json.decodeFromString(api.rpcRaw("proposal_conflicts", kotlinx.serialization.json.buildJsonObject { put("p_proposal_id", id) }))

    suspend fun takenSlots(groupId: String, from: Instant, to: Instant): List<Slot> = api.rpcList("group_taken_slots") {
        put("p_group_id", groupId); put("p_from", from.toString()); put("p_to", to.toString())
    }

    suspend fun myCommitments(): List<Commitment> = api.rpcList("my_upcoming_commitments")

    /**
     * Reserve the slot, upload the image to the reserved path, then confirm. If the upload
     * fails the proposal is cancelled so the slot is freed straight away (the server would
     * also release it after 15 minutes).
     */
    suspend fun create(p: NewProposal): String {
        val created: CreatedProposal = api.rpc("create_proposal") {
            put("p_group_id", p.groupId)
            put("p_start", p.start.toString())
            put("p_end", p.end.toString())
            put("p_target", p.target.name)
            put("p_focal_x", p.focalX)
            put("p_focal_y", p.focalY)
            put("p_reminder_text", p.reminderText?.trim()?.ifBlank { null })
            put("p_reminder_style", p.reminderStyle)
            put("p_replaces", p.replaces)
        }
        if (created.imageReady) return created.proposalId
        val bytes = requireNotNull(p.jpeg) { "image required" }
        try {
            api.client.storage.from(AppConfig.IMAGE_BUCKET).upload(created.imagePath, bytes) {
                upsert = false
                contentType = ContentType.Image.JPEG
            }
            api.rpcUnit("confirm_proposal_image") { put("p_proposal_id", created.proposalId) }
        } catch (t: Throwable) {
            runCatching { cancel(created.proposalId) }
            throw t
        }
        return created.proposalId
    }

    suspend fun vote(id: String, approve: Boolean, reason: String?): VoteSummary = api.rpc("cast_vote") {
        put("p_proposal_id", id); put("p_decision", if (approve) "APPROVE" else "REJECT"); put("p_reason", reason?.trim()?.ifBlank { null })
    }

    suspend fun cancel(id: String) = api.rpcUnit("cancel_proposal") { put("p_proposal_id", id) }

    suspend fun suggestTime(id: String, start: Instant, end: Instant): String = api.rpc("suggest_time") {
        put("p_proposal_id", id); put("p_start", start.toString()); put("p_end", end.toString())
    }

    suspend fun suggestions(id: String): List<TimeSuggestionDto> =
        api.client.from("time_suggestions").select { filter { eq("proposal_id", id) }; order("at", Order.DESCENDING) }.decodeList()

    suspend fun skip(id: String) = api.rpcUnit("skip_on_my_phone") { put("p_proposal_id", id) }

    suspend fun deliverySummary(id: String): DeliverySummary = api.rpc("delivery_summary") { put("p_proposal_id", id) }

    suspend fun reportDelivery(id: String, state: String) = api.rpcUnit("report_delivery") {
        put("p_proposal_id", id); put("p_state", state)
    }

    suspend fun mySchedules(): List<ScheduleDto> = api.rpcList("my_schedules")

    suspend fun serverNow(): Instant = InstantSerializer.parse(api.rpc<String>("server_now"))

    /** Short-lived (10 min) signed URL; images are never public. */
    suspend fun signedUrl(imagePath: String): String =
        api.client.storage.from(AppConfig.IMAGE_BUCKET).createSignedUrl(imagePath, 10.minutes)
}
