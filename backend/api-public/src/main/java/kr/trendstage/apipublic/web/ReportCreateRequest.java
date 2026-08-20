package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.trendstage.persistence.type.ReportReason;

import java.util.UUID;

/** OpenAPI ReportCreate 대응. */
public record ReportCreateRequest(
        @NotNull UUID trendItemId,
        @NotNull ReportReason reason,
        @Size(max = 1000) String detail
) {}
