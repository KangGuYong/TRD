package kr.trendstage.domain.signal;

import kr.trendstage.domain.verdict.TrendSignal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 독립 제보자 수(SP4 S5). 같은 기기(모드 DEVICE·DEVICE_OR_IP) 또는 같은 IP(DEVICE_OR_IP) 그룹을 공유하는
 * 제보자를 전이적으로 묶는다(union-find). 그룹이 null인 제보는 잇지 않는다. 시딩은 호출 측이 빼고 넘긴다.
 */
public final class Independence {
    private Independence() {}

    public static int count(List<TrendSignal.Entry> nonSeed, IndependenceMode mode) {
        List<UUID> users = nonSeed.stream().map(TrendSignal.Entry::userId).distinct().toList();
        if (mode == IndependenceMode.OFF) return users.size();

        Map<UUID, UUID> parent = new HashMap<>();
        users.forEach(u -> parent.put(u, u));
        Map<Integer, UUID> byDevice = new HashMap<>();
        Map<Integer, UUID> byIp = new HashMap<>();
        for (TrendSignal.Entry e : nonSeed) {
            if (e.deviceGroup() != null) {
                UUID first = byDevice.putIfAbsent(e.deviceGroup(), e.userId());
                if (first != null) union(parent, first, e.userId());
            }
            if (mode == IndependenceMode.DEVICE_OR_IP && e.ipGroup() != null) {
                UUID first = byIp.putIfAbsent(e.ipGroup(), e.userId());
                if (first != null) union(parent, first, e.userId());
            }
        }
        return (int) users.stream().map(u -> find(parent, u)).distinct().count();
    }

    private static UUID find(Map<UUID, UUID> parent, UUID u) {
        UUID root = u;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
