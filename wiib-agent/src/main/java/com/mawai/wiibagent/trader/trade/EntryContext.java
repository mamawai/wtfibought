package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.market.indicator.CryptoIndicatorCalculator;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开仓那一刻的上下文快照，写进计划的 entry_ctx_json，复盘按维度对照用。
 * 字段：orderType / leverage / hourBj / stopPct 必有；regime / atr1h / stopAtr / eventMin / eventTitle / funding
 * 取不到就缺席。任何外部数据失败只记 warn，不挡下单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntryContext {

    /** 找最近 High 事件的前后范围 */
    static final long EVENT_LOOKAROUND_MS = 12 * 3_600_000L;
    /** 1h ATR 用的根数，与工具层同一窗口 */
    private static final int ATR_BARS = 192;

    private final MarketDataService dataService;
    private final KlineFetcher klineFetcher;
    private final EconCalendarMapper calendarMapper;
    private final BinanceRestClient binanceRestClient;

    public String capture(String symbol, String orderType, BigDecimal entryRef, BigDecimal stopPrice,
                          int leverage, long nowMs) {
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("orderType", orderType);
        out.put("leverage", leverage);
        out.put("hourBj", Instant.ofEpochMilli(nowMs).atZone(WakeWindow.ZONE).getHour());
        BigDecimal stopDist = entryRef.subtract(stopPrice).abs();
        out.put("stopPct", stopDist.multiply(BigDecimal.valueOf(100)).divide(entryRef, 3, RoundingMode.HALF_UP));
        try {
            MarketAssembly a = dataService.assemble(sym);
            if (a.available()) {
                out.put("regime", a.snapshot().regime().name());
            }
        } catch (Exception e) {
            log.warn("[EntryContext] regime 取不到 {} msg={}", sym, e.getMessage());
        }
        try {
            List<KlineBar> bars = klineFetcher.fetch(sym, "1h", ATR_BARS);
            if (!bars.isEmpty()) {
                if (CryptoIndicatorCalculator.calcAll(IndicatorToolkit.toCalcRows(bars), false).get("atr14") instanceof BigDecimal atr
                        && atr.signum() > 0) {
                    out.put("atr1h", atr);
                    out.put("stopAtr", stopDist.divide(atr, 2, RoundingMode.HALF_UP));
                }
            }
        } catch (Exception e) {
            log.warn("[EntryContext] 1h ATR 取不到 {} msg={}", sym, e.getMessage());
        }
        try {
            calendarMapper.selectWindow(nowMs - EVENT_LOOKAROUND_MS, nowMs + EVENT_LOOKAROUND_MS).stream()
                    .min(Comparator.comparingLong(r -> Math.abs(r.getEventTime() - nowMs)))
                    .ifPresent(r -> {
                        out.put("eventMin", (r.getEventTime() - nowMs) / 60_000);
                        out.put("eventTitle", r.getTitle());
                    });
        } catch (Exception e) {
            log.warn("[EntryContext] 日历取不到 msg={}", e.getMessage());
        }
        try {
            String funding = MAPPER.readTree(binanceRestClient.getPremiumIndex(sym)).path("lastFundingRate").asString(null);
            if (funding != null) {
                out.put("funding", funding);
            }
        } catch (Exception e) {
            log.warn("[EntryContext] 资金费取不到 {} msg={}", sym, e.getMessage());
        }
        return MAPPER.writeValueAsString(out);
    }
}
