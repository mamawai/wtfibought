package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 开仓硬校验：AI 会幻觉出荒谬参数，入口一票否决。拒绝原因用中文原样返回给模型——
 * 模型看得懂就能自行修正重试。
 * 杠杆/保证金/仓位数/双开来自主人的 {@link TraderRiskConfig}，越界一律拒不截断：
 * 这是模拟盘，仓位规格是主人说了算的硬参数，模型无权评价也无权自行缩小。
 */
public final class TradeGuard {

    /** 论点标签枚举：写入时结构化，未来挖掘"哪些打法反复赚钱"直接 SQL 分桶 */
    public static final Set<String> PLAY_TYPES = Set.of(
            "BREAKOUT", "PULLBACK", "REVERSAL", "TREND_FOLLOW", "RANGE", "NEWS", "FUNDING", "OTHER");

    /** 限价偏离现价上限：防模型幻觉出离谱价格，与用户配置无关，不开放 */
    public static final BigDecimal MAX_LIMIT_DEVIATION = new BigDecimal("0.05");

    /**
     * 账户占位快照：已成交持仓 + 未成交挂单统一成这个形状。
     * 挂单必须一起计数——只看持仓的话，模型挂三个不同币的限价单，成交后就绕过了单仓限制。
     *
     * @param filled true=已成交持仓，false=未成交挂单。这个区分不能省：
     *               sim 只与已成交持仓并仓，把挂单也当成"已有同向仓"会让加仓判定为真、
     *               从而跳过保证金区间校验——先挂一个不会成交的限价单，之后开仓就不受约束了
     */
    public record PosSnap(String symbol, String side, Integer leverage, boolean filled) {
    }

    /** 开仓请求（工具参数的结构化镜像）。变体构造归测试侧（TradeGuardTest 的 with* 助手）。 */
    public record OpenReq(String symbol, String side, String orderType, BigDecimal quantity, Integer leverage,
                          BigDecimal limitPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                          String playType, String signalsUsed, String invalidationCondition) {
    }

    private TradeGuard() {
    }

    /**
     * 多单取最高价/空单取最低价。作判定基准时含义随场景不同：对止损是"最紧那一档"、对止盈是
     * "最远那一档"——sim 整组替换语义下按最保守档做基线，替换后才不会比原来任何一档更松/更近。
     * 批准加仓给新增部分补挂保护单时也照抄这一档（本版 sl/tp 都是全仓单，正常只有一档）。
     */
    public static BigDecimal extremePrice(List<BigDecimal> prices, boolean isLong) {
        return prices.stream().filter(java.util.Objects::nonNull)
                .reduce((a, b) -> isLong ? a.max(b) : a.min(b)).orElse(null);
    }

