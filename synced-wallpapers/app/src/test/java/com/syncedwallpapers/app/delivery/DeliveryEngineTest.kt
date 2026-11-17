package com.syncedwallpapers.app.delivery

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.data.local.LocalState
import com.syncedwallpapers.app.data.local.ScheduleDao
import com.syncedwallpapers.app.data.local.ScheduleEntity
import com.syncedwallpapers.app.data.local.Settings
import com.syncedwallpapers.app.data.local.SettingsStore
import com.syncedwallpapers.app.data.remote.Api
import com.syncedwallpapers.app.data.remote.ScheduleDto
import com.syncedwallpapers.app.data.repo.ProposalRepository
import com.syncedwallpapers.app.notifications.Notifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Device delivery scenarios from the spec, with the Android pieces faked. */
class DeliveryEngineTest {
    private val start = Instant.parse("2026-11-20T12:30:00Z")
    private val end = start.plus(Duration.ofHours(5))

    private class FakeDao : ScheduleDao {
        val rows = linkedMapOf<String, ScheduleEntity>()
        override fun observeAll(): Flow<List<ScheduleEntity>> = flowOf(rows.values.toList())
        override suspend fun all() = rows.values.sortedBy { it.startAt }
        override suspend fun get(id: String) = rows[id]
        override suspend fun forGroup(groupId: String) = rows.values.filter { it.groupId == groupId }
        override suspend fun upsert(entity: ScheduleEntity) { rows[entity.proposalId] = entity }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun clear() = rows.clear()
    }

    private lateinit var dao: FakeDao
    private lateinit var proposals: ProposalRepository
    private lateinit var alarms: AlarmScheduler
    private lateinit var applier: WallpaperApplier
    private lateinit var store: SecureStore
    private lateinit var notifier: Notifier
    private lateinit var work: DeliveryWork
    private var deviceNow: Instant = start.minusSeconds(3600)
    private var serverSchedules: () -> List<ScheduleDto> = { emptyList() }
    private var serverClockAhead: Duration = Duration.ZERO
    private val reported = mutableListOf<String>()

