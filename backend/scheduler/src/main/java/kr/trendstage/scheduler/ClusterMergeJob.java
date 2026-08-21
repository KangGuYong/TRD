package kr.trendstage.scheduler;

import kr.trendstage.merge.ClusterMergeCandidateService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 임베딩 유사도 병합(03 §2③④) 크론 진입점. 실제 처리는 ClusterMergeCandidateService(:merge)가
 * 담당한다 — 관리자 수동 트리거(BatchJobController, api-admin)와 로직·락을 공유하기 위함.
 * 멱등: trend_items.merge_checked_at으로 이미 처리한 항목은 매일 다시 비교하지 않는다.
 */
@Component
public class ClusterMergeJob {

    private static final Logger log = LoggerFactory.getLogger(ClusterMergeJob.class);

    private final ClusterMergeCandidateService candidateService;

    public ClusterMergeJob(ClusterMergeCandidateService candidateService) {
        this.candidateService = candidateService;
    }

    /** 일 1회(CLAUDE.md 배치 표). */
    @Scheduled(cron = "${jobs.cluster-merge.cron:0 0 17 * * *}")
    @SchedulerLock(name = "cluster_merge", lockAtMostFor = "PT60M", lockAtLeastFor = "PT5M")
    public void run() {
        ClusterMergeCandidateService.ClusterMergeResult result = candidateService.runNow();
        log.info("cluster_merge 완료: 후보 {} · 자동병합 {} · 큐적재 {} · 별개확정 {} · 실패 {}",
                result.candidates(), result.autoMerged(), result.queued(), result.separated(), result.failed());
    }
}
