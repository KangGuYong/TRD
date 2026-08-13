package kr.trendstage.apipublic.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    @ConditionalOnProperty(prefix = "firebase", name = "credentials-path")
    public FirebaseApp firebaseApp(org.springframework.core.env.Environment env) throws IOException {
        String path = env.getRequiredProperty("firebase.credentials-path");
        GoogleCredentials credentials = GoogleCredentials.fromStream(new FileInputStream(path));
        FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
        log.info("Firebase Admin SDK 초기화 완료 (credentials={})", path);
        return FirebaseApp.initializeApp(options);
    }
}
