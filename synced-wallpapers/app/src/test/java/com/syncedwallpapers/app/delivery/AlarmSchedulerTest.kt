package com.syncedwallpapers.app.delivery

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.syncedwallpapers.app.core.time.ServerClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var wm: WorkManager
    private val now = Instant.parse("2026-11-20T10:00:00Z")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        wm = WorkManager.getInstance(context)
    }

    private fun scheduler(offset: Duration = Duration.ZERO) =
        AlarmScheduler(context, ServerClock(Clock.fixed(now, ZoneOffset.UTC), offset), wm)

    @Test fun `exact alarm at the corrected device time`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Device clock is 10 minutes slow: true 10:30 = device 10:20.
        scheduler(Duration.ofMinutes(10)).schedule("p", AlarmKind.START, Instant.parse("2026-11-20T10:30:00Z"))
        val am = shadowOf(context.getSystemService(AlarmManager::class.java))
        val alarm = am.peekNextScheduledAlarm()
        assertThat(alarm).isNotNull()
        assertThat(alarm!!.triggerAtMs).isEqualTo(Instant.parse("2026-11-20T10:20:00Z").toEpochMilli())
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
    }

    @Test fun `exact alarms denied - falls back to WorkManager`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val s = scheduler()
        assertThat(s.canScheduleExact()).isFalse()
        s.schedule("p", AlarmKind.START, now.plusSeconds(3600))
        assertThat(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).isNull()
        val infos = wm.getWorkInfosForUniqueWork("alarm-fallback-START-p").get()
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test fun `cancel removes alarms and fallbacks`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val s = scheduler()
        s.schedule("p", AlarmKind.START, now.plusSeconds(3600))
        s.schedule("p", AlarmKind.END, now.plusSeconds(7200))
        s.cancel("p")
        assertThat(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms).isEmpty()
    }
}
