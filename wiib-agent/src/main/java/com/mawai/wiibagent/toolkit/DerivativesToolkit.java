package com.mawai.wiibagent.toolkit;

import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.MarketDataService.DerivSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 衍生品统计序列工具（chat 主 agent 挂）：Binance 合约的 OI、账户多空比、大户持仓多空比、主动买卖比，
 * 按周期各取最近 30 个点，四条线按时间戳对齐成一行一个点。取数走 MarketDataService 的裸缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DerivativesToolkit {

    static final int POINTS = 30;
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(WakeWindow.ZONE);

    private final MarketDataService dataService;

    // 描述按语言取自词表 tool.derivatives_history，注解这份只当词表缺失时的兜底
    @Tool(name = "derivatives_history", description = """
            Time series of Binance USDT-M perpetual derivatives statistics for a crypto symbol: the last 30
            points at the given period (1h = the last 30 hours, 4h = 5 days, 1d = 30 days; Binance keeps
            only 30 days). Binance only, not a cross-exchange total. One row per point, oldest first, time
            in UTC+8:
            - oi: open interest in coins; oiUsd: the same in USD
            - accountLsr: among all accounts holding a position, net-long accounts / net-short accounts
              (a head count, so retail-weighted)
            - topTraderPositionLsr: the top 20% of accounts by margin balance, long position notional /
              short position notional
            - takerBuySellRatio: taker buy volume / taker sell volume within the period; takerBuyVol /
              takerSellVol are those two volumes in coins
            A series Binance did not return is listed in `unavailable` and its fields are missing from the
            rows. market_snapshot gives only the latest reading of these; this gives how they moved.""")
    public String derivativesHistory(
            @ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
            @ToolParam(description = "Period per point: 1h / 4h / 1d") String period) {
        String p = period.strip().toLowerCase(Locale.ROOT);
        TreeMap<Long, ObjectNode> rows = new TreeMap<>();
        List<String> unavailable = new ArrayList<>();
        for (DerivSeries kind : DerivSeries.values()) {
            String raw = dataService.derivativesSeries(kind, symbol, p, POINTS);
            if (raw == null) {
                unavailable.add(field(kind));
                continue;
            }
            for (JsonNode pt : MAPPER.readValue(raw, ArrayNode.class)) {
                ObjectNode row = rows.computeIfAbsent(pt.get("timestamp").asLong(),
                        ts -> MAPPER.createObjectNode().put("time", BJ_FMT.format(Instant.ofEpochMilli(ts))));
                switch (kind) {
                    case OPEN_INTEREST -> {
                        put(row, "oi", pt, "sumOpenInterest");
                        put(row, "oiUsd", pt, "sumOpenInterestValue");
                    }
                    case ACCOUNT_LSR -> put(row, "accountLsr", pt, "longShortRatio");
                    case TOP_TRADER_POSITION_LSR -> put(row, "topTraderPositionLsr", pt, "longShortRatio");
                    case TAKER_LSR -> {
                        put(row, "takerBuySellRatio", pt, "buySellRatio");
                        put(row, "takerBuyVol", pt, "buyVol");
                        put(row, "takerSellVol", pt, "sellVol");
                    }
                }
            }
        }
        log.info("[DerivativesTool] derivatives_history {} {} {} 行，缺 {}", symbol, p, rows.size(), unavailable);
        ObjectNode out = MAPPER.createObjectNode();
        if (rows.isEmpty()) {
            out.put("available", false);
            out.put("reason", "derivatives history unavailable for " + symbol + " " + p);
            return MAPPER.writeValueAsString(out);
        }
        out.put("available", true);
        out.put("symbol", symbol);
        out.put("period", p);
        out.put("timezone", "UTC+8");
        if (!unavailable.isEmpty()) {
            out.set("unavailable", MAPPER.valueToTree(unavailable));
        }
        out.set("series", MAPPER.valueToTree(rows.values()));
        return MAPPER.writeValueAsString(out);
    }

    /** 行里代表这条线的字段名，缺线时报它 */
    private static String field(DerivSeries kind) {
        return switch (kind) {
            case OPEN_INTEREST -> "oi";
            case ACCOUNT_LSR -> "accountLsr";
            case TOP_TRADER_POSITION_LSR -> "topTraderPositionLsr";
            case TAKER_LSR -> "takerBuySellRatio";
        };
    }

    /** Binance 的数值都是字符串，转成数字放进行里 */
    private static void put(ObjectNode row, String key, JsonNode pt, String binanceField) {
        row.put(key, new BigDecimal(pt.get(binanceField).asString()));
    }
}
