package com.syncedwallpapers.app.core

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Every failure the UI can show. RPCs raise errors whose message is a stable code
 * (see the RPC migration in supabase/migrations); [AppError.from] maps them to friendly text.
 */
sealed class AppError(val userMessage: String, val retryable: Boolean = false) {
    class Server(val code: String, message: String, val detail: String? = null) : AppError(message)
    object Network : AppError("You're offline or the connection dropped. Try again.", retryable = true)
    object ServerWaking : AppError("Server is waking up, try again in a moment.", retryable = true)
    object SessionExpired : AppError("Your session expired. Please sign in again.")
    object NotConfigured : AppError("This build isn't configured yet. See local.properties.example.")
    class Unknown(message: String) : AppError(message, retryable = true)

    companion object {
        private val messages = mapOf(
            "NOT_AUTHENTICATED" to "Please sign in again.",
            "NOT_MEMBER" to "You're not a member of this group.",
            "NOT_ADMIN" to "Only the group admin can do that.",
            "NOT_FOUND" to "This item no longer exists.",
            "INVALID_NAME" to "Names must be 1–40 characters.",
            "INVALID_TIME_ZONE" to "That time zone isn't recognised.",
            "INVALID_TIME" to "Pick a start and end time.",
            "LEAD_TIME_TOO_SHORT" to "The start must be at least 30 minutes from now.",
            "WINDOW_TOO_SHORT" to "A schedule must last at least 15 minutes.",
            "WINDOW_TOO_LONG" to "A schedule can last at most 30 days.",
            "SLOT_TAKEN" to "Someone just took that time slot. Pick another time.",
            "PERSONAL_CONFLICT" to "This overlaps another wallpaper you've committed to.",
            "ALREADY_VOTED" to "You've already voted.",
            "PROPOSAL_CLOSED" to "Voting on this proposal has closed.",
            "VOTING_CLOSED" to "The voting deadline has passed.",
            "IMAGE_NOT_UPLOADED" to "The photo didn't finish uploading. Try again.",
            "IMAGE_GONE" to "The original photo is no longer available. Pick it again.",
            "REMINDER_TOO_LONG" to "Reminder text can be at most 60 characters.",
            "GROUP_FULL" to "This group is full.",
            "INVALID_EMAIL" to "That email address doesn't look right.",
            "ALREADY_MEMBER" to "That person is already in the group.",
            "INVITE_NOT_FOUND" to "That invite code doesn't exist.",
            "INVITE_USED" to "That invite has already been used.",
            "INVITE_EXPIRED" to "That invite has expired. Ask for a new one.",
            "INVITE_FOR_SOMEONE_ELSE" to "That invite was sent to a different Google account.",
            "REQUEST_CLOSED" to "This join request is closed.",
            "ACTION_ALREADY_PENDING" to "A request of this kind is already waiting for approval.",
            "ACTION_CLOSED" to "This request is closed.",
            "NOT_A_VOTER" to "You don't vote on this request.",
            "INVALID_MEMBER_LIMIT" to "The limit must be between the current member count and 20.",
            "INVALID_MEMBER" to "Pick another member of the group.",
            "NO_OTHER_CANDIDATE" to "There's nobody else to pass the admin role to.",
            "NOTHING_TO_DECLINE" to "There's nothing to decline.",
            "OWN_PROPOSAL" to "You can't suggest a time for your own proposal.",
        )

        fun messageFor(code: String): String = messages[code] ?: "Something went wrong ($code)."

        fun from(t: Throwable): AppError {
            if (t is CancellationException) throw t
            return when (t) {
                is AppException -> t.error
                is RestException -> {
                    val code = t.error.trim()
                    when {
                        t.statusCode == 401 -> SessionExpired
                        t.statusCode in setOf(502, 503, 504, 520, 522, 540, 544) -> ServerWaking
                        messages.containsKey(code) -> Server(code, messageFor(code), t.description)
                        else -> Unknown(t.message ?: "Something went wrong.")
                    }
                }
                is HttpRequestTimeoutException -> ServerWaking
                is HttpRequestException, is IOException -> Network
                else -> Unknown(t.message ?: "Something went wrong.")
            }
        }
    }
}

class AppException(val error: AppError) : Exception(error.userMessage)

/** Runs [block] and converts failures into [AppError]. */
suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (t: Throwable) {
        Result.failure(AppException(AppError.from(t)))
    }

val Throwable.appError: AppError get() = (this as? AppException)?.error ?: AppError.from(this)
