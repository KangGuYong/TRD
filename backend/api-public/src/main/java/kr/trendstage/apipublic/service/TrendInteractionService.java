package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.EndorseConflictException;
import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.apipublic.web.VoteResultResponse;
import kr.trendstage.persistence.entity.Endorsement;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Vote;
import kr.trendstage.persistence.repo.EndorsementRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VoteRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 투표·동의. 둘 다 판정에 입력되지 않는 부가 반응(R1) — 별도 서비스로 분리해 SubmissionService(판정 입력 경로)와 섞이지 않게 한다. */
@Service
public class TrendInteractionService {

    private final TrendItemRepository trends;
    private final VoteRepository votes;
    private final EndorsementRepository endorsements;

    public TrendInteractionService(TrendItemRepository trends, VoteRepository votes, EndorsementRepository endorsements) {
        this.trends = trends; this.votes = votes; this.endorsements = endorsements;
    }

    @Transactional
    public VoteResultResponse vote(UUID trendItemId, UUID userId, boolean willTrend) {
        requireExists(trendItemId);
        Vote v = votes.findByUserIdAndTrendItemId(userId, trendItemId).orElse(null);
        if (v == null) {
            votes.save(new Vote(userId, trendItemId, willTrend));
        } else {
            v.toggleTo(willTrend);
        }
        long yes = votes.countByTrendItemIdAndWillTrend(trendItemId, true);
        long no = votes.countByTrendItemIdAndWillTrend(trendItemId, false);
        long total = yes + no;
        String voteCount = total == 0 ? null
                : String.format("%,d명 참여 · 뜬다 %d%%", total, Math.round(yes * 100.0 / total));
        return new VoteResultResponse(willTrend, voteCount);
    }

    @Transactional
    public void endorse(UUID trendItemId, UUID userId) {
        requireExists(trendItemId);
        if (endorsements.existsByTrendItemIdAndUserId(trendItemId, userId)) {
            throw new EndorseConflictException("이미 동의한 항목입니다");
        }
        endorsements.save(new Endorsement(trendItemId, userId, null));
    }

    private void requireExists(UUID trendItemId) {
        TrendItem item = trends.findById(trendItemId).orElseThrow(() -> new TrendNotFoundException("존재하지 않는 항목입니다"));
        if (item.getState() == TrendState.MERGED) throw new TrendNotFoundException("존재하지 않는 항목입니다");
    }
}
