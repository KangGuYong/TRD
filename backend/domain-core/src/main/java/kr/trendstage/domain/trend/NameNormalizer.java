package kr.trendstage.domain.trend;

import java.text.Normalizer;

/**
 * 제보 원문 → 정규화 키. NFC 강제 + 공백 정리(03 §2①) — 완전일치 병합의 기준.
 * 순수 함수. 임베딩 유사도(회색지대 0.75~0.85)는 별도 배치(cluster_merge, 미구현)가 다룬다.
 */
public final class NameNormalizer {
    private NameNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        return nfc.strip().replaceAll("\\s+", " ").toLowerCase();
    }
}
