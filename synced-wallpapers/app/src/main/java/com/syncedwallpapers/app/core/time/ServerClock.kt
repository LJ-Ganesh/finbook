package com.syncedwallpapers.app.core.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Corrects for a wrong device clock. On every sync the app asks the database for its
 * time (server_now RPC) and stores offset = server − device, compensating half the
 * round trip. Alarm times are converted back to device time before scheduling.
 */
@Singleton
class ServerClock @Inject constructor() {
    private var deviceClock: Clock = Clock.systemUTC()
    private val offsetMillis = AtomicLong(0)

    constructor(deviceClock: Clock, offset: Duration = Duration.ZERO) : this() {
        this.deviceClock = deviceClock
        offsetMillis.set(offset.toMillis())
    }

    val offset: Duration get() = Duration.ofMillis(offsetMillis.get())

    fun setOffset(offset: Duration) = offsetMillis.set(offset.toMillis())

    /** Records a measurement: [server] was read between [sentAt] and [receivedAt] (device time). */
    fun record(sentAt: Instant, server: Instant, receivedAt: Instant): Duration {
        val midpoint = sentAt.plusMillis(Duration.between(sentAt, receivedAt).toMillis() / 2)
        val o = Duration.between(midpoint, server)
        offsetMillis.set(o.toMillis())
        return o
    }

    fun deviceNow(): Instant = deviceClock.instant()

    /** Best estimate of the true (server) time. */
    fun now(): Instant = deviceNow().plusMillis(offsetMillis.get())

    /** Device-clock time at which the true time will be [serverInstant] (for AlarmManager). */
    fun toDeviceTime(serverInstant: Instant): Instant = serverInstant.minusMillis(offsetMillis.get())
}
