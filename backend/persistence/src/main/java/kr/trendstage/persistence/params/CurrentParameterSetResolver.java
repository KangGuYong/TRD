package kr.trendstage.persistence.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Component;

/**
 * 운영에 반영된(가장 최근 APPLIED) 파라미터 값. 없으면 defaults().
 * verdict_runner(scheduler)와 ADM-600 "현재값" 표시(api-admin)가 같은 값을 봐야 하므로
 * 두 모듈이 공통으로 의존하는 persistence에 둔다.
 */
@Component
public class CurrentParameterSetResolver {

    private final ParameterDraftRepository drafts;
    private final ObjectMapper objectMapper;

    public CurrentParameterSetResolver(ParameterDraftRepository drafts, ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.objectMapper = objectMapper;
    }

    public ParameterSet resolve() {
        return drafts.findFirstByStatusOrderByAppliedAtDesc(ParamStatus.APPLIED)
                .map(d -> d.toParameterSet(objectMapper))
                .orElseGet(ParameterSet::defaults);
    }
}
