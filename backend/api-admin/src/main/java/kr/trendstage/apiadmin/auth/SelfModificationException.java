package kr.trendstage.apiadmin.auth;

/** ADMIN이 자기 자신의 계정을 비활성화하는 등, 스스로를 잠글 수 있는 조작을 막는다. */
public class SelfModificationException extends RuntimeException {
    public SelfModificationException(String message) {
        super(message);
    }
}
