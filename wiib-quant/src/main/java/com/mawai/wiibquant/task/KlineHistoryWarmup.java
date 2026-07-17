package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * K线历史启动修复：quant 启动后对 binance.symbols 全部标的（BTC/ETH/SOL/DOGE/XRP）的
 * 最近 90 天 5m 窗口做缺口检测回补。运行时的尾补（ensureHistoryReady / watchdog）只从
 * "最新 bar"往后补，停机跨段留下的中段空洞永远补不上——本任务网格比对精确找洞、只拉缺口区间：
 * 正常重启每币约 1 页尾补（原来无条件全窗重走 ~18 页），冷库时整窗即一个大缺口，退化为全量。
 * 补完才触发宏观预热，冷库时不和长回补重复翻页。
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
        // 缺口回补通常每币 1 条 SQL + 1 页 REST（几秒），冷库最坏 5币×~18页约1分钟，异步跑不堵启动
        Thread.startVirtualThread(this::run);
    }

    void run() {
        long now = System.currentTimeMillis();
        long from = now - MacroContextService.HISTORY.toMillis();
        if (binanceProperties.getSymbols() != null) {
            for (String symbol : binanceProperties.getSymbols()) {
                try {
                    int inserted = historyStore.backfillMissing(symbol, from, now);
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
