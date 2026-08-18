package kr.trendstage.apipublic.web;

import kr.trendstage.apipublic.service.TrendQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 앱 트렌드 API. OpenAPI /v1/trends 대응. */
@RestController
@RequestMapping("/v1/trends")
public class TrendController {

    private final TrendQueryService service;

    public TrendController(TrendQueryService service) { this.service = service; }

    /** { items: [...], nextCursor: null } (OpenAPI 목록 규약). */
    public record TrendListResponse(List<TrendSummaryResponse> items, String nextCursor) {}

    /** 홈 "오늘의 5개" / 목록. daily=true면 5개로 끝(더 보기 없음). */
    @GetMapping
    public TrendListResponse list(@RequestParam(name = "daily", defaultValue = "false") boolean daily) {
        return new TrendListResponse(service.home(daily), null);
    }

    /** 트렌드 항목 상세. 판정/투표/확산경로 포함. */
    @GetMapping("/{id}")
    public TrendDetailResponse detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
