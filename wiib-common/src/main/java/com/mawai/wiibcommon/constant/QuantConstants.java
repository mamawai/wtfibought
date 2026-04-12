package com.mawai.wiibcommon.constant;

import java.util.List;
import java.util.Set;

public final class QuantConstants {

    private QuantConstants() {}

    public static final List<String> WATCH_SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "PAXGUSDT");

    public static final Set<String> ALLOWED_SYMBOLS = Set.copyOf(WATCH_SYMBOLS);

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
