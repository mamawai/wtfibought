package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.trader.TradePairing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
     * @param boundaryTime 本次边界（例行=K 线收盘时刻，警报/手动=触发时刻）
     */
    public CaseFileBuilder.MarketWindow build(FuturesPositionDTO pos, Long lastOkWakeMs, long boundaryTime) {
        long opened = TradePairing.msOf(pos.getCreatedAt());
        long from = lastOkWakeMs == null ? opened : Math.max(lastOkWakeMs, opened);
        // 起点落在哪根里面，那根整根算进来；边界所在那根还没收盘，不算
        long fromBar = from - Math.floorMod(from, BAR_MS);
        long toBar = boundaryTime - Math.floorMod(boundaryTime, BAR_MS);
        return caseFiles.market(pos.getSymbol(), fromBar, toBar);
    }

    /** 一个币自上次 OK 轮以来的窗口；本局还没有 OK 轮次就是边界前的一个心跳周期 */
    public CaseFileBuilder.MarketWindow symbol(String symbol, Long lastOkWakeMs, long boundaryTime, long intervalMs) {
        long toBar = boundaryTime - Math.floorMod(boundaryTime, BAR_MS);
        long from = lastOkWakeMs == null ? toBar - intervalMs : lastOkWakeMs;
        long fromBar = from - Math.floorMod(from, BAR_MS);
        return caseFiles.market(symbol, fromBar, toBar);
    }

    /** 刚收盘的那根心跳周期 K 线（5m 合成）：边界对齐到周期，边界所在那根还没收盘不算 */
    public CaseFileBuilder.MarketWindow lastClosedBar(String symbol, long boundaryTime, long intervalMs) {
        long aligned = boundaryTime - Math.floorMod(boundaryTime, intervalMs);
        return caseFiles.market(symbol, aligned - intervalMs, aligned);
    }
}
