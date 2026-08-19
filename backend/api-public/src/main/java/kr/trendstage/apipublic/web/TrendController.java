package kr.trendstage.apipublic.web;

import kr.trendstage.apipublic.service.TrendInteractionService;
import kr.trendstage.apipublic.service.TrendQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final TrendInteractionService interactions;

    public TrendController(TrendQueryService service, TrendInteractionService interactions) {
        this.service = service;
        this.interactions = interactions;
    }

    /** { items: [...], nextCursor: null } (OpenAPI 목록 규약). */
    public record TrendListResponse(List<TrendSummaryResponse> items, String nextCursor) {}

    /** 홈 "오늘의 5개" / 목록. daily=true면 5개로 끝(더 보기 없음). */
    @GetMapping
    public TrendListResponse list(@RequestParam(name = "daily", defaultValue = "false") boolean daily) {
        return new TrendListResponse(service.home(daily), null);
    }

    /** 트렌드 항목 상세. 판정/투표/확산경로 포함. */
    @GetMapping("/{id}")
    public TrendDetailResponse detail(@PathVariable UUID id, Authentication auth) {
        UUID viewerId = auth != null ? (UUID) auth.getPrincipal() : null;
        return service.detail(id, viewerId);
    }

    @PostMapping("/{id}/vote")
    public VoteResultResponse vote(@PathVariable UUID id, Authentication auth, @Valid @RequestBody VoteRequest req) {
        return interactions.vote(id, userId(auth), req.willTrend());
    }

    @PostMapping("/{id}/endorse")
    public ResponseEntity<Void> endorse(@PathVariable UUID id, Authentication auth) {
        interactions.endorse(id, userId(auth));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
