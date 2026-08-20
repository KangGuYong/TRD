package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.PropagationStepResponse;
import kr.trendstage.apipublic.web.TrendDetailResponse;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.apipublic.web.TrendSummaryResponse;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.DailySelectionPicker;
import kr.trendstage.domain.trend.StageEvaluator;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.DailySelection;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserPreference;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.DailySelectionRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserPreferenceRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.repo.WatchRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 홈/목록 조회. 정렬은 "지금 행동해야 하는 순"(StageEvaluator.actionPriority) — 인기순 아님(앱 원칙 05).
 * daily=true면 상위 5개만("더 보기" 없음, 앱 원칙 01).
 */
@Service
public class TrendQueryService {

    private static final int DAILY_LIMIT = 5;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("MM-dd").withZone(KST);

    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final VerdictRepository verdicts;
    private final VoteRepository votes;
    private final WatchRepository watches;
    private final DailySelectionRepository dailySelections;
    private final UserPreferenceRepository preferences;
    private final DailySelectionWriter selectionWriter;

    public TrendQueryService(TrendItemRepository trends, SubmissionRepository submissions,
                             VerdictRepository verdicts, VoteRepository votes, WatchRepository watches,
                             DailySelectionRepository dailySelections, UserPreferenceRepository preferences,
                             DailySelectionWriter selectionWriter) {
        this.trends = trends; this.submissions = submissions;
        this.verdicts = verdicts; this.votes = votes; this.watches = watches;
        this.dailySelections = dailySelections; this.preferences = preferences;
        this.selectionWriter = selectionWriter;
    }

    @Transactional(readOnly = true)
    public List<TrendSummaryResponse> home(boolean daily, UUID userId) {
        if (daily && userId != null) {
            return dailyForUser(userId);
        }
        List<TrendSummaryResponse> all = liveRanked();
        return daily ? all.stream().limit(DAILY_LIMIT).toList() : all;
    }

    private List<TrendSummaryResponse> liveRanked() {
        return trends.findByStateInAndVisibility(List.of(TrendState.PENDING, TrendState.JUDGING), TrendVisibility.PUBLIC)
                .stream()
                .map(this::toSummary)
                .sorted(Comparator.comparingInt(r -> StageEvaluator.actionPriority(DisplayStage.valueOf(r.stage()))))
                .collect(Collectors.toList());
    }

