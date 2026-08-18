package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotNull;

public record VoteRequest(@NotNull Boolean willTrend) {}
