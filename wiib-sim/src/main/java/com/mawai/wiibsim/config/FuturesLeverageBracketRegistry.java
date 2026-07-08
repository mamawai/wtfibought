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
 * 已配置：BTCUSDT、ETHUSDT、PAXGUSDT、DOGEUSDT、SOLUSDT、XRPUSDT。
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

    // PAXG: 黄金稳定币流动性远低于 BTC/ETH，11 档，档位 1 仅 5K USDT、MMR 起点 1.00%。
    private static final List<Bracket> PAXG_BRACKETS = List.of(
            new Bracket(1,  bd("0"),            bd("5000"),         75, bd("0.0100"),  bd("0")),
            new Bracket(2,  bd("5000"),         bd("10000"),        50, bd("0.0150"),  bd("25")),
            new Bracket(3,  bd("10000"),        bd("50000"),        25, bd("0.0200"),  bd("75")),
            new Bracket(4,  bd("50000"),        bd("100000"),       20, bd("0.0250"),  bd("325")),
            new Bracket(5,  bd("100000"),       bd("175000"),       15, bd("0.0333"),  bd("1158")),
            new Bracket(6,  bd("175000"),       bd("500000"),       10, bd("0.0500"),  bd("4075.25")),
            new Bracket(7,  bd("500000"),       bd("750000"),       5,  bd("0.1000"),  bd("29075.25")),
            new Bracket(8,  bd("750000"),       bd("1500000"),      4,  bd("0.1250"),  bd("47825.25")),
            new Bracket(9,  bd("1500000"),      bd("4500000"),      3,  bd("0.1667"),  bd("110375.25")),
            new Bracket(10, bd("4500000"),      bd("7500000"),      2,  bd("0.2500"),  bd("485225.25")),
            new Bracket(11, bd("7500000"),      bd("12500000"),     1,  bd("0.5000"),  bd("2360225.25"))
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

    private static final Map<String, List<Bracket>> BRACKETS = Map.of(
            "BTCUSDT",  BTC_BRACKETS,
            "ETHUSDT",  ETH_BRACKETS,
            "PAXGUSDT", PAXG_BRACKETS,
            "DOGEUSDT", DOGE_BRACKETS,
            "SOLUSDT",  SOL_BRACKETS,
            "XRPUSDT",  XRP_BRACKETS
    );

    /**
     * 按仓位名义价值定档位。
     * 已知 symbol：返回命中档；notional 超出最高档时保守返回最高档（避免空指针，模拟盘
     * 不限制超大仓）。
     * 未知 symbol 或 notional 为 null：返回 null（仅供内部判存在性使用，调用方请优先
     * 使用 calcMaintenanceMargin / getEffectiveMaxLeverage 等带异常的 API）。
     */
    public Bracket findBracket(String symbol, BigDecimal notional) {
        if (symbol == null || notional == null) return null;
        List<Bracket> list = BRACKETS.get(symbol);
        if (list == null) return null;
        for (Bracket b : list) {
            if (notional.compareTo(b.notionalFloor) >= 0 && notional.compareTo(b.notionalCap) < 0) {
                return b;
            }
        }
        return list.get(list.size() - 1);
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
