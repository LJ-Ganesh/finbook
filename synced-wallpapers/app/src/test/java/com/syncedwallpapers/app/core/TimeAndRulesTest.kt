package com.syncedwallpapers.app.core

import com.google.common.truth.Truth.assertThat
import com.syncedwallpapers.app.core.rules.ScheduleRules
import com.syncedwallpapers.app.core.rules.TimeWindow
import com.syncedwallpapers.app.core.time.ServerClock
import com.syncedwallpapers.app.core.time.TimeFormat
import com.syncedwallpapers.app.data.repo.AuthRepository
import com.syncedwallpapers.app.data.repo.GroupRepository
import com.syncedwallpapers.app.data.repo.ProfileRepository
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class TimeAndRulesTest {
    private val now = Instant.parse("2026-11-17T10:00:00Z")

    @Test fun `lead time window length and deadline`() {
        assertThat(ScheduleRules.validate(now.plusSeconds(29 * 60), now.plusSeconds(3600), now)).isEqualTo(ScheduleRules.Problem.LEAD_TIME_TOO_SHORT)
        assertThat(ScheduleRules.validate(now.plusSeconds(3600), now.plusSeconds(3600 + 14 * 60), now)).isEqualTo(ScheduleRules.Problem.WINDOW_TOO_SHORT)
        assertThat(ScheduleRules.validate(now.plusSeconds(3600), now.plus(Duration.ofDays(31)), now)).isEqualTo(ScheduleRules.Problem.WINDOW_TOO_LONG)
        assertThat(ScheduleRules.validate(now.plusSeconds(1800), now.plusSeconds(1800 + 900), now)).isNull()
        assertThat(ScheduleRules.voteDeadline(now)).isEqualTo(now.minusSeconds(900))
    }

    @Test fun `windows are half-open like tstzrange`() {
        val a = TimeWindow(now, now.plusSeconds(3600))
        assertThat(a.overlaps(TimeWindow(now.plusSeconds(3600), now.plusSeconds(7200)))).isFalse()
        assertThat(a.overlaps(TimeWindow(now.plusSeconds(3599), now.plusSeconds(7200)))).isTrue()
    }

    @Test fun `a clock 10 minutes wrong is corrected by the server offset`() {
        val device = Clock.fixed(now, ZoneOffset.UTC)          // device thinks 10:00
        val clock = ServerClock(device)
        val server = now.plus(Duration.ofMinutes(10))           // truth is 10:10
        clock.record(sentAt = now, server = server, receivedAt = now)
        assertThat(clock.now()).isEqualTo(server)
        // An alarm for 10:30 true time must fire when the device clock reads 10:20.
        assertThat(clock.toDeviceTime(Instant.parse("2026-11-17T10:30:00Z"))).isEqualTo(Instant.parse("2026-11-17T10:20:00Z"))
    }

    @Test fun `round trip is compensated`() {
        val clock = ServerClock(Clock.fixed(now, ZoneOffset.UTC))
        val o = clock.record(sentAt = now, server = now.plusSeconds(1), receivedAt = now.plusSeconds(2))
        assertThat(o).isEqualTo(Duration.ZERO)
    }

    @Test fun `group time plus local time when it differs`() {
        Locale.setDefault(Locale.UK)
        val at = Instant.parse("2026-11-20T03:30:00Z") // 9:00 in Kolkata
        val text = TimeFormat.groupAndLocal(at, ZoneId.of("Asia/Kolkata"), ZoneId.of("Asia/Bangkok"))
        assertThat(text).startsWith("9:00 ")
        assertThat(text).endsWith("10:30 your time")
        assertThat(TimeFormat.groupAndLocal(at, ZoneId.of("Asia/Kolkata"), ZoneId.of("Asia/Kolkata"))).doesNotContain("your time")
    }

    @Test fun `travel or a device zone change keeps the same absolute instant`() {
        val start = Instant.parse("2026-11-20T03:30:00Z")
        // Whatever zone the phone is in, the stored instant is what gets scheduled.
        listOf("Asia/Kolkata", "Europe/London", "America/New_York").forEach { z ->
            assertThat(start.atZone(ZoneId.of(z)).toInstant()).isEqualTo(start)
        }
    }

    @Test fun `DST day is handled by zone rules`() {
        val london = ZoneId.of("Europe/London")
        val springForward = java.time.LocalDate.of(2026, 3, 29)
        val hours = Duration.between(springForward.atStartOfDay(london), springForward.plusDays(1).atStartOfDay(london)).toHours()
        assertThat(hours).isEqualTo(23)
    }

    @Test fun `remaining time`() {
        assertThat(TimeFormat.remaining(Duration.ofMinutes(135))).isEqualTo("2h 15m")
        assertThat(TimeFormat.remaining(Duration.ofSeconds(30))).isEqualTo("less than a minute")
        assertThat(TimeFormat.remaining(Duration.ZERO)).isEqualTo("ended")
    }

    @Test fun `invite codes are normalised from links and dashes`() {
        assertThat(GroupRepository.normalizeCode(" abcd-2345 ")).isEqualTo("ABCD2345")
        assertThat(GroupRepository.normalizeCode("syncedwallpapers://join/ABCD2345")).isEqualTo("ABCD2345")
    }

    @Test fun `phone numbers must be E164`() {
        assertThat(ProfileRepository.isValidPhone("+91 98123 45678")).isTrue()
        assertThat(ProfileRepository.isValidPhone("98123 45678")).isFalse()
    }

    @Test fun `nonce is hashed with SHA-256`() {
        assertThat(AuthRepository.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test fun `server error codes map to friendly messages`() {
        assertThat(AppError.messageFor("SLOT_TAKEN")).contains("time slot")
        assertThat(AppError.messageFor("PERSONAL_CONFLICT")).contains("overlaps")
        assertThat(AppError.messageFor("SOMETHING_NEW")).contains("SOMETHING_NEW")
        assertThat(AppError.from(java.io.IOException("x"))).isEqualTo(AppError.Network)
    }
}
