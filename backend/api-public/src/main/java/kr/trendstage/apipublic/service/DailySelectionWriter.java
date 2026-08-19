package kr.trendstage.apipublic.service;

import kr.trendstage.persistence.entity.DailySelection;
import kr.trendstage.persistence.repo.DailySelectionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "오늘의 5개" 저장 시도. 별도 트랜잭션(REQUIRES_NEW)에서 실행 — 동시 요청 경쟁으로
 * UNIQUE 제약을 위반해도 호출자의 트랜잭션(재조회)을 오염시키지 않는다.
 */
@Service
public class DailySelectionWriter {

    private final DailySelectionRepository repo;

    public DailySelectionWriter(DailySelectionRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void trySave(UUID userId, LocalDate selectionDate, List<UUID> trendItemIds) {
        try {
            List<DailySelection> rows = new ArrayList<>();
            short rank = 1;
            for (UUID trendItemId : trendItemIds) {
                rows.add(new DailySelection(userId, selectionDate, trendItemId, rank++));
            }
            repo.saveAll(rows);
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 저장 완료 — 무시. 호출자가 다시 조회해서 그 결과를 쓴다.
        }
    }
}