    private List<TrendSummaryResponse> dailyForUser(UUID userId) {
        LocalDate today = LocalDate.now(KST);
        List<UUID> ids = dailySelections.findByUserIdAndSelectionDateOrderByRankAsc(userId, today)
                .stream().map(DailySelection::getTrendItemId).toList();
        if (ids.isEmpty()) {
            ids = generateSelection(userId, today);
        }
        Map<UUID, TrendItem> byId = trends.findAllById(ids).stream()
                .collect(Collectors.toMap(TrendItem::getId, it -> it));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toSummary)
                .toList();
    }

    /** 오늘자 배정이 없을 때만 호출. 후보군을 계산해 선정하고 저장을 시도한 뒤, 실제 저장된(경쟁 시 상대방 것일 수도 있는) 결과를 다시 읽는다. */
    private List<UUID> generateSelection(UUID userId, LocalDate today) {
        List<TrendItem> candidates = trends.findByStateInAndVisibility(List.of(TrendState.PENDING, TrendState.JUDGING), TrendVisibility.PUBLIC);
        List<DailySelectionPicker.Candidate> picked = candidates.stream()
                .map(item -> new DailySelectionPicker.Candidate(
                        item.getId(),
                        item.getCategory() != null ? item.getCategory().name() : "",
                        StageEvaluator.actionPriority(stageOf(item))))
                .toList();

        Set<String> preferredCategories = preferences.findByUserId(userId)
                .map(UserPreference::getCategories)
                .map(Set::of)
                .orElseGet(Set::of);

        List<UUID> selected = DailySelectionPicker.pick(picked, preferredCategories, DAILY_LIMIT);
        if (selected.isEmpty()) return selected;

        selectionWriter.trySave(userId, today, selected);

        return dailySelections.findByUserIdAndSelectionDateOrderByRankAsc(userId, today)
                .stream().map(DailySelection::getTrendItemId).toList();
    }

    private DisplayStage stageOf(TrendItem item) {
        int reachedCount = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID).size();
        boolean resolvedFading = item.getState() == TrendState.RESOLVED;
        return StageEvaluator.fromReach(reachedCount, resolvedFading);
    }

    private TrendSummaryResponse toSummary(TrendItem item) {
        List<String> platforms = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID);
        int reachedCount = platforms.size();
        DisplayStage stage = stageOf(item);

        String meaning = submissions.findFirstByTrendItemIdOrderByCreatedAtAsc(item.getId())
                .map(Submission::getOneLine).orElse("");

        String pathText = String.join(" → ", platforms);

        return new TrendSummaryResponse(
                item.getId(),
                item.getCanonicalName(),
                meaning,
                stage.name(),
                stage.label(),
                lifeText(stage),
                pathText,
                reachedCount,
                null);
    }

    private static String lifeText(DisplayStage stage) {
        return switch (stage) {
            case SEED -> "아직 아무도 모릅니다";
            case RISING -> "지금이 적기예요";
            case PEAK -> "곧 흔해져요";
            case FADING -> "지금 쓰면 늦어요";
        };
    }

    @Transactional(readOnly = true)
    public TrendDetailResponse detail(UUID id, UUID viewerId) {
        TrendItem item = trends.findById(id)
                .filter(i -> i.getState() != TrendState.MERGED)
                .filter(i -> i.getVisibility() == TrendVisibility.PUBLIC)
                .orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));

        TrendSummaryResponse base = toSummary(item);

        Verdict current = verdicts.findCurrentByTrendItemId(id).orElse(null);
        String verdict = null, verdictWhy = null, reachLevel = null;
        if (current != null && current.getResult() != VerdictResult.VOID) {
            boolean hit = current.getResult() == VerdictResult.HIT;
            verdict = hit ? "적중했어요" : "빗나갔어요";
            int distinctSubmitters = (int) submissions.findByTrendItemIdAndResultNot(id, kr.trendstage.persistence.type.SubmissionResult.VOID)
                    .stream().map(Submission::getUserId).distinct().count();
            verdictWhy = String.format("서로 다른 제보자 %d명 확인 · T=%s", distinctSubmitters, current.getScoreT().toPlainString());
            reachLevel = current.getReachLevel() != null ? current.getReachLevel().name() : null;
        }

        List<PropagationStepResponse> propagationPath = buildPropagationPath(id);

        long willTrend = votes.countByTrendItemIdAndWillTrend(id, true);
        long wontTrend = votes.countByTrendItemIdAndWillTrend(id, false);
        long totalVotes = willTrend + wontTrend;
        String voteCount = totalVotes == 0 ? null
                : String.format("%,d명 참여 · 뜬다 %d%%", totalVotes, Math.round(willTrend * 100.0 / totalVotes));

        boolean watched = viewerId != null
                && watches.existsByUserIdAndNormalizedKey(viewerId, item.getNormalizedKey());

        return new TrendDetailResponse(
                base.id(), base.word(), base.meaning(), base.stage(), base.stageLabel(),
                base.lifeText(), base.pathText(), base.reachedCount(), base.ageShort(),
                verdict, verdictWhy, reachLevel,
                null, null, null, List.of(),
                propagationPath, voteCount, watched);
    }

    private List<PropagationStepResponse> buildPropagationPath(UUID trendItemId) {
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(trendItemId, kr.trendstage.persistence.type.SubmissionResult.VOID);
        Map<String, Instant> firstSeenByPlatform = new LinkedHashMap<>();
        subs.stream()
                .filter(s -> s.getSourcePlatform() != null)
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .forEach(s -> firstSeenByPlatform.putIfAbsent(s.getSourcePlatform(), s.getCreatedAt()));
        return firstSeenByPlatform.entrySet().stream()
                .map(e -> new PropagationStepResponse(e.getKey(), PATH_DATE.format(e.getValue()), null, true))
                .toList();
    }
}
