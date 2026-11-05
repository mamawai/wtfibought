package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 开仓硬护栏：盯主人设的仓位规格（杠杆区间/保证金区间/单仓/双开）与加仓的豁免边界。 */
class TradeGuardTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();

    /** 本类只钉护栏判定，拒因文案固定按中文取（双语覆盖归 PromptI18nTest） */
    private static String validateOpen(TradeGuard.OpenReq req, BigDecimal equity, BigDecimal mark,
                                       Set<String> whitelist, TraderRiskConfig cfg,
                                       List<TradeGuard.PosSnap> snaps) {
        return TradeGuard.validateOpen(req, equity, mark, whitelist, cfg, snaps, PROMPTS, AgentLang.ZH);
    }

    private static final Set<String> WL = Set.of("BTCUSDT");
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final BigDecimal MARK = new BigDecimal("100000");

    /** 默认配置：杠杆 5~20，保证金上限 20%，多仓位开、双开关 */
    private TraderRiskConfig cfg() {
        return new TraderRiskConfig(5, 20, new BigDecimal("20"), true, false);
    }

    private TraderRiskConfig cfg(boolean multi, boolean hedge) {
        return new TraderRiskConfig(5, 20, new BigDecimal("20"), multi, hedge);
    }

    /** 基准单：0.1×100000/10 = 保证金1000 = 权益10%，落在 5~20% 区间内；止损止盈都带（两者都是硬规则） */
    private TradeGuard.OpenReq base() {
        return new TradeGuard.OpenReq("BTCUSDT", "LONG", "MARKET", new BigDecimal("0.1"), 10,
                null, new BigDecimal("95000"), new BigDecimal("110000"), "BREAKOUT", "突破前高", "1h收盘跌回98000下方");
    }

    /** 空单基准：止损在入场价上方、止盈在下方 */
    private TradeGuard.OpenReq baseShort() {
        return new TradeGuard.OpenReq("BTCUSDT", "SHORT", "MARKET", new BigDecimal("0.1"), 10,
                null, new BigDecimal("105000"), new BigDecimal("90000"), "BREAKOUT", "跌破前低", "1h收盘站回102000上方");
    }

    // ---------- 造数变体：生产 OpenReq 不带 wither（无生产调用），变体构造属于测试侧 ----------

    private static TradeGuard.OpenReq withOrderType(TradeGuard.OpenReq r, String v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), v, r.quantity(), r.leverage(),
                r.limitPrice(), r.stopLossPrice(), r.takeProfitPrice(), r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    private static TradeGuard.OpenReq withQuantity(TradeGuard.OpenReq r, BigDecimal v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), v, r.leverage(),
                r.limitPrice(), r.stopLossPrice(), r.takeProfitPrice(), r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    private static TradeGuard.OpenReq withLeverage(TradeGuard.OpenReq r, Integer v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), r.quantity(), v,
                r.limitPrice(), r.stopLossPrice(), r.takeProfitPrice(), r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    private static TradeGuard.OpenReq withLimitPrice(TradeGuard.OpenReq r, BigDecimal v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), r.quantity(), r.leverage(),
                v, r.stopLossPrice(), r.takeProfitPrice(), r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    private static TradeGuard.OpenReq withStopLossPrice(TradeGuard.OpenReq r, BigDecimal v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), r.quantity(), r.leverage(),
                r.limitPrice(), v, r.takeProfitPrice(), r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    private static TradeGuard.OpenReq withInvalidationCondition(TradeGuard.OpenReq r, String v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), r.quantity(), r.leverage(),
                r.limitPrice(), r.stopLossPrice(), r.takeProfitPrice(), r.playType(), r.signalsUsed(), v);
    }

    private static TradeGuard.OpenReq withTakeProfitPrice(TradeGuard.OpenReq r, BigDecimal v) {
        return new TradeGuard.OpenReq(r.symbol(), r.side(), r.orderType(), r.quantity(), r.leverage(),
                r.limitPrice(), r.stopLossPrice(), v, r.playType(), r.signalsUsed(), r.invalidationCondition());
    }

    @Test
    void validOpenPasses() {
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(), List.of())).isNull();
        assertThat(validateOpen(baseShort(), EQUITY, MARK, WL, cfg(), List.of())).isNull();
    }

    // ---------- 止盈：计划的目标位，与止损同为硬规则 ----------

    /** 没有目标位就没有盈亏比、也没有"到目标位落袋"可检验——缺了拒，拒因告诉它往远移不受限 */
    @Test
    void takeProfitRequired() {
        assertThat(validateOpen(withTakeProfitPrice(base(), null), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("止盈价").contains("目标位").contains("set_take_profit");
    }

    /** SHORT 的方向校验「0 <= 入场价」为真会放行止盈价 0：正数校验要挡在方向校验之前 */
    @Test
    void takeProfitZeroRejectedBeforeDirectionCheck() {
        assertThat(validateOpen(withTakeProfitPrice(baseShort(), BigDecimal.ZERO), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("正数").contains("你给了0");
    }

    @Test
    void takeProfitWrongSideRejected() {
        assertThat(validateOpen(withTakeProfitPrice(base(), new BigDecimal("99000")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("LONG止盈须高于入场价");
        assertThat(validateOpen(withTakeProfitPrice(baseShort(), new BigDecimal("101000")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("SHORT止盈须低于入场价");
    }

    // ---------- 杠杆区间：允许集合，不是上限 ----------

    /** 配 5~20 时选 3 也不行——低于下界同样拒，这是"必须从区间里选"的核心语义 */
    @Test
    void leverageBelowMinRejected() {
        String r = validateOpen(withLeverage(base(), 3), EQUITY, MARK, WL, cfg(), List.of());
        assertThat(r).contains("5~20").contains("你给了3");
    }

    @Test
    void leverageAboveMaxRejected() {
        assertThat(validateOpen(withLeverage(base(), 50), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("5~20");
    }

    // ---------- 保证金占比上限 ----------

    /**
     * 一晚 8 次贴边拒绝的实测教训：拒因里的提示数量必须照抄可过——
     * 向下取整，模型复制建议值重试不能再次被拒陷入循环。
     */
    @Test
    void marginHintQuantityIsCopyPasteSafe() {
        // 0.3×100000/10=3000=30%，超过 cfg 里改设的 15% 上限 → 拒并给上限处的数量
        TraderRiskConfig tight = new TraderRiskConfig(5, 20, new BigDecimal("15"), true, false);
        String reject = validateOpen(withQuantity(base(), new BigDecimal("0.3")),
                EQUITY, MARK, WL, tight, List.of());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("数量最多 (\\S+)").matcher(reject);
        assertThat(m.find()).isTrue();

        assertThat(validateOpen(withQuantity(base(), new BigDecimal(m.group(1))),
                EQUITY, MARK, WL, tight, List.of())).isNull();
    }

    /** 占比原样展示：15.005% 不许四舍五入成 15.01% */
    @Test
    void marginPctDisplayedWithoutMisleadingRounding() {
        TraderRiskConfig tight = new TraderRiskConfig(5, 20, new BigDecimal("15"), true, false);
        // 0.15005×100000/10=1500.5 → 15.005%，刚过上限一丝
        String r = validateOpen(withQuantity(base(), new BigDecimal("0.15005")),
                EQUITY, MARK, WL, tight, List.of());
        assertThat(r).contains("15.005%").doesNotContain("15.01%");
    }

    /** 没有下限：0.001×100000/10=10=权益0.1% 照样放行（交易所最小名义 50 USDT 另算） */
    @Test
    void noLowerBoundOnMargin() {
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.001")), EQUITY, MARK, WL, cfg(), List.of()))
                .isNull();
    }

    /** 0.3×100000/10=3000=30% > 上限20% → 拒 */
    @Test
    void marginAboveMaxRejected() {
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.3")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("占权益30%").contains("上限20%");
    }

    // ---------- 交易所过滤器 ----------

    /** 0.0625 不是 BTC 步长 0.001 的整数倍：护栏先拒并把步长说清，不等 sim 用通用文案拒 */
    @Test
    void quantityOffStepRejectedWithStep() {
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.0625")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("0.001").contains("0.0625");
    }

    /** 名义价值低于交易所最小 50 USDT：0.001×40000=40 */
    @Test
    void notionalBelowExchangeMinimumRejected() {
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.001")), new BigDecimal("100"),
                new BigDecimal("40000"), WL, cfg(), List.of()))
                .contains("40").contains("50 USDT");
    }

    /** 建议数量还得对齐步长：权益不整时会算出 0.123457 这种数，照抄会被 sim 按步长拒 */
    @Test
    void marginHintQuantityIsStepAligned() {
        TraderRiskConfig tight = new TraderRiskConfig(5, 20, new BigDecimal("15"), true, false);
        BigDecimal equity = new BigDecimal("12345.67");
        String reject = validateOpen(withQuantity(base(), new BigDecimal("0.3")), equity, MARK, WL, tight, List.of());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("数量最多 (\\S+)").matcher(reject);
        assertThat(m.find()).isTrue();

        BigDecimal q = new BigDecimal(m.group(1));
        assertThat(q.remainder(new BigDecimal("0.001"))).isZero();
        assertThat(validateOpen(withQuantity(base(), q), equity, MARK, WL, tight, List.of())).isNull();
    }

    /** 边界含端点：0.2×100000/10=2000=正好20% → 放行 */
    @Test
    void marginExactlyAtMaxPasses() {
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.2")), EQUITY, MARK, WL, cfg(), List.of()))
                .isNull();
    }

    /** 限价单按限价算保证金：0.205×96000/10=1968=19.68% 放行；同样数量按现价 100000 算是 20.5%，拒 */
    @Test
    void limitOrderMarginUsesLimitPrice() {
        TradeGuard.OpenReq limit = withQuantity(withLimitPrice(withOrderType(base(), "LIMIT"), new BigDecimal("96000")),
                new BigDecimal("0.205"));
        assertThat(validateOpen(limit, EQUITY, MARK, WL, cfg(), List.of())).isNull();
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.205")), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("占权益20.5%");
    }

    // ---------- 限价单必须挂在现价另一侧 ----------

    /** 做多限价等于现价＝挂出去当场成交：拒，并说清该用 MARKET 还是挂到现价另一侧 */
    @Test
    void marketableLongLimitRejected() {
        TradeGuard.OpenReq atMark = withLimitPrice(withOrderType(base(), "LIMIT"), MARK);
        assertThat(validateOpen(atMark, EQUITY, MARK, WL, cfg(), List.of()))
                .contains("当场按现价成交").contains("MARKET").contains("做多限价要低于现价");
    }

    /** 做空限价：挂在现价下方拒，挂在上方放行 */
    @Test
    void shortLimitMustRestAboveMark() {
        TradeGuard.OpenReq below = withLimitPrice(withOrderType(baseShort(), "LIMIT"), new BigDecimal("99000"));
        assertThat(validateOpen(below, EQUITY, MARK, WL, cfg(), List.of()))
                .contains("当场按现价成交").contains("做空限价要高于现价");
        TradeGuard.OpenReq above = withLimitPrice(withOrderType(baseShort(), "LIMIT"), new BigDecimal("101000"));
        assertThat(validateOpen(above, EQUITY, MARK, WL, cfg(), List.of())).isNull();
    }

    // ---------- 仓位数与双开 ----------

    /** 单仓模式下换标的：拒，并把占坑的仓位报给模型 */
    @Test
    void singlePositionModeRejectsAnotherSymbol() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("ETHUSDT", "LONG", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held))
                .contains("一个仓位").contains("ETHUSDT LONG");
    }

    /** 单仓模式下对同一仓加仓：sim 会并仓不占新坑，放行 */
    @Test
    void singlePositionModeAllowsAddOn() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held)).isNull();
    }

    /** 挂单一并计数：只看持仓的话，先挂几单就能绕过单仓限制 */
    @Test
    void pendingOrderCountsAsOccupied() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("ETHUSDT", "SHORT", 10, false));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(false, false), held)).contains("一个仓位");
    }

    @Test
    void hedgeRejectedWhenDisabled() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "SHORT", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(true, false), held))
                .contains("反向仓位").contains("多空双开");
    }

    @Test
    void hedgeAllowedWhenEnabled() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "SHORT", 10, true));
        assertThat(validateOpen(base(), EQUITY, MARK, WL, cfg(true, true), held)).isNull();
    }

    // ---------- 加仓的两条特殊规则 ----------

    /** 同币杠杆必须一致（sim 侧规则），护栏提前拦并说清该用几倍 */
    @Test
    void addOnMustMatchSymbolLeverage() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(withLeverage(base(), 15), EQUITY, MARK, WL, cfg(), held))
                .contains("杠杆必须一致").contains("10倍");
    }

    /** 加仓量由模型自己斟酌：0.5 → 保证金5000=50%，远超20%上限也放行 */
    @Test
    void addOnSkipsMarginCap() {
        List<TradeGuard.PosSnap> held = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, true));
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.5")), EQUITY, MARK, WL, cfg(), held))
                .isNull();
    }

    /**
     * 同向挂单不算加仓：sim 只与已成交持仓并仓，把挂单当加仓等于送出一条绕过保证金上限的路——
     * 先挂一个远离现价永不成交的限价单，之后开多大都不受约束。
     */
    @Test
    void sameSidePendingOrderIsNotAnAddOn() {
        List<TradeGuard.PosSnap> pending = List.of(new TradeGuard.PosSnap("BTCUSDT", "LONG", 10, false));
        assertThat(validateOpen(withQuantity(base(), new BigDecimal("0.5")), EQUITY, MARK, WL, cfg(), pending))
                .contains("占权益50%");
    }

    // ---------- 与配置无关的既有护栏 ----------

    @Test
    void blankInvalidationConditionRejected() {
        assertThat(validateOpen(withInvalidationCondition(base(), " "), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("失效条件");
        assertThat(validateOpen(withInvalidationCondition(base(), null), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("失效条件");
    }

    @Test
    void stopLossOnWrongSideRejected() {
        assertThat(validateOpen(withStopLossPrice(base(), new BigDecimal("105000")),
                EQUITY, MARK, WL, cfg(), List.of())).contains("止损价方向错误");
    }

    // ---------- 止损价本身的合法性 ----------

    /**
     * 模型漏传 stopLossPrice 时工具层曾把它绑成 0（primitive 默认值），LONG 的方向校验
     * 「0 >= 入场价」为假直接放行——开出止损价为 0（永不触发）的裸多单。零/负数必须先一票拒。
     */
    @Test
    void nonPositiveStopLossRejectedForLong() {
        String zero = validateOpen(withStopLossPrice(base(), BigDecimal.ZERO),
                EQUITY, MARK, WL, cfg(), List.of());
        // 拒因要能让模型自行修正：说清必须为正数，并给出合法区间
        assertThat(zero).contains("止损价必须为正数").contains("100000");
        assertThat(validateOpen(withStopLossPrice(base(), new BigDecimal("-1")),
                EQUITY, MARK, WL, cfg(), List.of())).contains("止损价必须为正数");
    }

    /** SHORT 侥幸被方向校验挡下，但拒因说"方向错误"会误导模型去调价，实际是参数漏传 */
    @Test
    void nonPositiveStopLossRejectedForShort() {
        assertThat(validateOpen(withStopLossPrice(baseShort(), BigDecimal.ZERO),
                EQUITY, MARK, WL, cfg(), List.of()))
                .contains("止损价必须为正数").contains("100000");
    }

    /** 缺失止损（工具层 Double 为 null）：修好 primitive 后这条路径才真正可达 */
    @Test
    void missingStopLossRejected() {
        assertThat(validateOpen(withStopLossPrice(base(), null), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("必须设置止损价");
        assertThat(validateOpen(withStopLossPrice(baseShort(), null), EQUITY, MARK, WL, cfg(), List.of()))
                .contains("必须设置止损价");
    }
}
