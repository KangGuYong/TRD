package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.trend.TrendItemAdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** ADM-110/111. 조회 전용 화면이라 4개 역할 전부 허용(MergeQueueController.list()/VerdictController.list()와 동일 권한). */
@RestController
@RequestMapping("/admin/trend-items")
public class TrendItemController {

    private final TrendItemAdminService service;

    public TrendItemController(TrendItemAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<TrendItemAdminService.TrendItemSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public TrendItemAdminService.TrendItemDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
