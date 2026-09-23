package kr.trendstage.apipublic.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Firebase Admin SDK 초기화. 서비스 계정 키가 없는 로컬 개발 환경에서도 앱이 뜨도록
 * FIREBASE_CREDENTIALS_PATH가 설정된 경우에만 빈을 만든다(조건부).
 * 키가 없으면 {@link FirebaseTokenVerifier}가 호출 시점에 명확히 실패한다(앱 기동은 막지 않음).
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    @Conditional(CredentialsPathConfigured.class)
    public FirebaseApp firebaseApp(org.springframework.core.env.Environment env) throws IOException {
        // FirebaseApp 레지스트리는 JVM 전역이라 같은 JVM에서 컨텍스트가 두 번 뜨면(통합 테스트가
        // 서로 다른 프로퍼티로 컨텍스트를 새로 만들 때) initializeApp이 "DEFAULT already exists"로 던진다.
        // 이미 있으면 그대로 쓴다 — 초기화를 멱등하게.
        for (FirebaseApp existing : FirebaseApp.getApps()) {
            if (FirebaseApp.DEFAULT_APP_NAME.equals(existing.getName())) {
                log.info("이미 초기화된 Firebase 기본 앱을 재사용한다");
                return existing;
            }
        }

        String path = env.getRequiredProperty("firebase.credentials-path");
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(path));
        FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
        log.info("Firebase Admin SDK 초기화 완료 (credentials={})", path);
        return FirebaseApp.initializeApp(options);
    }

    /**
     * 키 경로가 비어 있지 않을 때만 빈을 만든다. @ConditionalOnProperty는 빈 문자열도 "설정됨"으로 봐서,
     * 변수를 빈 값으로 넘기는 환경(docker compose)에서 빈 경로로 초기화를 시도하다 기동이 실패한다.
     */
    static class CredentialsPathConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("firebase.credentials-path"));
        }
    }
}