    /**
     * 返回 null=放行；否则给出拒因（回给模型，同时公开在竞技场时间线上，故跟 trader 主人的语言）。
     *
     * @param cfg      主人设的仓位规格（区间是允许集合，越界拒不截断）
     * @param existing 当前账户占位：已成交持仓 + 未成交挂单
     */
    public static String validateOpen(OpenReq req, BigDecimal equity, BigDecimal markPrice,
                                      Set<String> symbolWhitelist, TraderRiskConfig cfg,
                                      List<PosSnap> existing, PromptCatalog prompts, AgentLang lang) {
        if (req.symbol() == null || !symbolWhitelist.contains(req.symbol())) {
            return prompts.get(lang, "trader.guard.symbolNotWhitelisted", Map.of("whitelist", symbolWhitelist));
        }
        boolean isLong = "LONG".equals(req.side());
        if (!isLong && !"SHORT".equals(req.side())) {
            return prompts.get(lang, "trader.guard.sideInvalid");
        }
        boolean isLimit = "LIMIT".equals(req.orderType());
        if (!isLimit && !"MARKET".equals(req.orderType())) {
            return prompts.get(lang, "trader.guard.orderTypeInvalid");
        }
        if (req.quantity() == null || req.quantity().signum() <= 0) {
            return prompts.get(lang, "trader.guard.quantityPositive");
        }
        if (req.leverage() == null || req.leverage() < cfg.leverageMin() || req.leverage() > cfg.leverageMax()) {
            return prompts.get(lang, "trader.guard.leverageRange", Map.of(
                    "min", cfg.leverageMin(), "max", cfg.leverageMax(), "given", String.valueOf(req.leverage())));
        }
        if (req.playType() == null || !PLAY_TYPES.contains(req.playType())) {
            return prompts.get(lang, "trader.guard.playTypeInvalid", Map.of("types", PLAY_TYPES));
        }
        if (req.invalidationCondition() == null || req.invalidationCondition().isBlank()) {
            return prompts.get(lang, "trader.guard.invalidationRequired");
        }
        if (isLimit && req.limitPrice() == null) {
            return prompts.get(lang, "trader.guard.limitPriceRequired");
        }
        if (isLimit) {
            BigDecimal deviation = req.limitPrice().subtract(markPrice).abs()
                    .divide(markPrice, 8, RoundingMode.HALF_UP);
            if (deviation.compareTo(MAX_LIMIT_DEVIATION) > 0) {
                return prompts.get(lang, "trader.guard.limitTooFar", Map.of(
                        "pct", deviation.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        "mark", markPrice.stripTrailingZeros().toPlainString()));
            }
        }
        List<PosSnap> snaps = existing == null ? List.of() : existing;
        // 只有已成交持仓才算加仓：sim 不会与挂单并仓，把挂单算进来等于送出一条绕过保证金区间的路
        boolean isAddOn = snaps.stream().filter(PosSnap::filled)
                .anyMatch(p -> p.symbol().equals(req.symbol()) && p.side().equals(req.side()));

        // 币种级杠杆一致性：sim 侧 validateSymbolConsistency 会拒不一致的加仓，护栏提前拦并说清楚，
        // 免得模型对着 sim 的错误码猜半天
        Integer symLev = snaps.stream()
                .filter(p -> p.symbol().equals(req.symbol()))
                .map(PosSnap::leverage).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (symLev != null && !symLev.equals(req.leverage())) {
            return prompts.get(lang, "trader.guard.symbolLeverageMismatch",
                    Map.of("symbol", req.symbol(), "lev", symLev));
        }
        // 单仓模式：加仓不占新坑（sim 同向自动并仓），其余一律拒
        if (!cfg.allowMultiPosition() && !isAddOn && !snaps.isEmpty()) {
            return prompts.get(lang, "trader.guard.singlePositionOnly", Map.of("occupied", describe(snaps)));
        }
        // 双开：同币反向占的是另一个坑，只可能在多仓位模式下发生
        if (!cfg.allowHedge() && snaps.stream()
                .anyMatch(p -> p.symbol().equals(req.symbol()) && !p.side().equals(req.side()))) {
            return prompts.get(lang, "trader.guard.hedgeNotAllowed", Map.of("symbol", req.symbol()));
        }

        // 入场参考价：限价单按限价、市价单按现价
        BigDecimal entryRef = isLimit ? req.limitPrice() : markPrice;
        // 保证金区间只管开新仓：加仓多大由模型自己斟酌（主人的原话）
        if (!isAddOn) {
            BigDecimal margin = req.quantity().multiply(entryRef)
                    .divide(BigDecimal.valueOf(req.leverage()), 8, RoundingMode.HALF_UP);
            BigDecimal pct = margin.multiply(new BigDecimal("100"))
                    .divide(equity, 4, RoundingMode.HALF_UP);
            if (pct.compareTo(cfg.marginPctMin()) < 0 || pct.compareTo(cfg.marginPctMax()) > 0) {
                // 占比原样展示不四舍五入：9.995% 若显示成"10.00%超出10~15%"是自相矛盾，模型会懵
                return prompts.get(lang, "trader.guard.marginOutOfRange", Map.of(
                        "margin", margin.setScale(0, RoundingMode.HALF_UP),
                        "pct", pct.stripTrailingZeros().toPlainString(),
                        "min", cfg.marginPctMin().stripTrailingZeros().toPlainString(),
                        "max", cfg.marginPctMax().stripTrailingZeros().toPlainString(),
                        "lev", req.leverage(),
                        "qtyLo", qtyFor(cfg.marginPctMin(), equity, req.leverage(), entryRef, RoundingMode.CEILING),
                        "qtyHi", qtyFor(cfg.marginPctMax(), equity, req.leverage(), entryRef, RoundingMode.FLOOR)));
            }
        }
        if (req.stopLossPrice() == null) {
            return prompts.get(lang, "trader.guard.stopLossRequired");
        }
        // 零/负必须挡在方向校验之前：LONG 的「0 >= 入场价」为假会直接放行，
        // 开出止损价为0（永不触发）的裸单——真实成因多半是重试时漏传了这个参数
        if (req.stopLossPrice().signum() <= 0) {
            return prompts.get(lang, "trader.guard.stopLossPositive", Map.of(
                    "given", req.stopLossPrice().stripTrailingZeros().toPlainString(),
                    "entry", entryRef.stripTrailingZeros().toPlainString(),
                    "hint", isLong
                            ? prompts.get(lang, "trader.guard.stopLossHintLong",
                                    Map.of("example", pctOf(entryRef, "0.98")))
                            : prompts.get(lang, "trader.guard.stopLossHintShort",
                                    Map.of("example", pctOf(entryRef, "1.02")))));
        }
        if (isLong ? req.stopLossPrice().compareTo(entryRef) >= 0
                : req.stopLossPrice().compareTo(entryRef) <= 0) {
            return prompts.get(lang, "trader.guard.stopLossDirection", Map.of(
                    "rule", prompts.get(lang, isLong ? "trader.guard.stopLossRuleLong" : "trader.guard.stopLossRuleShort"),
                    "entry", entryRef.stripTrailingZeros().toPlainString()));
        }
        // 止盈必填：它就是计划的目标位，盈亏比与"到目标位落袋"都按它算；想让利润奔跑开仓后往远移即可
        if (req.takeProfitPrice() == null) {
            return prompts.get(lang, "trader.guard.takeProfitRequired");
        }
        // 同止损：SHORT 的「0 <= 入场价」为真会放行一张止盈价为 0 的单
        if (req.takeProfitPrice().signum() <= 0) {
            return prompts.get(lang, "trader.guard.takeProfitPositive", Map.of(
                    "given", req.takeProfitPrice().stripTrailingZeros().toPlainString()));
        }
        if (isLong ? req.takeProfitPrice().compareTo(entryRef) <= 0
                : req.takeProfitPrice().compareTo(entryRef) >= 0) {
            return prompts.get(lang, "trader.guard.takeProfitDirection", Map.of("rule",
                    prompts.get(lang, isLong ? "trader.guard.takeProfitRuleLong" : "trader.guard.takeProfitRuleShort")));
        }
        return null;
    }

    /** 拒因里给个可直接照抄的止损建议价（入场价的某个比例）。 */
    private static String pctOf(BigDecimal entryRef, String factor) {
        return entryRef.multiply(new BigDecimal(factor))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 拒因里把该配多少数量直接算给模型，省一轮试错。
     * 下界向上取整、上界向下取整：模型会照抄提示数字重试（一晚 8 次贴边拒绝的实测教训——
     * HALF_UP 算出的下界被模型截位后又低于下界，陷入拒绝循环）。
     */
    private static String qtyFor(BigDecimal marginPct, BigDecimal equity, int leverage,
                                 BigDecimal entryRef, RoundingMode mode) {
        return equity.multiply(marginPct).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(leverage))
                .divide(entryRef, 6, mode)
                .stripTrailingZeros().toPlainString();
    }

    private static String describe(List<PosSnap> snaps) {
        return snaps.stream().map(p -> p.symbol() + " " + p.side())
                .collect(java.util.stream.Collectors.joining("、"));
    }
}
