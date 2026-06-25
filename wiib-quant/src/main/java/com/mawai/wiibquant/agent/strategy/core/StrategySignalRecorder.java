package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.entity.StrategySignalLog;
import com.mawai.wiibquant.mapper.StrategySignalLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** strategy_signal 落库；重复 bar 信号静默忽略，避免 WS 重发造成噪音。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySignalRecorder {

    private static final int TEXT_LIMIT = 512;
    private static final String LIVE_MODE = "LIVE";

    private final StrategySignalLogMapper mapper;

    public void record(StrategySignal signal, String legTags) {
        if (signal == null) return;
        try {
            StrategySignalLog row = new StrategySignalLog();
            row.setStrategyId(signal.strategyId());
            row.setSymbol(signal.symbol());
            row.setSide(signal.side());
            row.setMode(LIVE_MODE);
            row.setEntryRefPrice(signal.entryRefPrice());
            row.setStopLoss(signal.stopLossPrice());
            row.setTakeProfit(signal.takeProfitPrice());
            row.setScore(BigDecimal.valueOf(signal.score()).setScale(4, RoundingMode.HALF_UP));
            row.setReason(clip(signal.reason()));
            row.setLegTags(clip(legTags));
            row.setBarCloseTime(signal.barCloseTime());
            mapper.insert(row);
            log.info("[StrategySignal] mode={} strategy={} symbol={} side={} tags={}",
                    row.getMode(), row.getStrategyId(), row.getSymbol(), row.getSide(), row.getLegTags());
        } catch (DuplicateKeyException e) {
            log.debug("[StrategySignal] 重复信号忽略 strategy={} symbol={} barCloseTime={}",
                    signal.strategyId(), signal.symbol(), signal.barCloseTime());
        } catch (Exception e) {
            // 信号日志是观测面，失败不能阻断策略运行。
            log.warn("[StrategySignal] 落库失败 strategy={} symbol={} msg={}",
                    signal.strategyId(), signal.symbol(), e.getMessage());
        }
    }

    private String clip(String text) {
        if (text == null || text.length() <= TEXT_LIMIT) return text;
        return text.substring(0, TEXT_LIMIT);
    }
}
