package com.mawai.wiibquant.agent.strategy.liq;

import java.util.Map;

/**
 * LiqFade 参数组（5m 决策口径）。窗口/冷却/持有是跨币共用的结构参数；
 * 三签名阈值每币独立——各币发现段(2021-12~2024-01) q0.5% 分位冻结校准（LiqFadeStudyRun 产出），
 * 部署的就是被验证的那套数字。
 *
 * <p>已消融否决、勿再尝试：SHORT-fade（接空拉升，五币两段皆负）、持有 2h/4h（验证段全面劣于 1h，
 * ETH 反弹 1h 后回吐）、ATR 灾难框（×8/×10，SOL 验证段 PF 劣化超线）——网格 harness 已按惯例清退，
 * 判决明细见提交信息。滚动分位自适应阈值属未验证改动，须走独立预注册消融，勿直接改。</p>
 *
 * <p>slPct/tpPct 是宽幅灾难保护、平时不咬合（事件后 4h 平均最大逆行 1.6%~8.9%，主出场=holdBars 时间出场）；
 * tp/sl = 10%/8% 比值 1.25，恰好通过 riskPolicy.minRR=1.2 的引擎闸。</p>
 */
public record LiqFadeParams(
        int flushBars,          // 瀑布窗口：3×5m=15m 滚动收益
        int cooldownBars,       // 事件冷却：12×5m=60m，同一场瀑布只打一枪
        int minFlags,           // 三签名至少几个命中（默认 2，与事件研究判决口径一致）
        int holdBars,           // 持有时长：12×5m=1h 时间出场（出场消融采纳：验证段5/5币PF>1、加权+56.8bp 优于4h的4/5币+33.8bp）
        double slPct,           // 灾难止损（0.08 = entry×92%）
        double tpPct,           // 灾难远止盈（0.10 = entry×110%，顺带收割尾部大反弹）
        long premStaleMaxMs,    // premium 采样时效上限（由 LiqSideData 实现方消费：超龄按缺数据 NaN 处理）
        Map<String, CoinGate> gates
) {

    /** 单币三签名阈值：15m 收益 ≤ flushLe、premium ≤ premLe（比例）、15m taker 卖占比 ≥ sellGe。 */
    public record CoinGate(double flushLe, double premLe, double sellGe) {
    }

    /** 默认值 = 5m 口径复验 run（2026-07）各币发现段校准值；hold=1h（出场消融唯一过线格）。 */
    public static LiqFadeParams defaults() {
        return new LiqFadeParams(3, 12, 2, 12, 0.08, 0.10, 10 * 60_000L, Map.of(
                "BTCUSDT", new CoinGate(-0.0113, -0.00106, 0.693),
                "ETHUSDT", new CoinGate(-0.0146, -0.00151, 0.689),
                "SOLUSDT", new CoinGate(-0.0212, -0.00516, 0.660),
                "DOGEUSDT", new CoinGate(-0.0192, -0.00157, 0.704),
                "XRPUSDT", new CoinGate(-0.0157, -0.00150, 0.675)));
    }
}
