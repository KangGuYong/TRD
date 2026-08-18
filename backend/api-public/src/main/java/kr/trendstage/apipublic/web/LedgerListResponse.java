package kr.trendstage.apipublic.web;

import java.util.List;

public record LedgerListResponse(List<LedgerEntryResponse> items, String nextCursor) {}
