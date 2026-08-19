package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotBlank;

public record WatchRequest(@NotBlank String keyword) {}
