package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Binance USDⓈ-M 永续合约风险限额表（杠杆/MMR/维持保证金速算数）。
 * <p>
 * 数据来源：Binance 官方"合约杠杆与保证金"页面，规则更新时间 2025-08-19。
 * <p>
 * 档位选择：按"仓位 USDT 名义价值"匹配半开区间 [floor, cap)。
 * 强平公式：MM = notional × MMR − maintAmount。
 * <p>
 * 已配置：BTCUSDT、ETHUSDT、DOGEUSDT、SOLUSDT、XRPUSDT、BNBUSDT、XAUUSDT、CLUSDT，
 * 及 TradFi 股票/ETF 永续：SNDKUSDT、SOXLUSDT、SKHYNIXUSDT、MUUSDT、KORUUSDT、SPCXUSDT。
 * 新增 symbol 必须先在此处补完档位数据，否则开仓抛 FUTURES_SYMBOL_NOT_CONFIGURED。
 */
@Component
public class FuturesLeverageBracketRegistry {

    /**
     * 单档风险限额。
     * @param tier           档位编号（1 起）
     * @param notionalFloor  仓位名义价值下界（闭）
     * @param notionalCap    仓位名义价值上界（开）
     * @param maxLeverage    该档允许的最大杠杆
     * @param mmr            维持保证金率
     * @param maintAmount    维持保证金速算数（用于跨档平滑补偿）
     */
    public record Bracket(
            int tier,
            BigDecimal notionalFloor,
            BigDecimal notionalCap,
            int maxLeverage,
            BigDecimal mmr,
            BigDecimal maintAmount
    ) {}

