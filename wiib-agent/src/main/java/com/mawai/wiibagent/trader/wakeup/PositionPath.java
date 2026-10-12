package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.trader.TradePairing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 持仓自上次完成决策那轮以来走过的路：这里只算窗口，取数和极值交给 {@link CaseFileBuilder#market}。
 * 本局还没有 OK 轮次、或仓位是那之后才开的，从开仓所在那根 5m 算起。窗口里没收盘的 5m 返回 null，持仓行不带这块。
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
}
