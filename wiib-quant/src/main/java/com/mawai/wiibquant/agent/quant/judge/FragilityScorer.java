package com.mawai.wiibquant.agent.quant.judge;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MarketRegime;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragileDirection;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityLevel;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 市场脆弱度合成器（确定性，无 LLM）。
 *
 * <p>三腿<b>等权</b>合成 0-100 脆弱度：持仓拥挤 + 去杠杆剧烈 + vol-state。全部用 snapshot
 * 现成的归一化字段，零新数据。阈值常量待 Step 6 用 research 框架离线校准。</p>
 *
 * <p>脆弱方向由 POSITIONING 组净倾向决定（信号面板已消化"拥挤反向"：多头拥挤→偏空 lean
 * →下行脆弱）。这是"更易往哪破"的结构推论，<b>非预测方向</b>。</p>
 *
 * <p>去杠杆腿当前是 heatmap 思路的"标量子集"：用 liquidationPressure/Volume + oiChangeRate
 * 合成，未做 60h OI 滚动均值 + 价格分箱（OI 历史窗口不足，留 Step 6 升级）。</p>
 */
public class FragilityScorer {

    // === 归一化基准（待 Step 6 离线校准）===
    private static final double OI_SHOCK_REF = 0.05;          // OI 变化率达 5% 视为满格
    private static final double LIQ_VOL_REF = 5_000_000;      // 近期爆仓额达 500 万 USDT 视为满格
    private static final double ATR_REF = 0.012;             // ATR/价 达 1.2% 视为满格
    private static final double IV_REF = 80;                 // 隐含波动达 80 视为满格
    private static final double DIRECTION_MIN_CROWDING = 0.20; // 拥挤度低于此不给脆弱方向
    private static final double HEADLINE_PART_MIN = 0.40;     // 某腿超此值才进头条

    public FragilityScore score(FeatureSnapshot s, SignalPanel panel) {
        if (s == null) {
            return FragilityScore.empty();
        }
        double crowding = crowding(s);
        double deleveraging = deleveraging(s);
        double volState = volState(s);

        int score = (int) Math.round((crowding + deleveraging + volState) / 3.0 * 100);
        FragilityLevel level = FragilityLevel.of(score);
        FragileDirection direction = fragileDirection(crowding, panel);
        String headline = buildHeadline(crowding, deleveraging, volState, direction, level, score);

        return new FragilityScore(score, level, crowding, deleveraging, volState, direction, headline);
    }

    /** 拥挤度：funding / 多空比 / 大户 / taker / 恐贪极端度 等权平均，越一边倒越脆。 */
    private double crowding(FeatureSnapshot s) {
        // 恐贪无数据时 calcFearGreed 返回 -1（哨兵），当中性处理（extreme=0），不能误当满格拥挤
        double fearGreedExtreme = s.fearGreedIndex() < 0 ? 0 : Math.abs(s.fearGreedIndex() - 50) / 50.0;
        return mean(
                clamp01(Math.abs(s.fundingDeviation())),
                clamp01(Math.abs(s.lsrExtreme())),
                clamp01(Math.abs(s.topTraderBias())),
                clamp01(Math.abs(s.takerBuySellPressure())),
                clamp01(fearGreedExtreme));
    }

    /** 去杠杆剧烈度：强平方向失衡 + OI 突变 + 爆仓规模，等权平均。 */
    private double deleveraging(FeatureSnapshot s) {
        double liqIntensity = clamp01(Math.abs(s.liquidationPressure()));
        double oiShock = clamp01(Math.abs(s.oiChangeRate()) / OI_SHOCK_REF);
        double volScale = clamp01(s.liquidationVolumeUsdt() / LIQ_VOL_REF);
        return mean(liqIntensity, oiShock, volScale);
    }

    /** vol-state：扩张 / 隐波 / 状态 / 挤压取 max——任一维度极端即脆。 */
    private double volState(FeatureSnapshot s) {
        double atrRatio = 0;
        BigDecimal atr = s.atr() != null ? s.atr() : s.atr1m();
        if (atr != null && s.lastPrice() != null && s.lastPrice().signum() > 0) {
            atrRatio = clamp01(atr.doubleValue() / s.lastPrice().doubleValue() / ATR_REF);
        }
        double ivLevel = clamp01(Math.max(s.dvolIndex(), s.atmIv()) / IV_REF);
        double regimeRisk = switch (s.regime() == null ? MarketRegime.RANGE : s.regime()) {
            case SHOCK -> 0.9;
            case SQUEEZE -> 0.6;
            default -> 0.0;
        };
        double squeezeRisk = s.bollSqueeze() ? 0.5 : 0.0;
        return max(atrRatio, ivLevel, regimeRisk, squeezeRisk);
    }

    /** 脆弱方向：拥挤够高时取 POSITIONING 净倾向符号（信号面板已反向解读）。 */
    private FragileDirection fragileDirection(double crowding, SignalPanel panel) {
        if (crowding < DIRECTION_MIN_CROWDING || panel == null) {
            return FragileDirection.NEUTRAL;
        }
        int net = panel.netLean(SignalGroup.POSITIONING);
        if (net < 0) {
            return FragileDirection.DOWN;   // POSITIONING 偏空 = 多头拥挤 → 下行脆弱
        }
        if (net > 0) {
            return FragileDirection.UP;     // POSITIONING 偏多 = 空头拥挤 → 上行脆弱
        }
        return FragileDirection.NEUTRAL;
    }

    private String buildHeadline(double crowding, double deleveraging, double volState,
                                 FragileDirection direction, FragilityLevel level, int score) {
        List<String> parts = new ArrayList<>();
        if (crowding >= HEADLINE_PART_MIN) {
            parts.add(switch (direction) {
                case UP -> "空头拥挤";
                case DOWN -> "多头拥挤";
                case NEUTRAL -> "持仓拥挤";
            });
        }
        if (deleveraging >= HEADLINE_PART_MIN) {
            parts.add("去杠杆抬升");
        }
        if (volState >= HEADLINE_PART_MIN) {
            parts.add("波动放大");
        }
        String front = parts.isEmpty() ? "市场结构平稳" : String.join(" + ", parts);
        return "%s → %s（脆弱度 %s %d）".formatted(front, direction.cn(), level.cn(), score);
    }

    private static double clamp01(double v) {
        return Math.clamp(Double.isFinite(v) ? v : 0, 0, 1);
    }

    private static double mean(double... xs) {
        if (xs.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.length;
    }

    private static double max(double... xs) {
        double m = 0;
        for (double x : xs) {
            m = Math.max(m, x);
        }
        return m;
    }
}
