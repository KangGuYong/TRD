package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.queues.QueueSummaryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADM-010 진입 화면 집계. 조회 전용이라 REVIEWER 이상 전부 허용(MergeQueueController.list()와 동일 권한). */
@RestController
@RequestMapping("/admin/queues")
public class QueueSummaryController {

    private final QueueSummaryService service;

    public QueueSummaryController(QueueSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public QueueSummaryService.QueueSummaryResponse summary() {
        return service.summarize();
    }
}
