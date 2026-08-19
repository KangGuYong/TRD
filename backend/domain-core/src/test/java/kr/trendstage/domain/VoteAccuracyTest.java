package kr.trendstage.domain;

import kr.trendstage.domain.vote.VoteAccuracy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteAccuracyTest {

    @Test void 투표가_없으면_적중률_0() {
        var r = VoteAccuracy.compute(List.of());
        assertEquals(0, r.total());
        assertEquals(0, r.correct());
        assertEquals(0.0, r.hitRate(), 1e-9);
    }

    @Test void 예측이_맞은_경우만_correct에_들어간다() {
        var r = VoteAccuracy.compute(List.of(
                new VoteAccuracy.VoteOutcome(true, true),   // 뜬다에 투표, 실제 HIT → 맞음
                new VoteAccuracy.VoteOutcome(true, false),  // 뜬다에 투표, 실제 MISS → 틀림
                new VoteAccuracy.VoteOutcome(false, false)  // 안 뜬다에 투표, 실제 MISS → 맞음
        ));
        assertEquals(3, r.total());
        assertEquals(2, r.correct());
        assertEquals(2.0 / 3.0, r.hitRate(), 1e-9);
    }
}
