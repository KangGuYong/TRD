package kr.trendstage.apipublic.web;

public record MeSummaryResponse(
        int streakDays, int quotaUsed, int quotaMax,
        double voteHitRate, int votesTotal, int votesCorrect, int totalRead
) {}
