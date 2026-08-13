package kr.trendstage.apipublic.auth;

/** FIREBASE_CREDENTIALS_PATH 미설정 상태에서 인증이 필요한 요청이 들어왔을 때. */
public class FirebaseNotConfiguredException extends RuntimeException {
    public FirebaseNotConfiguredException() {
        super("Firebase 인증이 설정되지 않았습니다 (FIREBASE_CREDENTIALS_PATH 필요)");
    }
}
