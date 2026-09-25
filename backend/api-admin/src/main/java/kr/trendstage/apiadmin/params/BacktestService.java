package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.apiadmin.web.AdminNotFoundException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.backtest.Backtest;
import kr.trendstage.domain.backtest.BacktestReport;
import kr.trendstage.persistence.entity.BacktestDataset;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.repo.BacktestDatasetRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-600 백테스트(SP4 S9·S11). 사례 파일은 바꿀 수 없게 보관하고(같은 SHA-256이면 기존 행), 실행 결과는
 * 데이터셋 해시와 함께 드래프트에 남긴다 — 승인 요청의 근거.
 */
@Service
public class BacktestService {

    static final String EXAMPLE = "backtest/synthetic-v1.json";

    private final BacktestDatasetRepository datasets;
    private final BacktestDatasetParser parser;
    private final ParamStudioService studio;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BacktestService(BacktestDatasetRepository datasets, BacktestDatasetParser parser, ParamStudioService studio,
                           AuditLogService auditLogService, ObjectMapper objectMapper, Clock clock) {
        this.datasets = datasets;
        this.parser = parser;
        this.studio = studio;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record Upload(BacktestDataset dataset, boolean created) {}

    /** 드래프트에 남기는 결과(§5.4). */
    public record BacktestResult(UUID datasetId, String datasetName, String sha256, int caseCount, Instant ranAt,
                                 BacktestReport report) {}

    @Transactional
    public Upload upload(UUID actorId, AdminRole actorRole, String raw) {
        BacktestDatasetParser.Parsed parsed = parser.parse(raw);
        String sha = sha256(raw);
        Optional<BacktestDataset> existing = datasets.findBySha256(sha);
        if (existing.isPresent()) return new Upload(existing.get(), false);
        BacktestDataset saved;
        try {
            saved = datasets.saveAndFlush(new BacktestDataset(parsed.name(), sha, parsed.cases().size(), raw,
                    actorId, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new AdminConflictException("dataset-upload-race", "같은 파일이 동시에 올라왔습니다 — 목록을 새로고침하세요");
        }
        auditLogService.record(actorId, actorRole, "BACKTEST_DATASET_UPLOAD", "BACKTEST_DATASET", saved.getId(), Map.of(
                "name", parsed.name(), "caseCount", parsed.cases().size(), "sha256", sha));
        return new Upload(saved, true);
    }

    @Transactional(readOnly = true)
    public List<BacktestDataset> list() {
        return datasets.findAllByOrderByCreatedAtDesc();
    }

    public String example() {
        try {
            return new ClassPathResource(EXAMPLE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional
    public ParameterDraft run(UUID actorId, AdminRole actorRole, UUID datasetId) {
        BacktestDataset ds = datasets.findById(datasetId)
                .orElseThrow(() -> new AdminNotFoundException("데이터셋이 없습니다: " + datasetId));
        ParameterDraft draft = studio.getOrCreateActiveDraft(actorId);
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("승인 대기 중인 드래프트에는 백테스트를 다시 돌릴 수 없습니다");
        }
        BacktestReport report = Backtest.run(parser.parse(ds.getPayload()).cases(),
                studio.currentOperationalParams(), draft.toParameterSet(objectMapper));
        try {
            draft.recordBacktestResult(objectMapper.writeValueAsString(new BacktestResult(
                    ds.getId(), ds.getName(), ds.getSha256(), ds.getCaseCount(), clock.instant(), report)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("백테스트 결과 직렬화 실패", e);
        }
        auditLogService.record(actorId, actorRole, "PARAM_BACKTEST", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "datasetId", ds.getId().toString(), "caseCount", ds.getCaseCount(), "changed", report.changedCount()));
        return draft;
    }

    static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
