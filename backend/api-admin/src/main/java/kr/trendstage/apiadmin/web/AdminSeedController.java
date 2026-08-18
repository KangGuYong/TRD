package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.seed.AdminSeedService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ADM-500. 등록은 OPERATOR 이상, 담당자별 적중률 조회는 4개 역할 전부(MergeQueueController.list()와 동일 권한). */
@RestController
@RequestMapping("/admin/seed")
public class AdminSeedController {

    private final AdminSeedService service;

    public AdminSeedController(AdminSeedService service) {
        this.service = service;
    }

    @PostMapping("/submissions")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public AdminSeedService.SeedSubmissionResult register(@RequestBody AdminSeedService.SeedSubmissionRequest req,
                                                            @AuthenticationPrincipal AdminPrincipal actor) {
        return service.registerSeed(actor.id(), req);
    }

    @GetMapping("/accuracy")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<AdminSeedService.SeedAccuracyRow> accuracy() {
        return service.listAccuracy();
    }
}
