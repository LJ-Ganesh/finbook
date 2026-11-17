package com.syncedwallpapers.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SlotKind { PENDING, APPROVED, MINE, CONFLICT }

data class TimelineSlot(val start: Instant, val end: Instant, val kind: SlotKind)

/** One day as a 24-hour bar in [zone]: pending = outlined, approved = filled. */
@Composable
fun DayBar(day: LocalDate, zone: ZoneId, slots: List<TimelineSlot>, modifier: Modifier = Modifier, height: Int = 18) {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    val total = Duration.between(dayStart, dayEnd).toMillis().toFloat() // 23/25h on DST days
    val track = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val mine = MaterialTheme.colorScheme.tertiary
    val conflict = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(track, cornerRadius = r)
        for (h in listOf(6, 12, 18)) {
            val x = size.width * h / 24f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        slots.forEach { s ->
            val a = maxOf(s.start, dayStart); val b = minOf(s.end, dayEnd)
            if (!a.isBefore(b)) return@forEach
            val x0 = size.width * Duration.between(dayStart, a).toMillis() / total
            val x1 = size.width * Duration.between(dayStart, b).toMillis() / total
            val w = (x1 - x0).coerceAtLeast(3f)
            when (s.kind) {
                SlotKind.APPROVED -> drawRoundRect(primary, Offset(x0, 0f), Size(w, size.height), r)
                SlotKind.PENDING -> drawRoundRect(primary, Offset(x0 + 1, 1f), Size(w - 2, size.height - 2), r, style = Stroke(2.dp.toPx()))
                SlotKind.MINE -> drawRoundRect(mine.copy(alpha = 0.6f), Offset(x0, 0f), Size(w, size.height), r)
                SlotKind.CONFLICT -> drawRoundRect(conflict.copy(alpha = 0.35f), Offset(x0, 0f), Size(w, size.height), r)
            }
        }
    }
}

/** The next [days] days, one bar each, with a "Mon 3" label. */
@Composable
fun WeekTimeline(from: LocalDate, zone: ZoneId, slots: List<TimelineSlot>, days: Int = 7, modifier: Modifier = Modifier) {
    val fmt = DateTimeFormatter.ofPattern("EEE d")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(days) { i ->
            val d = from.plusDays(i.toLong())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                DayBar(d, zone, slots, Modifier.weight(1f))
            }
        }
        Row {
            Text("", modifier = Modifier.width(52.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(12.dp).height(12.dp)) { drawRoundRect(c, cornerRadius = CornerRadius(3f)) }
                Text(" $label", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
