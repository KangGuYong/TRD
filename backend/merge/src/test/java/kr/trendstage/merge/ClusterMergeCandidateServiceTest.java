package kr.trendstage.merge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterMergeCandidateServiceTest {

    @Test void 유사도_0_85_이상은_자동병합() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.AUTO_MERGED,
                ClusterMergeCandidateService.classify(0.85));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.AUTO_MERGED,
                ClusterMergeCandidateService.classify(0.9));
    }

    @Test void 유사도_0_75_이상_0_85_미만은_큐적재() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.QUEUED,
                ClusterMergeCandidateService.classify(0.75));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.QUEUED,
                ClusterMergeCandidateService.classify(0.8499));
    }

    @Test void 유사도_0_75_미만은_별개확정() {
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.SEPARATED,
                ClusterMergeCandidateService.classify(0.7499));
        assertEquals(ClusterMergeCandidateService.ProcessOutcome.SEPARATED,
                ClusterMergeCandidateService.classify(0.0));
    }
}
