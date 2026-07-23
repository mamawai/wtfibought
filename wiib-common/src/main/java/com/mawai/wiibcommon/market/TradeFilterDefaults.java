package com.mawai.wiibcommon.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Binance 交易过滤器默认值（LOT_SIZE 步长 + 最小名义额），实拉 exchangeInfo 快照 2026-07-23。
 * <p>
 * 兜底用途：sim 的 TradeFilterRegistry 启动时拉官方 exchangeInfo 覆盖这份（拉不到用这份）；
 * quant 的 PositionSizer 下单定量直接用这份做步长对齐（回测/实盘同一口径）。
 * <p>
 * 官方口径：minQty 与 stepSize 当前全部相等，分开存忠实于数据源；
 * 合约下单（非 reduce-only）必须 名义额 ≥ minNotional，平仓/止盈损豁免。
 */
public final class TradeFilterDefaults {

    private TradeFilterDefaults() {}

    /** 单市场过滤器：数量步长 / 最小数量 / 最小名义额(USDT) */
    public record Filter(BigDecimal stepSize, BigDecimal minQty, BigDecimal minNotional) {}

    public static final Map<String, Filter> FUTURES = Map.of(
            "BTCUSDT",  f("0.001", "50"),
            "ETHUSDT",  f("0.001", "20"),
            "DOGEUSDT", f("1",     "5"),
            "SOLUSDT",  f("0.01",  "5"),
            "XRPUSDT",  f("0.1",   "5"),
            "XAUUSDT",  f("0.001", "5"),
            "CLUSDT",   f("0.01",  "5"));

    public static final Map<String, Filter> SPOT = Map.of(
            "BTCUSDT",  f("0.00001", "5"),
            "ETHUSDT",  f("0.0001",  "5"),
            "DOGEUSDT", f("1",       "1"),
            "SOLUSDT",  f("0.001",   "5"),
            "XRPUSDT",  f("0.1",     "5"));

    /** 未配置 symbol 返回 null，调用方按"不校验"处理 */
    public static Filter futures(String symbol) {
        return FUTURES.get(symbol);
    }

    public static Filter spot(String symbol) {
        return SPOT.get(symbol);
    }

    /** 数量向下对齐步长（quant 定量出口用）；step 无效原样返回 */
    public static BigDecimal floorToStep(BigDecimal qty, BigDecimal step) {
        if (qty == null || step == null || step.signum() <= 0) return qty;
        return qty.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static Filter f(String step, String minNotional) {
        BigDecimal s = new BigDecimal(step);
        return new Filter(s, s, new BigDecimal(minNotional));
    }
}
