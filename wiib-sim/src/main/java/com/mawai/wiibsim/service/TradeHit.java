package com.mawai.wiibsim.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一段行情里命中的一项：第 tick 格最先碰到它，属于 userId，run 去执行（失败放回索引之类由 run 自己兜）。
 * stopLoss：同一格里止损排前面——一根 K 线里分不清先碰到止损还是止盈，按对用户不利的算
 */
@Slf4j
public record TradeHit(int tick, boolean stopLoss, Long userId, Runnable run) {

    private static final Comparator<TradeHit> TOUCH_ORDER = Comparator.comparingInt(TradeHit::tick)
            .thenComparing(h -> !h.stopLoss());

    /** 同一个用户的按碰到的先后一个个执行，不同用户并发；全部执行完才返回 */
    public static void runByUser(List<TradeHit> hits) {
        if (hits.isEmpty()) return;
        Map<Long, List<TradeHit>> byUser = hits.stream().sorted(TOUCH_ORDER)
                .collect(Collectors.groupingBy(TradeHit::userId, LinkedHashMap::new, Collectors.toList()));
        List<Thread> threads = new ArrayList<>();
        for (List<TradeHit> mine : byUser.values()) {
            threads.add(Thread.startVirtualThread(() -> mine.forEach(TradeHit::runQuietly)));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** run 自己没兜住的异常记一笔，这个用户后面的项接着执行 */
    private void runQuietly() {
        try {
            run.run();
        } catch (Exception e) {
            log.error("命中执行失败 userId={} 第{}格", userId, tick, e);
        }
    }
}
