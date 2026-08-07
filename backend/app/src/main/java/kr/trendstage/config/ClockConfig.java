package kr.trendstage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 시간은 UTC 기준(04 §8). 테스트에서 고정 Clock 주입 가능하도록 빈으로 노출. */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