    private static final List<Bracket> BTC_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("300000"),       150, bd("0.0040"), bd("0")),
            new Bracket(2,  bd("300000"),       bd("800000"),       100, bd("0.0050"), bd("300")),
            new Bracket(3,  bd("800000"),       bd("3000000"),      75,  bd("0.0065"), bd("1500")),
            new Bracket(4,  bd("3000000"),      bd("12000000"),     50,  bd("0.0100"), bd("12000")),
            new Bracket(5,  bd("12000000"),     bd("70000000"),     25,  bd("0.0200"), bd("132000")),
            new Bracket(6,  bd("70000000"),     bd("100000000"),    20,  bd("0.0250"), bd("482000")),
            new Bracket(7,  bd("100000000"),    bd("230000000"),    10,  bd("0.0500"), bd("2982000")),
            new Bracket(8,  bd("230000000"),    bd("480000000"),    5,   bd("0.1000"), bd("14482000")),
            new Bracket(9,  bd("480000000"),    bd("600000000"),    4,   bd("0.1250"), bd("26482000")),
            new Bracket(10, bd("600000000"),    bd("800000000"),    3,   bd("0.1500"), bd("41482000")),
            new Bracket(11, bd("800000000"),    bd("1200000000"),   2,   bd("0.2500"), bd("121482000")),
            new Bracket(12, bd("1200000000"),   bd("1800000000"),   1,   bd("0.5000"), bd("421482000"))
    );

    private static final List<Bracket> ETH_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("300000"),       150, bd("0.0040"), bd("0")),
            new Bracket(2,  bd("300000"),       bd("800000"),       100, bd("0.0050"), bd("300")),
            new Bracket(3,  bd("800000"),       bd("3000000"),      75,  bd("0.0065"), bd("1500")),
            new Bracket(4,  bd("3000000"),      bd("12000000"),     50,  bd("0.0100"), bd("12000")),
            new Bracket(5,  bd("12000000"),     bd("50000000"),     25,  bd("0.0200"), bd("132000")),
            new Bracket(6,  bd("50000000"),     bd("65000000"),     20,  bd("0.0250"), bd("382000")),
            new Bracket(7,  bd("65000000"),     bd("150000000"),    10,  bd("0.0500"), bd("2007000")),
            new Bracket(8,  bd("150000000"),    bd("320000000"),    5,   bd("0.1000"), bd("9507000")),
            new Bracket(9,  bd("320000000"),    bd("400000000"),    4,   bd("0.1250"), bd("17507000")),
            new Bracket(10, bd("400000000"),    bd("530000000"),    3,   bd("0.1500"), bd("27507000")),
            new Bracket(11, bd("530000000"),    bd("800000000"),    2,   bd("0.2500"), bd("80507000")),
            new Bracket(12, bd("800000000"),    bd("1200000000"),   1,   bd("0.5000"), bd("280507000"))
    );

    // DOGE: meme 币高波动，10 档，档位 1 上限 80K USDT、MMR 起点 0.65%（比 BTC/ETH 高）。
    private static final List<Bracket> DOGE_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("80000"),        75, bd("0.0065"),  bd("0")),
            new Bracket(2,  bd("80000"),        bd("150000"),       50, bd("0.0100"),  bd("280")),
            new Bracket(3,  bd("150000"),       bd("750000"),       40, bd("0.0125"),  bd("655")),
            new Bracket(4,  bd("750000"),       bd("2000000"),      25, bd("0.0200"),  bd("6280")),
            new Bracket(5,  bd("2000000"),      bd("4000000"),      20, bd("0.0250"),  bd("16280")),
            new Bracket(6,  bd("4000000"),      bd("20000000"),     10, bd("0.0500"),  bd("116280")),
            new Bracket(7,  bd("20000000"),     bd("40000000"),     5,  bd("0.1000"),  bd("1116280")),
            new Bracket(8,  bd("40000000"),     bd("50000000"),     4,  bd("0.1250"),  bd("2116280")),
            new Bracket(9,  bd("50000000"),     bd("100000000"),    2,  bd("0.2500"),  bd("8366280")),
            new Bracket(10, bd("100000000"),    bd("200000000"),    1,  bd("0.5000"),  bd("33366280"))
    );

    // SOL: 主流山寨，10 档，档位 1 上限 50K USDT、MMR 起点 0.50%、最大 100x。
    // 数据来源：Binance 公共 brackets 接口（主网口径，updateTime 2025-06-23）。
    private static final List<Bracket> SOL_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("50000"),        100, bd("0.0050"),  bd("0")),
            new Bracket(2,  bd("50000"),        bd("400000"),       75,  bd("0.0065"),  bd("75")),
            new Bracket(3,  bd("400000"),       bd("1000000"),      50,  bd("0.0100"),  bd("1475")),
            new Bracket(4,  bd("1000000"),      bd("4000000"),      25,  bd("0.0200"),  bd("11475")),
            new Bracket(5,  bd("4000000"),      bd("8000000"),      20,  bd("0.0250"),  bd("31475")),
            new Bracket(6,  bd("8000000"),      bd("40000000"),     10,  bd("0.0500"),  bd("231475")),
            new Bracket(7,  bd("40000000"),     bd("80000000"),     5,   bd("0.1000"),  bd("2231475")),
            new Bracket(8,  bd("80000000"),     bd("100000000"),    4,   bd("0.1250"),  bd("4231475")),
            new Bracket(9,  bd("100000000"),    bd("200000000"),    2,   bd("0.2500"),  bd("16731475")),
            new Bracket(10, bd("200000000"),    bd("400000000"),    1,   bd("0.5000"),  bd("66731475"))
    );

    // XRP: 11 档，档位 1 上限 40K USDT、MMR 起点 0.50%、最大 100x。
    // 数据来源：Binance 公共 brackets 接口（主网口径，updateTime 2025-07-17）。
    private static final List<Bracket> XRP_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("40000"),        100, bd("0.0050"),  bd("0")),
            new Bracket(2,  bd("40000"),        bd("80000"),        75,  bd("0.0060"),  bd("40")),
            new Bracket(3,  bd("80000"),        bd("150000"),       50,  bd("0.0100"),  bd("360")),
            new Bracket(4,  bd("150000"),       bd("400000"),       40,  bd("0.0125"),  bd("735")),
            new Bracket(5,  bd("400000"),       bd("1000000"),      25,  bd("0.0200"),  bd("3735")),
            new Bracket(6,  bd("1000000"),      bd("2000000"),      20,  bd("0.0250"),  bd("8735")),
            new Bracket(7,  bd("2000000"),      bd("10000000"),     10,  bd("0.0500"),  bd("58735")),
            new Bracket(8,  bd("10000000"),     bd("20000000"),     5,   bd("0.1000"),  bd("558735")),
            new Bracket(9,  bd("20000000"),     bd("25000000"),     4,   bd("0.1250"),  bd("1058735")),
            new Bracket(10, bd("25000000"),     bd("50000000"),     2,   bd("0.2500"),  bd("4183735")),
            new Bracket(11, bd("50000000"),     bd("100000000"),    1,   bd("0.5000"),  bd("16683735"))
    );

    // BNB: 平台币，10 档，档位1 上限仅 10K USDT（各币中最小）、MMR 起点 0.50%、最大 75x。
    // 数据来源：Binance 主网 /fapi/v1/leverageBracket（实拉 2026-07-23，cum 速算数逐档验算自洽）。
    private static final List<Bracket> BNB_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("10000"),        75, bd("0.0050"),  bd("0")),
            new Bracket(2,  bd("10000"),        bd("200000"),       50, bd("0.0060"),  bd("10")),
            new Bracket(3,  bd("200000"),       bd("400000"),       40, bd("0.0100"),  bd("810")),
            new Bracket(4,  bd("400000"),       bd("1500000"),      25, bd("0.0200"),  bd("4810")),
            new Bracket(5,  bd("1500000"),      bd("3000000"),      20, bd("0.0250"),  bd("12310")),
            new Bracket(6,  bd("3000000"),      bd("15000000"),     10, bd("0.0500"),  bd("87310")),
            new Bracket(7,  bd("15000000"),     bd("30000000"),     5,  bd("0.1000"),  bd("837310")),
            new Bracket(8,  bd("30000000"),     bd("37500000"),     4,  bd("0.1250"),  bd("1587310")),
            new Bracket(9,  bd("37500000"),     bd("75000000"),     2,  bd("0.2500"),  bd("6274810")),
            new Bracket(10, bd("75000000"),     bd("150000000"),    1,  bd("0.5000"),  bd("25024810"))
    );

    // 大宗商品（TradFi 永续：黄金 XAU / 原油 CL）：二者档位完全一致（与 SOL 同结构），10 档，档位1 上限 50K、MMR 0.50%、最大 100x。
    // 数据来源：Binance 主网 /fapi/v1/leverageBracket（XAUUSDT & CLUSDT，实拉核对一致）。
    private static final List<Bracket> COMMODITY_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("50000"),        100, bd("0.0050"),  bd("0")),
            new Bracket(2,  bd("50000"),        bd("400000"),       75,  bd("0.0065"),  bd("75")),
            new Bracket(3,  bd("400000"),       bd("1000000"),      50,  bd("0.0100"),  bd("1475")),
            new Bracket(4,  bd("1000000"),      bd("4000000"),      25,  bd("0.0200"),  bd("11475")),
            new Bracket(5,  bd("4000000"),      bd("8000000"),      20,  bd("0.0250"),  bd("31475")),
            new Bracket(6,  bd("8000000"),      bd("40000000"),     10,  bd("0.0500"),  bd("231475")),
            new Bracket(7,  bd("40000000"),     bd("80000000"),     5,   bd("0.1000"),  bd("2231475")),
            new Bracket(8,  bd("80000000"),     bd("100000000"),    4,   bd("0.1250"),  bd("4231475")),
            new Bracket(9,  bd("100000000"),    bd("200000000"),    2,   bd("0.2500"),  bd("16731475")),
            new Bracket(10, bd("200000000"),    bd("400000000"),    1,   bd("0.5000"),  bd("66731475"))
    );

    // TradFi 美股/存储芯片股永续（闪迪 SNDK / 半导体3X SOXL / SK海力士 / 美光 MU）：四者档位完全一致，
    // 11 档，档位1 上限 50K、MMR 起点 1%（股票波动大起点比主流币高）、最大 50x。
    // 数据来源：Binance 主网 /fapi/v1/leverageBracket（实拉 2026-07-23，cum 速算数逐档验算自洽，下同）。
    private static final List<Bracket> US_STOCK_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("50000"),        50, bd("0.0100"),  bd("0")),
            new Bracket(2,  bd("50000"),        bd("200000"),       40, bd("0.0125"),  bd("125")),
            new Bracket(3,  bd("200000"),       bd("500000"),       25, bd("0.0200"),  bd("1625")),
            new Bracket(4,  bd("500000"),       bd("1000000"),      20, bd("0.0250"),  bd("4125")),
            new Bracket(5,  bd("1000000"),      bd("2000000"),      15, bd("0.0333"),  bd("12425")),
            new Bracket(6,  bd("2000000"),      bd("5000000"),      10, bd("0.0500"),  bd("45825")),
            new Bracket(7,  bd("5000000"),      bd("8000000"),      5,  bd("0.1000"),  bd("295825")),
            new Bracket(8,  bd("8000000"),      bd("20000000"),     4,  bd("0.1250"),  bd("495825")),
            new Bracket(9,  bd("20000000"),     bd("50000000"),     3,  bd("0.1667"),  bd("1329825")),
            new Bracket(10, bd("50000000"),     bd("100000000"),    2,  bd("0.2500"),  bd("5494825")),
            new Bracket(11, bd("100000000"),    bd("200000000"),    1,  bd("0.5000"),  bd("30494825"))
    );

    // KORU（三倍做多韩国ETF）：杠杆ETF自带3倍波动，9 档，MMR 起点即 2%、最大仅 25x（全场最保守）。
    private static final List<Bracket> KORU_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("250000"),       25, bd("0.0200"),  bd("0")),
            new Bracket(2,  bd("250000"),       bd("1000000"),      20, bd("0.0250"),  bd("1250")),
            new Bracket(3,  bd("1000000"),      bd("2000000"),      15, bd("0.0333"),  bd("9550")),
            new Bracket(4,  bd("2000000"),      bd("5000000"),      10, bd("0.0500"),  bd("42950")),
            new Bracket(5,  bd("5000000"),      bd("8000000"),      5,  bd("0.1000"),  bd("292950")),
            new Bracket(6,  bd("8000000"),      bd("15000000"),     4,  bd("0.1250"),  bd("492950")),
            new Bracket(7,  bd("15000000"),     bd("25000000"),     3,  bd("0.1667"),  bd("1118450")),
            new Bracket(8,  bd("25000000"),     bd("50000000"),     2,  bd("0.2500"),  bd("3200950")),
            new Bracket(9,  bd("50000000"),     bd("200000000"),    1,  bd("0.5000"),  bd("15700950"))
    );

    // SPCX（SpaceX pre-IPO 永续）：11 档，档位1 上限 50K、MMR 起点 0.65%、最大 75x（TradFi 组里最宽松）。
    private static final List<Bracket> SPCX_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("50000"),        75, bd("0.0065"),  bd("0")),
            new Bracket(2,  bd("50000"),        bd("250000"),       50, bd("0.0100"),  bd("175")),
            new Bracket(3,  bd("250000"),       bd("500000"),       25, bd("0.0200"),  bd("2675")),
            new Bracket(4,  bd("500000"),       bd("1500000"),      20, bd("0.0250"),  bd("5175")),
            new Bracket(5,  bd("1500000"),      bd("5000000"),      15, bd("0.0333"),  bd("17625")),
            new Bracket(6,  bd("5000000"),      bd("10000000"),     10, bd("0.0500"),  bd("101125")),
            new Bracket(7,  bd("10000000"),     bd("25000000"),     5,  bd("0.1000"),  bd("601125")),
            new Bracket(8,  bd("25000000"),     bd("50000000"),     4,  bd("0.1250"),  bd("1226125")),
            new Bracket(9,  bd("50000000"),     bd("100000000"),    3,  bd("0.1667"),  bd("3311125")),
            new Bracket(10, bd("100000000"),    bd("200000000"),    2,  bd("0.2500"),  bd("11641125")),
            new Bracket(11, bd("200000000"),    bd("400000000"),    1,  bd("0.5000"),  bd("61641125"))
    );

    // Map.of 上限 10 对，超出后用 ofEntries
    private static final Map<String, List<Bracket>> BRACKETS = Map.ofEntries(
            Map.entry("BTCUSDT",     BTC_BRACKETS),
            Map.entry("ETHUSDT",     ETH_BRACKETS),
            Map.entry("DOGEUSDT",    DOGE_BRACKETS),
            Map.entry("SOLUSDT",     SOL_BRACKETS),
            Map.entry("XRPUSDT",     XRP_BRACKETS),
            Map.entry("BNBUSDT",     BNB_BRACKETS),
            Map.entry("XAUUSDT",     COMMODITY_BRACKETS),
            Map.entry("CLUSDT",      COMMODITY_BRACKETS),
            Map.entry("SNDKUSDT",    US_STOCK_BRACKETS),
            Map.entry("SOXLUSDT",    US_STOCK_BRACKETS),
            Map.entry("SKHYNIXUSDT", US_STOCK_BRACKETS),
            Map.entry("MUUSDT",      US_STOCK_BRACKETS),
            Map.entry("KORUUSDT",    KORU_BRACKETS),
            Map.entry("SPCXUSDT",    SPCX_BRACKETS)
    );

    /**
     * 按仓位名义价值定档位。
     * 已知 symbol：返回命中档；notional 超出最高档时保守返回最高档（避免空指针，模拟盘
     * 不限制超大仓）。
     * 未知 symbol 或 notional 为 null：返回 null（仅供内部判存在性使用，调用方请优先
     * 使用 calcMaintenanceMargin / getEffectiveMaxLeverage 等带异常的 API）。
     */
    public Bracket findBracket(String symbol, BigDecimal notional) {
        List<Bracket> list = BRACKETS.get(symbol);
        if (list == null) return null;
        for (Bracket b : list) {
            if (notional.compareTo(b.notionalFloor) >= 0 && notional.compareTo(b.notionalCap) < 0) {
                return b;
            }
        }
        return list.getLast();
    }

    /** 已配置 symbol 的完整档位；未配置 symbol 返回 null。 */
    public List<Bracket> getBrackets(String symbol) {
        if (symbol == null) return null;
        return BRACKETS.get(symbol);
    }

    /** 全部 symbol 的档位表，供前端预估强平价/MMR 时直接复用，避免前后端两份硬编码。 */
    public Map<String, List<Bracket>> getAllBrackets() {
        return BRACKETS;
    }

    /** 实际生效最大杠杆：按 notional 取档；未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。 */
    public int getEffectiveMaxLeverage(String symbol, BigDecimal notional) {
        Bracket b = findBracket(symbol, notional);
        if (b == null) throw new BizException(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED);
        return b.maxLeverage();
    }

    /**
     * 维持保证金计算：MM = notional × MMR − maintAmount（按 notional 取档）。
     * 未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。
     */
    public BigDecimal calcMaintenanceMargin(String symbol, BigDecimal notional) {
        Bracket b = findBracket(symbol, notional);
        if (b == null) throw new BizException(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED);
        return notional.multiply(b.mmr()).subtract(b.maintAmount());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
