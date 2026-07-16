package com.mawai.wiibquant.agent.strategy.liq;

import java.util.Map;

/**
 * LiqFade 参数组（5m 决策口径）。
 *
 * <p>结构参数（瀑布窗口/冷却/持有时长）所有币共用；三签名门槛每币一套，数字来自发现段
 * （2021-12~2024-01）的历史分位校准（LiqFadeStudyRun 产出），校准完就冻结——
 * 线上跑的就是回测验证过的那套原样数字。</p>
 *
 * <p><b>试过但被否决的改法，别再试</b>（实验代码按惯例已删，数据细节在提交历史）：
 * ① 反向做空接瀑布拉升：五个币训练段、验证段全亏；
 * ② 持有拉长到 2h/4h：全面不如 1h——ETH 这类币反弹 1 小时后就开始回吐；
 * ③ 灾难止损框改用 ATR（×8/×10）：SOL 验证段明显变差；
 * ④ 门槛改成随行情滚动自适应（2026-07）：本意是修复 SOL 信号太少，SOL 确实修好了
 *    （10笔→83笔且 PF 1.6），但 BTC/ETH/XRP 的门槛在平静行情里变得太松、放进大量平庸信号，
 *    平均每笔净收益稀释到 29~38bp，没过"不低于冻结版七成（39.3bp）"的预定线 → 保持冻结校准。</p>
 *
 * <p>slPct/tpPct 只是防极端行情的宽幅保护，平时碰不到（事件后 4h 最大逆行平均 1.6%~8.9%），
 * 正常出场靠 holdBars 时间出场；止盈10%/止损8% 比值 1.25，恰好过 riskPolicy.minRR=1.2 的引擎门槛。</p>
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
