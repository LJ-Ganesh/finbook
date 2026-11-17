package com.syncedwallpapers.app.core.rules

import java.time.Duration
import java.time.Instant

/**
 * Client-side mirror of the server rules (the server is authoritative; this only
 * gives instant feedback in the propose flow).
 */
object ScheduleRules {
    val MIN_LEAD: Duration = Duration.ofMinutes(30)
    val MIN_WINDOW: Duration = Duration.ofMinutes(15)
    val MAX_WINDOW: Duration = Duration.ofDays(30)
    val DEADLINE_BEFORE_START: Duration = Duration.ofMinutes(15)
    const val MAX_REMINDER = 60

    enum class Problem { LEAD_TIME_TOO_SHORT, WINDOW_TOO_SHORT, WINDOW_TOO_LONG, END_BEFORE_START }

    fun validate(start: Instant, end: Instant, now: Instant): Problem? = when {
        !end.isAfter(start) -> Problem.END_BEFORE_START
        start.isBefore(now.plus(MIN_LEAD)) -> Problem.LEAD_TIME_TOO_SHORT
        Duration.between(start, end) < MIN_WINDOW -> Problem.WINDOW_TOO_SHORT
        Duration.between(start, end) > MAX_WINDOW -> Problem.WINDOW_TOO_LONG
        else -> null
    }

    fun voteDeadline(start: Instant): Instant = start.minus(DEADLINE_BEFORE_START)

    fun message(p: Problem): String = when (p) {
        Problem.LEAD_TIME_TOO_SHORT -> "Start at least 30 minutes from now."
        Problem.WINDOW_TOO_SHORT -> "Make it at least 15 minutes long."
        Problem.WINDOW_TOO_LONG -> "Make it at most 30 days long."
        Problem.END_BEFORE_START -> "The end must be after the start."
    }
}

data class TimeWindow(val start: Instant, val end: Instant) {
    init { require(end.isAfter(start)) { "end must be after start" } }

    /** Half-open [start, end): back-to-back windows don't overlap (same as tstzrange '[)'). */
    fun overlaps(other: TimeWindow): Boolean = start < other.end && other.start < end
}

object Overlaps {
    fun <T> conflicting(window: TimeWindow, items: List<T>, windowOf: (T) -> TimeWindow): List<T> =
        items.filter { windowOf(it).overlaps(window) }
}
