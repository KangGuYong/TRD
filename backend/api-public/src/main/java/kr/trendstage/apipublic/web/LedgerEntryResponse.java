package kr.trendstage.apipublic.web;

import java.time.Instant;

public record LedgerEntryResponse(String word, String kind, double delta, String reason, Instant createdAt) {}
