package com.syncedwallpapers.app.core.time

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Times are entered and shown in the group's time zone, plus the viewer's local time
 * when it differs, e.g. "9:00 IST · 10:30 your time".
 */
object TimeFormat {
    private val time = DateTimeFormatter.ofPattern("H:mm", Locale.getDefault())
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun zoneShort(zone: ZoneId, at: Instant, locale: Locale = Locale.getDefault()): String {
        val name = DateTimeFormatter.ofPattern("zzz", locale).format(ZonedDateTime.ofInstant(at, zone))
        // "GMT+05:30" style names are less readable than the region; keep them but trim.
        return name.ifBlank { zone.getDisplayName(TextStyle.SHORT, locale) }
    }

    fun clock(at: Instant, zone: ZoneId): String = time.format(ZonedDateTime.ofInstant(at, zone))

    fun dayLabel(at: Instant, zone: ZoneId): String = day.format(ZonedDateTime.ofInstant(at, zone))

    /** "9:00 IST" or "9:00 IST · 10:30 your time". */
    fun groupAndLocal(at: Instant, groupZone: ZoneId, localZone: ZoneId = ZoneId.systemDefault()): String {
        val g = "${clock(at, groupZone)} ${zoneShort(groupZone, at)}"
        return if (sameOffset(at, groupZone, localZone)) g else "$g · ${clock(at, localZone)} your time"
    }

    /** "Sat 12 Nov, 18:00–23:00 IST" (+ local line handled by the caller). */
    fun window(start: Instant, end: Instant, zone: ZoneId): String {
        val s = ZonedDateTime.ofInstant(start, zone)
        val e = ZonedDateTime.ofInstant(end, zone)
        val sameDay = s.toLocalDate() == e.toLocalDate()
        val endText = if (sameDay) time.format(e) else "${day.format(e)}, ${time.format(e)}"
        return "${day.format(s)}, ${time.format(s)}–$endText ${zoneShort(zone, start)}"
    }

    fun localWindowIfDifferent(start: Instant, end: Instant, groupZone: ZoneId, localZone: ZoneId = ZoneId.systemDefault()): String? =
        if (sameOffset(start, groupZone, localZone) && sameOffset(end, groupZone, localZone)) null
        else "${window(start, end, localZone)} your time"

    fun sameOffset(at: Instant, a: ZoneId, b: ZoneId): Boolean = a.rules.getOffset(at) == b.rules.getOffset(at)

    /** "2h 15m left", "3 days left", "less than a minute". */
    fun remaining(d: Duration): String = when {
        d.isNegative || d.isZero -> "ended"
        d.toMinutes() < 1 -> "less than a minute"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h ${d.toMinutesPart()}m"
        else -> "${d.toDays()}d ${d.toHoursPart()}h"
    }
}
