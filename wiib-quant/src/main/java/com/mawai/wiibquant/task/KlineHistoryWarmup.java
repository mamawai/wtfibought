package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * K线历史启动修复：quant 启动后把 binance.symbols 全部标的（BTC/ETH/SOL/DOGE/XRP/PAXG）的
 * 最近 90 天 5m 窗口无条件全量重走一遍。运行时的尾补（ensureHistoryReady / watchdog）只从
 * "最新 bar"往后补，停机跨段留下的中段空洞永远补不上——本任务全窗翻页 + insertIgnore 幂等，
 * 已有数据约等于纯索引探测，一次把洞补齐。补完才触发宏观预热，冷库时不和长回补重复翻页。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineHistoryWarmup {

    private final KlineHistoryStore historyStore;
    private final BinanceProperties binanceProperties;
    private final MacroContextService macroContextService;

    @PostConstruct
    public void start() {
        // 6币×~18页 REST 约1分钟，异步跑不堵启动
        Thread.startVirtualThread(this::run);
    }

    void run() {
        long now = System.currentTimeMillis();
        long from = now - MacroContextService.HISTORY.toMillis();
        if (binanceProperties.getSymbols() != null) {
            for (String symbol : binanceProperties.getSymbols()) {
                try {
                    int inserted = historyStore.backfill(symbol, from, now);
                    log.info("[KlineWarmup] 启动回补完成 symbol={} 新增={}", symbol, inserted);
                } catch (Exception e) {
                    // 单币失败不挡全局，缺口留给 watchdog / ensureHistoryReady 运行时自愈
                    log.warn("[KlineWarmup] 启动回补失败 symbol={} msg={}", symbol, e.toString());
                }
            }
        }
        macroContextService.warmup();
    }
}
