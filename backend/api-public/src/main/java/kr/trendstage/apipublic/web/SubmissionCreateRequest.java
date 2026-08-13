package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.trendstage.persistence.type.TrendCategory;

/** OpenAPI SubmissionCreate 대응. */
public record SubmissionCreateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull TrendCategory category,
        @NotBlank @Size(max = 60) String platform,
        @NotBlank String evidenceUrl,
        @NotNull Integer confidence,
        @NotNull Boolean disclosure,
        @NotBlank @Size(max = 200) String oneLine
) {}
