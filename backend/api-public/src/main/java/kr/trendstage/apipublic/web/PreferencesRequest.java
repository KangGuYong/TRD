package kr.trendstage.apipublic.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import kr.trendstage.persistence.type.TrendCategory;

import java.util.List;

public record PreferencesRequest(
        @NotEmpty List<TrendCategory> categories,
        @Min(0) @Max(23) int notifyHour
) {}
