package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缺口检测是启动回补的省流核心：算错区间要么漏补（数据洞）要么白拉（浪费 REST），两头都不能错。 */
class KlineHistoryStoreMissingRangesTest {

    private static final long BAR = KlineHistoryStore.DEFAULT_BAR_MILLIS;

    @Test
    void emptyDbWholeWindowIsOneGap() {
        // 冷库：整个窗口一个大缺口，等价于原来的全量回补
        List<long[]> gaps = KlineHistoryStore.missingRanges(0, BAR * 9, BAR, Set.of());
        assertEquals(1, gaps.size());
        assertArrayEquals(new long[]{0, BAR * 10}, gaps.getFirst());
    }

    @Test
    void fullDbNoGap() {
        Set<Long> existing = Set.of(0L, BAR, BAR * 2, BAR * 3);
        assertTrue(KlineHistoryStore.missingRanges(0, BAR * 3, BAR, existing).isEmpty());
    }

    @Test
    void middleHoleDetected() {
        // 停机跨段留下的中段空洞：bar2~bar4 缺失
        Set<Long> existing = Set.of(0L, BAR, BAR * 5, BAR * 6);
        List<long[]> gaps = KlineHistoryStore.missingRanges(0, BAR * 6, BAR, existing);
        assertEquals(1, gaps.size());
        assertArrayEquals(new long[]{BAR * 2, BAR * 5}, gaps.getFirst());
    }

    @Test
    void headAndTailGapsDetected() {
        // 头缺（窗口前移露出）+ 尾缺（停机后新bar）各成一段
        Set<Long> existing = Set.of(BAR * 2, BAR * 3);
        List<long[]> gaps = KlineHistoryStore.missingRanges(0, BAR * 5, BAR, existing);
        assertEquals(2, gaps.size());
        assertArrayEquals(new long[]{0, BAR * 2}, gaps.get(0));
        assertArrayEquals(new long[]{BAR * 4, BAR * 6}, gaps.get(1));
    }

    @Test
    void alignUpRoundsToGrid() {
        // now-90d 一般不落在整 5m 上，必须向上对齐，否则第一格永远"缺失"导致每次白拉一页
        assertEquals(BAR, KlineHistoryStore.alignUp(1, BAR));
        assertEquals(BAR, KlineHistoryStore.alignUp(BAR, BAR));
        assertEquals(BAR * 2, KlineHistoryStore.alignUp(BAR + 1, BAR));
    }
}
