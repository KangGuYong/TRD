package kr.trendstage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 트렌드 레이더 부트스트랩. 앱 API·콘솔 API·배치를 한 실행 파일로 기동하는 모듈러 모놀리스.
 * 트래픽/보안 요구가 확정되면 모듈 경계 그대로 서비스 분리(04 §2.2).
 */
@SpringBootApplication(scanBasePackages = "kr.trendstage")
@EnableScheduling
public class TrendRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrendRadarApplication.class, args);
    }
}
