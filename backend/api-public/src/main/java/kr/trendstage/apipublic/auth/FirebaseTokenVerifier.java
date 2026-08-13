package kr.trendstage.apipublic.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Authorization 헤더의 Firebase ID 토큰을 검증한다. 서명·만료·발급자를 Admin SDK가 확인. */
@Component
public class FirebaseTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenVerifier.class);

    private final Optional<FirebaseApp> app;

    public FirebaseTokenVerifier(Optional<FirebaseApp> app) {
        this.app = app;
    }

    /** 토큰이 유효하면 검증된 FirebaseToken, 그렇지 않으면 empty. Firebase 미설정이면 예외. */
    public Optional<FirebaseToken> verify(String idToken) {
        if (app.isEmpty()) throw new FirebaseNotConfiguredException();
        try {
            return Optional.of(FirebaseAuth.getInstance(app.get()).verifyIdToken(idToken));
        } catch (FirebaseAuthException e) {
            log.debug("Firebase ID 토큰 검증 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
