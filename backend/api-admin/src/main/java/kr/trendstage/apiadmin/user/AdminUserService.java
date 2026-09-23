package kr.trendstage.apiadmin.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.web.AdminNotFoundException;
import kr.trendstage.domain.grade.Grade;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.UserGrade;
import kr.trendstage.persistence.grade.GradeInputsReader;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-311 유저 상세·원장. 이의 제기 대응의 근거 화면이라 앱(/v1/me)과 같은 입력(GradeInputsReader)으로 계산하고
 * 산정 근거 문장을 붙인다(관리자 API 규약). 원장은 수정·삭제하지 않는다(R2) — 정정은 LedgerAdjustService의 ADJ뿐.
 */
@Service
public class AdminUserService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final UserRepository users;
    private final UserGradeRepository grades;
    private final ScoreLedgerRepository ledger;
    private final VerdictRepository verdicts;
    private final TrendItemRepository trendItems;
    private final AdminAccountRepository accounts;
    private final GradeInputsReader gradeInputs;
    private final CurrentParameterSetResolver params;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AdminUserService(UserRepository users, UserGradeRepository grades, ScoreLedgerRepository ledger,
                            VerdictRepository verdicts, TrendItemRepository trendItems, AdminAccountRepository accounts,
                            GradeInputsReader gradeInputs, CurrentParameterSetResolver params, JdbcTemplate jdbc, Clock clock) {
        this.users = users; this.grades = grades; this.ledger = ledger; this.verdicts = verdicts;
        this.trendItems = trendItems; this.accounts = accounts; this.gradeInputs = gradeInputs;
        this.params = params; this.jdbc = jdbc; this.clock = clock;
    }

    public record UserHit(String id, String handle, String grade, String joinedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LedgerRow(String id, String createdAt, String kind, double delta, String reason,
                            String trendItemName, String approvedBy) {}
    public record AdminUserDetail(String id, String handle, String status, String joinedAt, String grade,
                                  String gradeComputedAt, double trustIndex, double activeScore, int judgedCount,
                                  int hitInWindow, int missInWindow, List<String> basis, long abuseFlagCount,
                                  List<LedgerRow> ledger) {}

    @Transactional(readOnly = true)
    public List<UserHit> search(String handlePrefix) {
        if (handlePrefix == null || handlePrefix.isBlank()) {
            throw new AdminValidationException("핸들을 입력하세요");
        }
        return users.findTop20ByHandleStartingWithIgnoreCaseOrderByHandleAsc(handlePrefix.strip()).stream()
                .map(u -> new UserHit(u.getId().toString(), u.getHandle(), gradeOf(u.getId()).name(), DISPLAY.format(u.getJoinedAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        UserAccount u = users.findById(userId).orElseThrow(() -> new AdminNotFoundException("존재하지 않는 유저입니다"));
        Instant now = clock.instant();
        GradeInputsReader.GradeInputs in = gradeInputs.read(userId, now);
        ParameterSet p = params.resolve();
        double ti = TrustIndex.compute(in.hitInWindow(), in.missInWindow(), p);
        UserGrade snapshot = grades.findTopByUserIdOrderByComputedAtDesc(userId).orElse(null);

        List<ScoreLedgerEntry> rows = ledger.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, String> approverNames = accounts.findAllById(rows.stream().map(ScoreLedgerEntry::getApprovedByAdminId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName));
        List<LedgerRow> ledgerRows = rows.stream().map(e -> new LedgerRow(
                e.getId().toString(), DISPLAY.format(e.getCreatedAt()), e.getKind().name(), e.getDelta().doubleValue(),
                e.getReason(), itemNameOf(e.getVerdictId()),
                e.getApprovedByAdminId() == null ? null : approverNames.getOrDefault(e.getApprovedByAdminId(), e.getApprovedByAdminId().toString())))
                .toList();

        List<String> basis = List.of(
                String.format(Locale.US, "TI %.2f = (HIT %d + %.0f) / (HIT %d + MISS %d + %.0f) · 최근 %d일",
                        ti, in.hitInWindow(), p.tiAlpha, in.hitInWindow(), in.missInWindow(), p.tiAlpha + p.tiBeta,
                        GradeInputsReader.TI_WINDOW_DAYS),
                String.format(Locale.US, "AS %.1f = 원장 %d행의 Δ × 0.5^(경과일 / 행별 반감기) 합", in.activeScore(), rows.size()),
                String.format(Locale.US, "판정 완료 %d건(전 기간, 시딩 제외)", in.judgedCount()));

        Long flags = jdbc.queryForObject("SELECT count(*) FROM abuse_flags WHERE user_id = ?", Long.class, userId);
        return new AdminUserDetail(u.getId().toString(), u.getHandle(), u.getStatus().name(), DISPLAY.format(u.getJoinedAt()),
                snapshot == null ? Grade.L0.name() : snapshot.getGrade().name(),
                snapshot == null ? null : DISPLAY.format(snapshot.getComputedAt()),
                ti, in.activeScore(), in.judgedCount(), in.hitInWindow(), in.missInWindow(), basis,
                flags == null ? 0 : flags, ledgerRows);
    }

    private Grade gradeOf(UUID userId) {
        return grades.findTopByUserIdOrderByComputedAtDesc(userId).map(UserGrade::getGrade).orElse(Grade.L0);
    }

    private String itemNameOf(UUID verdictId) {
        if (verdictId == null) return null;
        return verdicts.findById(verdictId).flatMap(v -> trendItems.findById(v.getTrendItemId()))
                .map(i -> i.getCanonicalName()).orElse(null);
    }
}
