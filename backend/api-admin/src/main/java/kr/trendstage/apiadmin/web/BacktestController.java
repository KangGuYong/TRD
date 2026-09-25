package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.params.BacktestService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.BacktestDataset;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** ADM-600 백테스트 데이터셋(SP4 §5.3). 업로드는 O/A, 조회·예시는 O/A/Au. 실행은 ParamStudioController. */
@RestController
@RequestMapping("/admin/params/backtest-datasets")
public class BacktestController {

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final BacktestService service;
    private final AdminAccountRepository accounts;

    public BacktestController(BacktestService service, AdminAccountRepository accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    public record DatasetSummary(String id, String name, int caseCount, String sha256, String uploadedBy, String createdAt) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<DatasetSummary> upload(@RequestBody String body, @AuthenticationPrincipal AdminPrincipal actor) {
        BacktestService.Upload up = service.upload(actor.id(), actor.role(), body);
        return ResponseEntity.status(up.created() ? 201 : 200).body(summary(up.dataset()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<DatasetSummary> list() {
        return service.list().stream().map(this::summary).toList();
    }

    @GetMapping(value = "/example", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public String example() {
        return service.example();
    }

    private DatasetSummary summary(BacktestDataset d) {
        String by = accounts.findById(d.getUploadedBy()).map(AdminAccount::getDisplayName).orElse("-");
        return new DatasetSummary(d.getId().toString(), d.getName(), d.getCaseCount(), d.getSha256(), by,
                KST.format(d.getCreatedAt()));
    }
}
