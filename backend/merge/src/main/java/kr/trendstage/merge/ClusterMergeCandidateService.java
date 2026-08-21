package kr.trendstage.merge;

public class ClusterMergeCandidateService {

    public enum ProcessOutcome { AUTO_MERGED, QUEUED, SEPARATED }

    private static final double AUTO_MERGE_THRESHOLD = 0.85;
    private static final double QUEUE_THRESHOLD = 0.75;

    static ProcessOutcome classify(double similarity) {
        if (similarity >= AUTO_MERGE_THRESHOLD) return ProcessOutcome.AUTO_MERGED;
        if (similarity >= QUEUE_THRESHOLD) return ProcessOutcome.QUEUED;
        return ProcessOutcome.SEPARATED;
    }
}
