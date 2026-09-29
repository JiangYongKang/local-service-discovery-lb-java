package com.github.highcumontoa.localservicediscoverylbjava.tests.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

/** 测试用时钟：从固定起点开始，可手动推进，避免真实等待。 */
public class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-09-30T00:00:00Z");

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override public ZoneId getZone() { return TimeZone.getTimeZone("UTC").toZoneId(); }

    @Override
    public Clock withZone(ZoneId zone) {
        return this; // 测试只关心 millis
    }

    @Override public Instant instant() { return now; }

    @Override public long millis() { return now.toEpochMilli(); }
}
