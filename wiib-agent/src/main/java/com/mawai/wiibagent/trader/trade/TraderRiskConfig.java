package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.entity.AiTrader;

import java.math.BigDecimal;

/**
 * trader 的仓位规格配置：主人设的硬参数，模型只能遵守不能评价。
 * 区间是"允许集合"而非上限——配 50~100 时模型选 20 也不行，必须回到 50 以上。
 * 越界一律拒绝不截断：平台悄悄改值，模型不知道自己被改了，后面的止损计算全错。
 */
public record TraderRiskConfig(int leverageMin, int leverageMax,
                               BigDecimal marginPctMin, BigDecimal marginPctMax,
                               boolean allowMultiPosition, boolean allowHedge) {

    /** 配置校验用的绝对边界；实际可用杠杆还受 sim 按名义价值分档限制（超档由 sim 拒并回传拒因） */
    public static final int LEVERAGE_HARD_MAX = 125;
    public static final BigDecimal MARGIN_PCT_HARD_MIN = new BigDecimal("0.1");
    public static final BigDecimal MARGIN_PCT_HARD_MAX = new BigDecimal("100");

    /** 主人没填时的默认规格；建 trader 与改配置两处共用同一份 */
    public static final int DEF_LEV_MIN = 3;
    public static final int DEF_LEV_MAX = 20;
    public static final BigDecimal DEF_MARGIN_MIN = new BigDecimal("5");
    public static final BigDecimal DEF_MARGIN_MAX = new BigDecimal("20");

    /** 从实体读，null 走默认值——老数据与手工插库都不至于炸。 */
    public static TraderRiskConfig of(AiTrader t) {
        return new TraderRiskConfig(
                t.getLeverageMin() == null ? DEF_LEV_MIN : t.getLeverageMin(),
                t.getLeverageMax() == null ? DEF_LEV_MAX : t.getLeverageMax(),
                t.getMarginPctMin() == null ? DEF_MARGIN_MIN : t.getMarginPctMin(),
                t.getMarginPctMax() == null ? DEF_MARGIN_MAX : t.getMarginPctMax(),
                !Boolean.FALSE.equals(t.getAllowMultiPosition()),
                Boolean.TRUE.equals(t.getAllowHedge()));
    }
}
