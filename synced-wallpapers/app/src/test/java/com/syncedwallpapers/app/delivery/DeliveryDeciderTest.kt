package com.syncedwallpapers.app.delivery

import com.google.common.truth.Truth.assertThat
import com.syncedwallpapers.app.data.local.LocalState
import com.syncedwallpapers.app.data.local.ScheduleEntity
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DeliveryDeciderTest {
    private val start = Instant.parse("2026-11-20T12:30:00Z")
    private val end = start.plus(Duration.ofHours(5))

    private fun entity(
        mode: String = "AUTO", ready: Boolean = true, state: LocalState = LocalState.READY,
        skipped: Boolean = false, tapNotified: Boolean = false,
    ) = ScheduleEntity(
        proposalId = "p", groupId = "g", groupName = "Family", timeZone = "Asia/Kolkata",
        startAt = start.toEpochMilli(), endAt = end.toEpochMilli(), target = "HOME", imagePath = "g/p.jpg",
        focalX = .5f, focalY = .5f, reminderText = null, reminderStyle = 0, deliveryMode = mode,
        skipped = skipped, state = state, homeFile = if (ready) "f.bin" else null, tapNotified = tapNotified,
    )

    @Test fun `before the start it prepares`() {
        assertThat(DeliveryDecider.decide(entity(ready = false), start.minusSeconds(60))).isEqualTo(Plan.PREPARE)
    }

    @Test fun `at the start in auto mode it applies`() {
        assertThat(DeliveryDecider.decide(entity(), start)).isEqualTo(Plan.APPLY)
    }

    @Test fun `phone off at start and on mid-window applies late`() {
        assertThat(DeliveryDecider.decide(entity(), start.plus(Duration.ofHours(2)))).isEqualTo(Plan.APPLY_LATE)
    }

    @Test fun `phone back on after the end is missed`() {
        assertThat(DeliveryDecider.decide(entity(), end.plusSeconds(1))).isEqualTo(Plan.MISSED)
    }

    @Test fun `image not downloaded by the start keeps downloading`() {
        assertThat(DeliveryDecider.decide(entity(ready = false, state = LocalState.DOWNLOADING), start.plusSeconds(30)))
            .isEqualTo(Plan.DOWNLOAD_NOT_READY)
    }

    @Test fun `applied wallpaper ends at the end`() {
        assertThat(DeliveryDecider.decide(entity(state = LocalState.LIVE), end)).isEqualTo(Plan.END)
        assertThat(DeliveryDecider.decide(entity(state = LocalState.LIVE), start.plusSeconds(5))).isEqualTo(Plan.KEEP)
    }

    @Test fun `tap mode notifies once and never applies by itself`() {
        assertThat(DeliveryDecider.decide(entity(mode = "TAP"), start)).isEqualTo(Plan.NOTIFY_TAP)
        assertThat(DeliveryDecider.decide(entity(mode = "TAP", tapNotified = true, state = LocalState.WAITING_TAP), start.plusSeconds(60)))
            .isEqualTo(Plan.KEEP)
    }

    @Test fun `tap mode never tapped reports NOT_TAPPED`() {
        assertThat(DeliveryDecider.decide(entity(mode = "TAP", tapNotified = true, state = LocalState.WAITING_TAP), end))
            .isEqualTo(Plan.NOT_TAPPED)
    }

    @Test fun `skipped always wins`() {
        listOf(start.minusSeconds(60), start, end).forEach {
            assertThat(DeliveryDecider.decide(entity(skipped = true), it)).isEqualTo(Plan.SKIP)
        }
    }

    @Test fun `never applies outside the window`() {
        val e = entity()
        assertThat(DeliveryDecider.mayApply(e, start.minusMillis(1))).isFalse()
        assertThat(DeliveryDecider.mayApply(e, start)).isTrue()
        assertThat(DeliveryDecider.mayApply(e, end.minusMillis(1))).isTrue()
        assertThat(DeliveryDecider.mayApply(e, end)).isFalse()
        assertThat(DeliveryDecider.mayApply(entity(skipped = true), start)).isFalse()
    }

    @Test fun `skips are never reported as delivery state`() {
        assertThat(DeliveryDecider.reportedState(LocalState.SKIPPED)).isNull()
        assertThat(DeliveryDecider.reportedState(LocalState.APPLIED_LATE)).isEqualTo("APPLIED_LATE")
    }

    @Test fun `restore only if our wallpaper is still set`() {
        assertThat(RestorePolicy.shouldRestore(appliedId = 42, currentId = 42)).isTrue()
        assertThat(RestorePolicy.shouldRestore(appliedId = 42, currentId = 43)).isFalse() // user changed it
        assertThat(RestorePolicy.shouldRestore(appliedId = null, currentId = 42)).isFalse()
        assertThat(RestorePolicy.shouldRestore(appliedId = -1, currentId = -1)).isFalse()
    }
}
