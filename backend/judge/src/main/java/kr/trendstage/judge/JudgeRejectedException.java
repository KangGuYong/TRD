package kr.trendstage.judge;

/** 판정 규칙상 받아들일 수 없는 요청(없는 항목, 판정 이력 없음 등). 콘솔 API는 422로 매핑한다. */
public class JudgeRejectedException extends RuntimeException {
    public JudgeRejectedException(String message) {
        super(message);
    }
}
