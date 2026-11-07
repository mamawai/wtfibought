package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * K线历史启动修复：quant 启动后对全部加密币（binance.symbols + crypto-futures-symbols）的
 * 最近 90 天 5m 窗口做缺口检测回补。运行期的新 bar 由 feed 收盘即落库，停机跨段留下的
 * 中段空洞没人管——本任务网格比对精确找洞、只拉缺口区间：
 * 正常重启每币约 1 页尾补（原来无条件全窗重走 ~18 页），冷库时整窗即一个大缺口，退化为全量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineHistoryWarmup {

    /** 与原宏观口径一致的回补窗口：90 天 5m。 */
    private static final Duration HISTORY = Duration.ofDays(90);

    private final KlineHistoryStore historyStore;
    private final BinanceProperties binanceProperties;

    @PostConstruct
    public void start() {
        // 缺口回补通常每币 1 条 SQL + 1 页 REST（几秒），冷库最坏 8币×~18页约两分钟，异步跑不堵启动
        Thread.startVirtualThread(this::run);
    }

    void run() {
        long now = System.currentTimeMillis();
        long from = now - HISTORY.toMillis();
        for (String symbol : binanceProperties.getAllCryptoSymbols()) {
            try {
                int inserted = historyStore.backfillMissing(symbol, from, now);
                log.info("[KlineWarmup] 启动回补完成 symbol={} 新增={}", symbol, inserted);
            } catch (Exception e) {
                // 单币失败不挡全局，缺口留到下次启动再补
                log.warn("[KlineWarmup] 启动回补失败 symbol={} msg={}", symbol, e.toString());
            }
        }
    }
}
