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
import kr.trendstage.domain.grade.GradeRequirement;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.domain.vote.VoteAccuracy;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.entity.UserPreference;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.entity.Vote;
import kr.trendstage.persistence.grade.GradeInputsReader;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserPreferenceRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.type.TrendCategory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 등급·원장·요약·설정 조회. 공식 등급은 주간 스냅샷이다(J5) — 제보권 한도와 같은 값.
 * 다음 등급까지의 진행 상황만 지금 값으로 계산한다(스냅샷이 최대 일주일 묵는 대신 주중 등락과 화면상 강등이 없다).
 */
@Service
public class MeService {

    private static final Map<Grade, String> GRADE_NAMES = Map.of(
            Grade.L0, "관찰자", Grade.L1, "제보자", Grade.L2, "탐지자", Grade.L3, "분석가", Grade.L4, "선구자"
    );

    private final SubmissionRepository submissions;
    private final ScoreLedgerRepository ledger;
    private final TrendItemRepository trends;
    private final VoteRepository votes;
    private final VerdictRepository verdicts;
    private final UserPreferenceRepository preferences;
    private final QuotaService quotaService;
    private final UserGradeRepository grades;
    private final GradeInputsReader gradeInputs;
    private final CurrentParameterSetResolver currentParams;
    private final Clock clock;

    public MeService(SubmissionRepository submissions, ScoreLedgerRepository ledger, TrendItemRepository trends,
                     VoteRepository votes, VerdictRepository verdicts, UserPreferenceRepository preferences,
                     QuotaService quotaService, UserGradeRepository grades, GradeInputsReader gradeInputs,
                     CurrentParameterSetResolver currentParams, Clock clock) {
        this.submissions = submissions; this.ledger = ledger; this.trends = trends;
        this.votes = votes; this.verdicts = verdicts; this.preferences = preferences;
        this.quotaService = quotaService; this.grades = grades; this.gradeInputs = gradeInputs;
        this.currentParams = currentParams; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GradeStatusResponse grade(UUID userId) {
        Instant now = clock.instant();
        GradeInputsReader.GradeInputs in = gradeInputs.read(userId, now);
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), currentParams.resolve());
        Grade current = grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
        Grade next = GradePolicy.nextOf(current);
        List<GradeRequirement> reqs = current == next
                ? List.of() : GradePolicy.progressToward(next, in.judgedCount(), ti, in.activeScore());

        String note = null;
        if (current == next) {
            note = "최고 등급입니다";
        } else if (!reqs.isEmpty() && reqs.stream().allMatch(GradeRequirement::met)) {
            note = "요건을 모두 채웠습니다 — 월요일 00:00 등급 재계산 때 반영됩니다";
        }

        return new GradeStatusResponse(
                current.name(), GRADE_NAMES.get(current), ti, in.activeScore(), in.judgedCount(),
                GRADE_NAMES.get(next),
                reqs.stream().map(r -> new GradeRequirementResponse(
                        r.kind(), r.label(), r.current(), r.required(), r.met(), r.basis())).toList(),
                note);
    }

    @Transactional(readOnly = true)
    public MeSummaryResponse summary(UUID userId) {
        QuotaService.Quota quota = quotaService.of(userId, clock.instant());

        List<VoteAccuracy.VoteOutcome> outcomes = new ArrayList<>();
        for (Vote v : votes.findByUserId(userId)) {
            Verdict current = verdicts.findCurrentByTrendItemId(v.getTrendItemId()).orElse(null);
            if (current != null && current.getResult() != VerdictResult.VOID) {
                outcomes.add(new VoteAccuracy.VoteOutcome(v.isWillTrend(), current.getResult() == VerdictResult.HIT));
            }
        }
        VoteAccuracy.Result acc = VoteAccuracy.compute(outcomes);

        // streakDays/totalRead: reads 테이블이 없어 계산 불가 — 항상 0. 프론트는 이 두 값을 표시에 쓰지 않는다.
        return new MeSummaryResponse(0, quota.used(), quota.max(), acc.hitRate(), acc.total(), acc.correct(), 0);
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

    private String wordFor(ScoreLedgerEntry e) {
        if (e.getSubmissionId() == null) return "계정 조정";
        return submissions.findById(e.getSubmissionId())
                .flatMap(s -> trends.findById(s.getTrendItemId()))
                .map(TrendItem::getCanonicalName)
                .orElse("(삭제된 항목)");
    }
}
