package kr.trendstage.apipublic.web;

import kr.trendstage.persistence.type.TrendCategory;

import java.util.List;

public record PreferencesResponse(List<TrendCategory> categories, int notifyHour) {}
