package kr.trendstage.domain.trend;

import kr.trendstage.domain.trend.DailySelectionPicker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DailySelectionPickerTest {

    @Test void 선호_카테고리_일치_항목이_우선한다() {
        UUID meme = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(product, "PRODUCT", 0), // actionPriority 더 좋음(0)
                new DailySelectionPicker.Candidate(meme, "MEME", 1));      // 선호 카테고리지만 priority는 나쁨(1)

        List<UUID> picked = DailySelectionPicker.pick(candidates, Set.of("MEME"), 5);

        assertEquals(List.of(meme, product), picked);
    }

    @Test void 선호_카테고리_내에서는_actionPriority_순으로_정렬한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(a, "MEME", 2),
                new DailySelectionPicker.Candidate(b, "MEME", 0));

        List<UUID> picked = DailySelectionPicker.pick(candidates, Set.of("MEME"), 5);

        assertEquals(List.of(b, a), picked);
    }

    @Test void 상위_N개로_제한한다() {
        var candidates = List.of(
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 0),
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 1),
                new DailySelectionPicker.Candidate(UUID.randomUUID(), "MEME", 2));

        assertEquals(2, DailySelectionPicker.pick(candidates, Set.of("MEME"), 2).size());
    }

    @Test void 선호_카테고리가_비어있으면_actionPriority_순으로만_정렬한다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var candidates = List.of(
                new DailySelectionPicker.Candidate(a, "MEME", 1),
                new DailySelectionPicker.Candidate(b, "PRODUCT", 0));

        assertEquals(List.of(b, a), DailySelectionPicker.pick(candidates, Set.of(), 5));
    }
}
