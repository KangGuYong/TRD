package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.GradeRequirementResponse;
import kr.trendstage.apipublic.web.GradeStatusResponse;
import kr.trendstage.apipublic.web.LedgerEntryResponse;
import kr.trendstage.apipublic.web.LedgerListResponse;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.grade.GradeStatus;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 등급·원장 조회. GradeRecalcJob과 동일한 순수 함수 조합을 매 요청 실시간으로 호출한다 —
 * user_grades 스냅샷은 주 1회만 갱신되므로 그걸 읽으면 최대 일주일 묵은 값을 보여줄 수 있다.
 */
@Service
public class MeService {

    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final TrendItemRepository trends;
    private final Clock clock;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger,
                     TrendItemRepository trends, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.trends = trends; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        int hit = (int) submissions.countByUserIdAndResult(userId, SubmissionResult.HIT);
        int miss = (int) submissions.countByUserIdAndResult(userId, SubmissionResult.MISS);
        int judged = hit + miss;

        ParameterSet p = ParameterSet.defaults();
        double ti = TrustIndex.compute(hit, miss, p);

        List<ActiveScore.Aged> aged = new ArrayList<>();
        var now = clock.instant();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(userId)) {
            long ageDays = Duration.between(e.getCreatedAt(), now).toDays();
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), Math.max(0, ageDays)));
        }
        double as = ActiveScore.compute(aged, p);

        GradeStatus status = GradePolicy.evaluate(judged, ti, as);

        List<GradeRequirementResponse> reqs = status.requirements().stream()
                .map(r -> new GradeRequirementResponse(r.label(), r.current(), r.required(), r.met(), r.basis()))
                .toList();

        String note = status.current() == status.next() ? "최고 등급입니다" : null;

        return new GradeStatusResponse(
                status.current().name(), GRADE_NAMES.get(status.current()),
                ti, as, judged, GRADE_NAMES.get(status.next()), reqs, note);
    }

    @Transactional(readOnly = true)
    public LedgerListResponse ledger(UUID userId) {
        List<LedgerEntryResponse> items = ledger.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(e -> new LedgerEntryResponse(
                        wordFor(e),
                        e.getKind().name(),
                        e.getDelta().doubleValue(),
                        e.getReason(),
                        e.getCreatedAt()))
                .toList();
        return new LedgerListResponse(items, null);
    }

    private String wordFor(ScoreLedgerEntry e) {
        if (e.getSubmissionId() == null) return "계정 조정";
        return submissions.findById(e.getSubmissionId())
                .flatMap(s -> trends.findById(s.getTrendItemId()))
                .map(TrendItem::getCanonicalName)
                .orElse("(삭제된 항목)");
    }
}
