package kr.trendstage.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 운영 ClockConfig(systemUTC)보다 우선하는 조작 가능한 Clock. */
@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
