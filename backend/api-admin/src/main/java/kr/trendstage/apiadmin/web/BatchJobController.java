package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.merge.ClusterMergeCandidateService;
import kr.trendstage.merge.ClusterMergeCandidateService.ClusterMergeResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/** 배치 잡 수동 실행. 이번 스코프는 cluster_merge 하나뿐(verdict_runner/grade_recalc는 별도 검토 필요). */
@RestController
@RequestMapping("/admin/batch-jobs")
public class BatchJobController {

    private final ClusterMergeCandidateService clusterMergeCandidateService;
    private final AuditLogService auditLogService;

    public BatchJobController(ClusterMergeCandidateService clusterMergeCandidateService,
                               AuditLogService auditLogService) {
        this.clusterMergeCandidateService = clusterMergeCandidateService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/cluster-merge/run")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ClusterMergeResult runClusterMerge(@AuthenticationPrincipal AdminPrincipal actor) {
        Optional<ClusterMergeResult> result = clusterMergeCandidateService.tryRunNow();
        if (result.isEmpty()) {
            throw new BatchJobAlreadyRunningException("이미 실행 중입니다");
        }
        ClusterMergeResult r = result.get();
        auditLogService.record(actor.id(), actor.role(), "CLUSTER_MERGE_MANUAL_TRIGGER", "BATCH_JOB", null,
                Map.of("candidates", r.candidates(), "autoMerged", r.autoMerged(),
                        "queued", r.queued(), "separated", r.separated(), "failed", r.failed()));
        return r;
    }
}
