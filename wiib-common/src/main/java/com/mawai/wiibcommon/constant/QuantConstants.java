package com.mawai.wiibcommon.constant;

import java.util.List;
import java.util.Set;

public final class QuantConstants {

    private QuantConstants() {}

    /**
     * FIBO 策略的篮子，也是两个回测入口（StrategyBacktestController、AiBacktestController）的标的白名单；
     * 叙事对账只听这几个币的 5m 收盘。
     */
    public static final List<String> WATCH_SYMBOLS = List.of("BTCUSDT", "ETHUSDT");

    /** 系统支持查询/操作的标的白名单（API 入参校验） */
    public static final Set<String> ALLOWED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT", "DOGEUSDT");

    public static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "BTCUSDT";
        symbol = symbol.trim().toUpperCase();
        if (symbol.endsWith("USDT") || symbol.endsWith("USDC")) {
            symbol = symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.isBlank()) return "BTCUSDT";
        String normalized = symbol + "USDT";
        if (!ALLOWED_SYMBOLS.contains(normalized)) {
            throw new IllegalArgumentException("仅支持 BTC、ETH、DOGE");
        }
        return normalized;
    }

    /**
     * 宽松归一（不校验白名单、永不抛错）：trim+大写，空/null 回退 BTCUSDT。
     * LLM 工具入口与事件监听用它——模型/事件漏传 symbol 时按 BTC 容错处理，不能把整条链打断。
     */
    public static String normalizeSymbolLenient(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
