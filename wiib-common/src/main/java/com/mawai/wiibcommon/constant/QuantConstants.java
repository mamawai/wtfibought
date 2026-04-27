package com.mawai.wiibcommon.constant;

import java.util.List;
import java.util.Set;

public final class QuantConstants {

    private QuantConstants() {}

    /**
     * 系统主动轮询的标的：Scheduler / ReflectionTask / Admin 全量触发都用这个。
     * PAXG 因低波动与加密因子错配，Phase 0A 起暂停开仓，故移出主动清单；已有持仓由 SL/TP 自动走完。
     */
    public static final List<String> WATCH_SYMBOLS = List.of("BTCUSDT", "ETHUSDT");

    /**
     * 系统支持查询/操作的标的白名单（API 入参校验）。
     * 保留 PAXGUSDT 以便前端继续访问残留持仓、历史预测、订单记录。
     */
    public static final Set<String> ALLOWED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT", "PAXGUSDT");

    public static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "BTCUSDT";
        symbol = symbol.trim().toUpperCase();
        if (symbol.endsWith("USDT")) symbol = symbol.substring(0, symbol.length() - 4);
        else if (symbol.endsWith("USDC")) symbol = symbol.substring(0, symbol.length() - 4);
        if (symbol.isBlank()) return "BTCUSDT";
        String normalized = symbol + "USDT";
        if (!ALLOWED_SYMBOLS.contains(normalized)) {
            throw new IllegalArgumentException("仅支持 BTC、ETH、PAXG");
        }
        return normalized;
    }
}
