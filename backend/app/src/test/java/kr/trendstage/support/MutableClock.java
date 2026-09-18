package kr.trendstage.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 시각을 앞당기기 위한 Clock. 기본은 실제 현재 시각에서 시작. */
public class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    public void set(Instant instant) { this.now = instant; }
    public void advance(Duration d) { this.now = this.now.plus(d); }
    public void reset() { this.now = Instant.now(); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
