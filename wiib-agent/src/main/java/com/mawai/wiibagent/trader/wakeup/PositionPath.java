package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.trader.TradePairing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自上次完成决策那轮以来走过的路：这里只算窗口，取数和极值交给 {@link CaseFileBuilder#market}。
 * 持仓的（{@link #build}）本局还没有 OK 轮次、或仓位是那之后才开的，从开仓所在那根 5m 算起；
 * 一个币的（{@link #symbol}）没有 OK 轮次就取刚过去的一个心跳周期。窗口里没收盘的 5m 返回 null，那一行就不带。
 */
@Component
@RequiredArgsConstructor
public class PositionPath {

    private static final long BAR_MS = KlineHistoryStore.DEFAULT_BAR_MILLIS;

    private final CaseFileBuilder caseFiles;

    /**
     * @param lastOkWakeMs 本局最近一条 OK 决策行的唤醒时刻，null=本局还没有
     * @param wakeTime     这一轮的唤醒时刻（例行=K 线收盘时刻，警报/手动=触发时刻）
     */
    public CaseFileBuilder.MarketWindow build(FuturesPositionDTO pos, Long lastOkWakeMs, long wakeTime) {
        long opened = TradePairing.msOf(pos.getCreatedAt());
        long from = lastOkWakeMs == null ? opened : Math.max(lastOkWakeMs, opened);
        // 起点落在哪根里面，那根整根算进来；唤醒时刻所在那根还没收盘，不算
        long fromBar = WakeIntervals.align(from, BAR_MS);
        long toBar = WakeIntervals.align(wakeTime, BAR_MS);
        return caseFiles.market(pos.getSymbol(), fromBar, toBar);
    }

    /** 一个币自上次 OK 轮以来的窗口；本局还没有 OK 轮次就是唤醒时刻前的一个心跳周期 */
    public CaseFileBuilder.MarketWindow symbol(String symbol, Long lastOkWakeMs, long wakeTime, long intervalMs) {
        long toBar = WakeIntervals.align(wakeTime, BAR_MS);
        long from = lastOkWakeMs == null ? toBar - intervalMs : lastOkWakeMs;
        long fromBar = WakeIntervals.align(from, BAR_MS);
        return caseFiles.market(symbol, fromBar, toBar);
    }

    /** 一个币截至 now 最近 1h / 4h / 24h 的涨跌 %，本地缺 K 线的那格为 null */
    public List<BigDecimal> changes(String symbol, long now) {
        return caseFiles.changes(symbol, now, 3_600_000L, 4 * 3_600_000L, 24 * 3_600_000L);
    }

    /** 刚收盘的那根心跳周期 K 线（5m 合成）：唤醒时刻对齐到周期，它所在那根还没收盘不算 */
    public CaseFileBuilder.MarketWindow lastClosedBar(String symbol, long wakeTime, long intervalMs) {
        long aligned = WakeIntervals.align(wakeTime, intervalMs);
        return caseFiles.market(symbol, aligned - intervalMs, aligned);
    }
}
