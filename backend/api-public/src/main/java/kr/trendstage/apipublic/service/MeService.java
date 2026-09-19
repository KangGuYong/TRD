package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.GradeRequirementResponse;
import kr.trendstage.apipublic.web.GradeStatusResponse;
import kr.trendstage.apipublic.web.LedgerEntryResponse;
import kr.trendstage.apipublic.web.LedgerListResponse;
import kr.trendstage.apipublic.web.MeSummaryResponse;
import kr.trendstage.apipublic.web.PreferencesNotFoundException;
import kr.trendstage.apipublic.web.PreferencesRequest;
import kr.trendstage.apipublic.web.PreferencesResponse;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.grade.GradePolicy;
import kr.trendstage.domain.grade.GradeStatus;
import kr.trendstage.domain.grade.SubmissionQuota;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.ActiveScore;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.domain.vote.VoteAccuracy;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserPreference;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.entity.Vote;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserPreferenceRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendCategory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 등급·원장·요약·설정 조회. GradeRecalcJob과 동일한 순수 함수 조합을 매 요청 실시간으로 호출한다 —
 * user_grades 스냅샷은 주 1회만 갱신되므로 그걸 읽으면 최대 일주일 묵은 값을 보여줄 수 있다.
 */
@Service
public class MeService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final TrendItemRepository trends;
    private final VoteRepository votes;
    private final VerdictRepository verdicts;
    private final UserPreferenceRepository preferences;
    private final Clock clock;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger, TrendItemRepository trends,
                     VoteRepository votes, VerdictRepository verdicts, UserPreferenceRepository preferences, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.trends = trends;
        this.votes = votes; this.verdicts = verdicts; this.preferences = preferences; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        GradeStatus status = computeGradeStatus(userId);
        int judged = judgedCount(userId);
        double ti = TrustIndex.compute((int) hitCount(userId), (int) missCount(userId), ParameterSet.defaults());
        double as = activeScore(userId);

        List<GradeRequirementResponse> reqs = status.requirements().stream()
                .map(r -> new GradeRequirementResponse(r.kind(), r.label(), r.current(), r.required(), r.met(), r.basis()))
                .toList();

        String note = status.current() == status.next() ? "최고 등급입니다" : null;

        return new GradeStatusResponse(
                status.current().name(), GRADE_NAMES.get(status.current()),
                ti, as, judged, GRADE_NAMES.get(status.next()), reqs, note);
    }

    @Transactional(readOnly = true)
    public MeSummaryResponse summary(UUID userId) {
        GradeStatus status = computeGradeStatus(userId);
        int quotaMax = SubmissionQuota.weeklyLimit(status.current());

        var now = clock.instant();
        var weekStart = now.atZone(KST).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(KST).toInstant();
        int quotaUsed = (int) submissions.countByUserIdAndCreatedAtAfterAndResultNot(userId, weekStart, SubmissionResult.VOID);

        List<VoteAccuracy.VoteOutcome> outcomes = new ArrayList<>();
        for (Vote v : votes.findByUserId(userId)) {
            Verdict current = verdicts.findCurrentByTrendItemId(v.getTrendItemId()).orElse(null);
            if (current != null && current.getResult() != VerdictResult.VOID) {
                outcomes.add(new VoteAccuracy.VoteOutcome(v.isWillTrend(), current.getResult() == VerdictResult.HIT));
            }
        }
        VoteAccuracy.Result acc = VoteAccuracy.compute(outcomes);

        // streakDays/totalRead: reads 테이블이 없어 계산 불가 — 항상 0. 프론트는 이 두 값을 표시에 쓰지 않는다.
        return new MeSummaryResponse(0, quotaUsed, quotaMax, acc.hitRate(), acc.total(), acc.correct(), 0);
    }

    @Transactional(readOnly = true)
    public PreferencesResponse getPreferences(UUID userId) {
        UserPreference p = preferences.findByUserId(userId).orElseThrow(PreferencesNotFoundException::new);
        List<TrendCategory> cats = Arrays.stream(p.getCategories()).map(TrendCategory::valueOf).toList();
        return new PreferencesResponse(cats, p.getNotifyHour());
    }

    @Transactional
    public PreferencesResponse savePreferences(UUID userId, PreferencesRequest req) {
        String[] cats = req.categories().stream().map(Enum::name).toArray(String[]::new);
        UserPreference existing = preferences.findByUserId(userId).orElse(null);
        if (existing == null) {
            preferences.save(new UserPreference(userId, cats, (short) req.notifyHour()));
        } else {
            existing.update(cats, (short) req.notifyHour());
        }
        return new PreferencesResponse(req.categories(), req.notifyHour());
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

    private GradeStatus computeGradeStatus(UUID userId) {
        int judged = judgedCount(userId);
        double ti = TrustIndex.compute((int) hitCount(userId), (int) missCount(userId), ParameterSet.defaults());
        double as = activeScore(userId);
        return GradePolicy.evaluate(judged, ti, as);
    }

    private int judgedCount(UUID userId) {
        return (int) (hitCount(userId) + missCount(userId));
    }

    private long hitCount(UUID userId) {
        return submissions.countByUserIdAndResult(userId, SubmissionResult.HIT);
    }

    private long missCount(UUID userId) {
        return submissions.countByUserIdAndResult(userId, SubmissionResult.MISS);
    }

    private double activeScore(UUID userId) {
        List<ActiveScore.Aged> aged = new ArrayList<>();
        var now = clock.instant();
        for (ScoreLedgerEntry e : ledger.findByUserIdOrderByCreatedAtDesc(userId)) {
            long ageDays = Math.max(0, Duration.between(e.getDecayAnchorAt(), now).toDays());
            aged.add(new ActiveScore.Aged(e.getDelta().doubleValue(), ageDays, e.getHalflifeDays()));
        }
        return ActiveScore.compute(aged);
    }

    private String wordFor(ScoreLedgerEntry e) {
        if (e.getSubmissionId() == null) return "계정 조정";
        return submissions.findById(e.getSubmissionId())
                .flatMap(s -> trends.findById(s.getTrendItemId()))
                .map(TrendItem::getCanonicalName)
                .orElse("(삭제된 항목)");
    }
}
