package com.salesforce.test

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TestClock(millis: Long = 42L) : Clock() {

    private var now: Instant = Instant.ofEpochMilli(millis)

    fun setMillis(millis: Long) {
        now = Instant.ofEpochMilli(millis)
    }

    fun advanceMillis(millis: Long): Long {
        now = now.plus(Duration.ofMillis(millis))
        return now.toEpochMilli()
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this
}