    private fun engine(): DeliveryEngine {
        val clock = ServerClock(object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = deviceNow
        })
        val api = mockk<Api> { every { uidOrNull() } returns "me" }
        val images = mockk<ImageProcessor>(relaxed = true) { every { decodeBytes(any()) } returns mockk<Bitmap>(relaxed = true) }
        val settings = mockk<SettingsStore>(relaxed = true) { coEvery { current() } returns Settings(quietLiveNotice = false) }
        return DeliveryEngine(dao, proposals, api, clock, alarms, applier, images, store, settings, notifier, OkHttpClient(), work)
    }

    private fun dto(mode: String = "AUTO", skipped: Boolean = false) = ScheduleDto(
        proposalId = "p", groupId = "g", groupName = "Family", timeZone = "Asia/Kolkata", startAt = start, endAt = end,
        target = "HOME", imagePath = "g/p.jpg", status = "APPROVED", deliveryMode = mode, skipped = skipped)

    private fun readyRow(state: LocalState = LocalState.READY, mode: String = "AUTO", appliedId: Int? = null, tapNotified: Boolean = false) = ScheduleEntity(
        proposalId = "p", groupId = "g", groupName = "Family", timeZone = "Asia/Kolkata", startAt = start.toEpochMilli(), endAt = end.toEpochMilli(),
        target = "HOME", imagePath = "g/p.jpg", focalX = .5f, focalY = .5f, reminderText = null, reminderStyle = 0, deliveryMode = mode,
        state = state, homeFile = "wp.bin", appliedHomeId = appliedId, tapNotified = tapNotified)

    @Before fun setUp() {
        dao = FakeDao()
        reported.clear()
        proposals = mockk(relaxed = true) {
            coEvery { serverNow() } answers { deviceNow.plus(serverClockAhead) }
            coEvery { mySchedules() } answers { serverSchedules() }
            coEvery { reportDelivery(any(), any()) } answers { reported += secondArg<String>() }
        }
        alarms = mockk(relaxed = true)
        applier = mockk(relaxed = true) { every { setHome(any()) } returns 77; every { currentHomeId() } returns 77 }
        store = mockk(relaxed = true)
        every { store.get(any<String>()) } returns ByteArray(4)
        notifier = mockk(relaxed = true)
        work = mockk(relaxed = true)
    }

    @Test fun `push never arrives - reconciliation still downloads and schedules`() = runTest {
        serverSchedules = { listOf(dto()) }
        engine().reconcile()
        verify { work.enqueueDownload("p") }
        verify { alarms.schedule("p", AlarmKind.START, start) }
        verify { alarms.schedule("p", AlarmKind.END, end) }
        assertThat(reported).contains("DOWNLOADING")
    }

    @Test fun `reboot - alarms are rebuilt from Room even when offline`() = runTest {
        dao.upsert(readyRow())
        coEvery { proposals.mySchedules() } throws IOException("offline")
        assertThat(engine().reconcile()).isEqualTo(DeliveryEngine.SyncResult.OFFLINE)
        verify { alarms.schedule("p", AlarmKind.START, start) }
        assertThat(dao.get("p")).isNotNull() // offline is not "cancelled"
    }

    @Test fun `phone off at start, on mid-window - applies late`() = runTest {
        dao.upsert(readyRow())
        serverSchedules = { listOf(dto()) }
        deviceNow = start.plus(Duration.ofHours(1))
        engine().reconcile()
        verify { applier.setHome(any()) }
        assertThat(dao.get("p")!!.state).isEqualTo(LocalState.APPLIED_LATE)
        assertThat(reported).contains("APPLIED_LATE")
    }

    @Test fun `phone on after the end - missed, nothing applied, image purged`() = runTest {
        dao.upsert(readyRow())
        serverSchedules = { emptyList() } // server stops listing it after the end
        deviceNow = end.plusSeconds(60)
        coEvery { proposals.mySchedules() } throws IOException("offline") // even offline
        engine().reconcile()
        verify(exactly = 0) { applier.setHome(any()) }
        verify { store.delete("wp.bin") }
        assertThat(reported).contains("MISSED")
        assertThat(dao.get("p")).isNull()
    }

    @Test fun `user changed the wallpaper while live - not overwritten at the end`() = runTest {
        dao.upsert(readyRow(state = LocalState.LIVE, appliedId = 77))
        every { applier.currentHomeId() } returns 99 // user picked their own
        deviceNow = end
        coEvery { proposals.mySchedules() } throws IOException("offline")
        engine().onAlarm("p")
        verify(exactly = 0) { applier.restoreHome(any()) }
        assertThat(reported).contains("ENDED")
    }

    @Test fun `our wallpaper still set at the end - restored`() = runTest {
        dao.upsert(readyRow(state = LocalState.LIVE, appliedId = 77))
        deviceNow = end
        engine().onAlarm("p")
        verify { applier.restoreHome(null) } // no home wallpaper chosen → system default
    }

    @Test fun `proposer leaves while live - restored immediately`() = runTest {
        dao.upsert(readyRow(state = LocalState.LIVE, appliedId = 77))
        deviceNow = start.plusSeconds(600)
        serverSchedules = { emptyList() } // cancelled on the server
        engine().reconcile()
        verify { applier.restoreHome(null) }
        verify { alarms.cancel("p") }
        assertThat(dao.get("p")).isNull()
    }

    @Test fun `tap mode, never tapped - NOT_TAPPED and nothing changed`() = runTest {
        serverSchedules = { listOf(dto(mode = "TAP")) }
        dao.upsert(readyRow(mode = "TAP"))
        deviceNow = start.plusSeconds(30)
        val e = engine()
        e.reconcile()
        verify { notifier.tapToSet(any()) }
        deviceNow = end.plusSeconds(1)
        serverSchedules = { emptyList() }
        coEvery { proposals.mySchedules() } throws IOException("offline")
        e.reconcile()
        verify(exactly = 0) { applier.setHome(any()) }
        assertThat(reported).contains("NOT_TAPPED")
    }

    @Test fun `tap outside the window does nothing`() = runTest {
        dao.upsert(readyRow(mode = "TAP", tapNotified = true, state = LocalState.WAITING_TAP))
        deviceNow = end.plusSeconds(1)
        assertThat(engine().onTapToSet("p")).isFalse()
        verify(exactly = 0) { applier.setHome(any()) }
    }

    @Test fun `device clock 10 minutes slow - corrected by the server offset`() = runTest {
        dao.upsert(readyRow())
        serverSchedules = { listOf(dto()) }
        deviceNow = start.minus(Duration.ofMinutes(5)) // device says 5 min before start…
        serverClockAhead = Duration.ofMinutes(10)       // …but it's really 5 min after
        engine().reconcile()
        verify { applier.setHome(any()) }
    }

    @Test fun `skip on my phone restores now and survives an offline sync`() = runTest {
        dao.upsert(readyRow(state = LocalState.LIVE, appliedId = 77))
        deviceNow = start.plusSeconds(60)
        val e = engine()
        e.skip("p")
        verify { applier.restoreHome(null) }
        coVerify { proposals.skip("p") }
        serverSchedules = { listOf(dto(skipped = false)) } // server hasn't caught up yet
        e.reconcile()
        verify(exactly = 0) { applier.setHome(any()) } // never re-applied
    }

}
