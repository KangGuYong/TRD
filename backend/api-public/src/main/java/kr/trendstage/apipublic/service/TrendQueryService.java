package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.PropagationStepResponse;
import kr.trendstage.apipublic.web.TrendDetailResponse;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.apipublic.web.TrendSummaryResponse;
import kr.trendstage.domain.signal.Platform;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.DailySelectionPicker;
import kr.trendstage.domain.trend.NameNormalizer;
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
import java.util.ArrayList;
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
    private static final int SEARCH_LIMIT = 20;
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

    /**
     * 앱 검색 "이거 아직 써도 돼?"(APP-2). 판정이 끝난 항목도 대상이다. 정렬: 완전일치 → 앞부분 일치 → 포함,
     * 같은 순위 안에서는 홈과 같은 "지금 행동해야 하는 순". 비교는 제보 이름과 같은 정규화 키로 한다.
     */
    @Transactional(readOnly = true)
    public List<TrendSummaryResponse> search(String q) {
        String key = NameNormalizer.normalize(q);
        if (key.isEmpty()) return List.of();
        String pattern = "%" + key.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return trends.searchPublic(pattern).stream()
                .map(item -> Map.entry(matchRank(item, key), toSummary(item)))
                .sorted(Comparator.<Map.Entry<Integer, TrendSummaryResponse>>comparingInt(Map.Entry::getKey)
                        .thenComparingInt(e -> StageEvaluator.actionPriority(DisplayStage.valueOf(e.getValue().stage()))))
                .limit(SEARCH_LIMIT)
                .map(Map.Entry::getValue)
                .toList();
    }

    /** 0 완전일치 · 1 앞부분 일치 · 2 포함 — 정규화 키와 별칭 중 가장 가까운 것. */
    private static int matchRank(TrendItem item, String key) {
        int best = 2;
        for (String name : withAliases(item)) {
            if (name.equals(key)) return 0;
            if (name.startsWith(key)) best = 1;
        }
        return best;
    }

    private static List<String> withAliases(TrendItem item) {
        List<String> names = new ArrayList<>(List.of(item.getNormalizedKey()));
        for (String alias : item.getAliases()) names.add(NameNormalizer.normalize(alias));
        return names;
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
        // 선정 뒤 비공개(신고 대응)·병합된 항목은 그날이라도 빼고, 빈자리는 채우지 않는다(하루 같은 목록).
        // 판정된(RESOLVED) 항목은 결과를 보여줄 수 있게 남긴다.
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(it -> it.getVisibility() == TrendVisibility.PUBLIC && it.getState() != TrendState.MERGED)
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

        String pathText = platforms.stream().map(Platform::labelOf).collect(Collectors.joining(" → "));

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
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .forEach(s -> firstSeenByPlatform.putIfAbsent(Platform.labelOf(s.getPlatform()), s.getCreatedAt()));
        return firstSeenByPlatform.entrySet().stream()
                .map(e -> new PropagationStepResponse(e.getKey(), PATH_DATE.format(e.getValue()), null, true))
                .toList();
    }
}
