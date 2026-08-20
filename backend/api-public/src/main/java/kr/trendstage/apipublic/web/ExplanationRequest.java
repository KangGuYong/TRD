package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /v1/reports/{id}/explanation 요청 본문. */
public record ExplanationRequest(@NotBlank @Size(max = 2000) String text) {}
