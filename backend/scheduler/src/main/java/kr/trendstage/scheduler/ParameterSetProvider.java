package kr.trendstage.scheduler;

import kr.trendstage.domain.params.ParameterSet;
import org.springframework.stereotype.Component;

/**
 * 현재 유효한 파라미터를 제공. Phase 0에는 기본값, 이후 parameter_drafts 의 APPLIED 최신본을 읽는다(ADM-600).
 * 엔진은 이 값을 주입받으므로 운영/시뮬레이션이 같은 코드를 공유한다.
 */
@Component
public class ParameterSetProvider {
    public ParameterSet current() {
        return ParameterSet.defaults(); // TODO: parameter_drafts(APPLIED, apply_at<=now) 최신본 조회
    }
}
