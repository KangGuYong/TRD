package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReadRequest(@NotNull UUID trendId) {}
