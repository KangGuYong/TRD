package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ADM-200 판정 관리. R1: 예외처리 전용(VOID·재판정 요청·유예 연장) — 직접 결과 편집 없음.
 * 조회는 OPERATOR/ADMIN/AUDITOR, 처리(VOID·재판정·유예연장)는 OPERATOR/ADMIN만(02 §1.1).
 */
@RestController
@RequestMapping("/admin/verdicts")
public class VerdictController {

    private static final int JUDGE_WINDOW_DAYS = 14;
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final TrendItemRepository trendItems;
    private final VerdictRepository verdicts;
    private final VerdictAdminService verdictAdminService;
    private final Clock clock;

    public VerdictController(TrendItemRepository trendItems, VerdictRepository verdicts,
                              VerdictAdminService verdictAdminService, Clock clock) {
        this.trendItems = trendItems;
        this.verdicts = verdicts;
        this.verdictAdminService = verdictAdminService;
        this.clock = clock;
    }

    public record JudgedItem(String trendItemId, String canonicalName, String result, String reachLevel,
                              String scoreT, String judgedAt, boolean superseded) {}
    public record ImminentItem(String trendItemId, String canonicalName, String firstSeenAt, String deadline,
                                long daysLeft, boolean graceExtended) {}
    public record VerdictListResponse(List<JudgedItem> judged, List<ImminentItem> imminent) {}
    public record ReasonRequest(String reason) {}
    public record ExtendGraceRequest(int days, String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public VerdictListResponse list() {
        List<JudgedItem> judged = trendItems.findByStateIn(List.of(TrendState.RESOLVED, TrendState.VOID)).stream()
                .map(this::toJudgedItem)
                .toList();

        Instant now = clock.instant();
        List<ImminentItem> imminent = trendItems.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING)).stream()
                .map(item -> toImminentItem(item, now))
                .sorted(Comparator.comparingLong(ImminentItem::daysLeft))
                .limit(30)
                .toList();

        return new VerdictListResponse(judged, imminent);
    }

    @PostMapping("/{trendItemId}/void")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public void voidVerdict(@PathVariable UUID trendItemId, @RequestBody ReasonRequest req,
                             @AuthenticationPrincipal AdminPrincipal actor) {
        verdictAdminService.voidVerdict(trendItemId, actor.id(), actor.role(), req.reason());
    }

    @PostMapping("/{trendItemId}/rejudge")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public void rejudge(@PathVariable UUID trendItemId, @RequestBody ReasonRequest req,
                         @AuthenticationPrincipal AdminPrincipal actor) {
        verdictAdminService.requestRejudge(trendItemId, actor.id(), actor.role(), req.reason());
    }

    @PostMapping("/{trendItemId}/extend-grace")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public void extendGrace(@PathVariable UUID trendItemId, @RequestBody ExtendGraceRequest req,
                             @AuthenticationPrincipal AdminPrincipal actor) {
        verdictAdminService.extendGrace(trendItemId, req.days(), actor.id(), actor.role(), req.reason());
    }

    private JudgedItem toJudgedItem(TrendItem item) {
        Verdict current = verdicts.findCurrentByTrendItemId(item.getId()).orElse(null);
        boolean superseded = current != null && current.getSupersedes() != null;
        return new JudgedItem(
                item.getId().toString(), item.getCanonicalName(),
                current == null ? "-" : current.getResult().name(),
                current == null || current.getReachLevel() == null ? null : current.getReachLevel().name(),
                current == null || current.getScoreT() == null ? null : current.getScoreT().toPlainString(),
                current == null ? null : DISPLAY_FORMAT.format(current.getJudgedAt()),
                superseded);
    }

    private ImminentItem toImminentItem(TrendItem item, Instant now) {
        Instant original = item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS));
        Instant deadline = item.getJudgmentDeadlineOverride() != null ? item.getJudgmentDeadlineOverride() : original;
        return new ImminentItem(
                item.getId().toString(), item.getCanonicalName(),
                DISPLAY_FORMAT.format(item.getFirstSeenAt()), DISPLAY_FORMAT.format(deadline),
                Duration.between(now, deadline).toDays(),
                item.getJudgmentDeadlineOverride() != null);
    }
}
