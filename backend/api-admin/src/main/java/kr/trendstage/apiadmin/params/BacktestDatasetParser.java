package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.domain.backtest.BacktestCase;
import kr.trendstage.domain.signal.PlatformResolver;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 백테스트 사례 파일(formatVersion 1, SP4 §5.2) → 사례 목록. 운영 판정과 같은 입력 형태(TrendSignal)로 바꾼다:
 * 이름표 → 결정적 UUID, 근거 링크 → PlatformResolver, device·ip 이름표 → 사례 안 그룹 번호(제출 순, §4와 같은 방식).
 * 위반은 AdminValidationException("경로: 메시지") — 경로는 cases[3].submissions[5].at 형태.
 */
@Component
public class BacktestDatasetParser {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CASES = 500;

    private final ObjectMapper objectMapper;

    public BacktestDatasetParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Parsed(String name, List<BacktestCase> cases) {}

    private record Raw(int index, String submitter, Instant at, String evidenceUrl, String device, String ip,
                       boolean seed, Instant joinedAt) {}

    public Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) throw new AdminValidationException("사례 파일이 비어 있습니다");
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new AdminValidationException("사례 파일은 2MB 이하여야 합니다");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new AdminValidationException("JSON 형식이 아닙니다: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) throw new AdminValidationException("최상위는 JSON 객체여야 합니다");
        if (root.path("formatVersion").asInt(-1) != 1) throw invalid("formatVersion", "1이어야 합니다");
        String name = text(root, "name", "name", 120);
        JsonNode cases = root.path("cases");
        if (!cases.isArray() || cases.isEmpty()) throw invalid("cases", "사례가 1건 이상 필요합니다");
        if (cases.size() > MAX_CASES) {
            throw invalid("cases", MAX_CASES + "건 이하여야 합니다 (입력 " + cases.size() + "건)");
        }
        Set<String> ids = new HashSet<>();
        List<BacktestCase> out = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            out.add(parseCase(cases.get(i), "cases[" + i + "]", ids));
        }
        return new Parsed(name, out);
    }

    private BacktestCase parseCase(JsonNode c, String at, Set<String> ids) {
        if (!c.isObject()) throw invalid(at, "객체여야 합니다");
        String caseId = text(c, "caseId", at + ".caseId", 60);
        if (!ids.add(caseId)) throw invalid(at + ".caseId", "중복입니다: " + caseId);
        String title = text(c, "title", at + ".title", 200);
        VerdictResult label = switch (text(c, "label", at + ".label", 10)) {
            case "HIT" -> VerdictResult.HIT;
            case "MISS" -> VerdictResult.MISS;
            default -> throw invalid(at + ".label", "HIT 또는 MISS여야 합니다");
        };
        ReachLevel labelReach = null;
        if (c.hasNonNull("labelReach")) {
            if (label != VerdictResult.HIT) throw invalid(at + ".labelReach", "label이 HIT일 때만 쓸 수 있습니다");
            try {
                labelReach = ReachLevel.valueOf(c.get("labelReach").asText());
            } catch (IllegalArgumentException e) {
                throw invalid(at + ".labelReach", "L1 ~ L4 중 하나여야 합니다");
            }
        }
        Instant deadline = instant(c.get("deadline"), at + ".deadline");
        Integer active = null;
        if (c.hasNonNull("activeSubmitters")) {
            JsonNode a = c.get("activeSubmitters");
            if (!a.isIntegralNumber() || !a.canConvertToInt() || a.asInt() < 0) {
                throw invalid(at + ".activeSubmitters", "0 이상의 정수여야 합니다");
            }
            active = a.asInt();
        }
        JsonNode subs = c.path("submissions");
        if (!subs.isArray() || subs.isEmpty()) throw invalid(at + ".submissions", "제보가 1건 이상 필요합니다");

        List<Raw> raws = new ArrayList<>();
        for (int j = 0; j < subs.size(); j++) {
            JsonNode s = subs.get(j);
            String p = at + ".submissions[" + j + "]";
            if (!s.isObject()) throw invalid(p, "객체여야 합니다");
            Instant when = instant(s.get("at"), p + ".at");
            if (!when.isBefore(deadline)) throw invalid(p + ".at", "deadline보다 앞서야 합니다");
            JsonNode seed = s.get("seed");
            if (seed != null && !seed.isNull() && !seed.isBoolean()) throw invalid(p + ".seed", "true 또는 false여야 합니다");
            raws.add(new Raw(j, text(s, "submitter", p + ".submitter", 60), when,
                    text(s, "evidenceUrl", p + ".evidenceUrl", 2000),
                    optionalText(s, "device", p + ".device"), optionalText(s, "ip", p + ".ip"),
                    seed != null && seed.asBoolean(false),
                    s.hasNonNull("joinedAt") ? instant(s.get("joinedAt"), p + ".joinedAt") : null));
        }
        raws.sort(Comparator.comparing(Raw::at).thenComparingInt(Raw::index));

        Map<String, Integer> devices = new HashMap<>();
        Map<String, Integer> ips = new HashMap<>();
        List<TrendSignal.Entry> entries = new ArrayList<>();
        for (Raw r : raws) {
            entries.add(new TrendSignal.Entry(
                    uuid(caseId, "submission", String.valueOf(r.index())), uuid(caseId, "submitter", r.submitter()),
                    r.seed(), r.at(), null, r.joinedAt(), PlatformResolver.resolve(r.evidenceUrl()).name(),
                    group(devices, r.device()), group(ips, r.ip())));
        }
        return new BacktestCase(caseId, title, label, labelReach, new TrendSignal(deadline, entries, active));
    }

    private static UUID uuid(String caseId, String kind, String key) {
        return UUID.nameUUIDFromBytes(("backtest:" + caseId + ":" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static Integer group(Map<String, Integer> groups, String label) {
        return label == null ? null : groups.computeIfAbsent(label, k -> groups.size() + 1);
    }

    private static String text(JsonNode node, String field, String path, int maxLength) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) throw invalid(path, "문자열 값이 필요합니다");
        String s = v.asText().trim();
        if (s.length() > maxLength) throw invalid(path, maxLength + "자 이하여야 합니다");
        return s;
    }

    private static String optionalText(JsonNode node, String field, String path) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw invalid(path, "문자열이어야 합니다");
        String s = v.asText().trim();
        if (s.length() > 60) throw invalid(path, "60자 이하여야 합니다");
        return s.isEmpty() ? null : s;
    }

    private static Instant instant(JsonNode v, String path) {
        if (v == null || v.isNull() || !v.isTextual()) throw invalid(path, "시각이 필요합니다 (예: 2026-06-01T03:00:00Z)");
        try {
            return Instant.parse(v.asText());
        } catch (DateTimeParseException e) {
            throw invalid(path, "ISO-8601 UTC 시각이어야 합니다 (예: 2026-06-01T03:00:00Z)");
        }
    }

    private static AdminValidationException invalid(String path, String message) {
        return new AdminValidationException(path + ": " + message);
    }
}
