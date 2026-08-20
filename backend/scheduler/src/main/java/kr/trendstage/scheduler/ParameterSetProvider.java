package kr.trendstage.scheduler;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import org.springframework.stereotype.Component;

/**
 * 현재 유효한 파라미터를 제공. parameter_drafts의 APPLIED 최신본을 읽는다(ADM-600 2인 승인 실행 시 갱신).
 * 엔진은 이 값을 주입받으므로 운영/시뮬레이션이 같은 코드를 공유한다.
 */
@Component
public class ParameterSetProvider {

    private final CurrentParameterSetResolver resolver;

    public ParameterSetProvider(CurrentParameterSetResolver resolver) {
        this.resolver = resolver;
    }

    public ParameterSet current() {
        return resolver.resolve();
    }
}
