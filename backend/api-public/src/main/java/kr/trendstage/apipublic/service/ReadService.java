package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.persistence.entity.TrendRead;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.TrendReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 완독 기록. 카드 열람 시 서버에 남겨 재로그인/기기 교체에도 유지되게 한다(04 §7.3 확장). */
@Service
public class ReadService {

    private final TrendReadRepository reads;
    private final TrendItemRepository trends;

    public ReadService(TrendReadRepository reads, TrendItemRepository trends) {
        this.reads = reads; this.trends = trends;
    }

    @Transactional
    public void markRead(UUID userId, UUID trendId) {
        if (!trends.existsById(trendId)) throw new TrendNotFoundException("존재하지 않는 항목입니다");
        reads.insertIfAbsent(userId, trendId);
    }

    @Transactional(readOnly = true)
    public List<UUID> list(UUID userId) {
        return reads.findByUserId(userId).stream().map(TrendRead::getTrendItemId).toList();
    }
}
