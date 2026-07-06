package com.mawai.wiibquant.agent.strategy.sqzmom;

/**
 * SQZMOM 空头策略参数组（多币消融定稿形态，defaults 即部署配置）。
 *
 * <p>没有通道乘数参数——原版 LazyBear 脚本的 BB 乘数输入(2.0)没接线（著名 bug，BB/KC 共用
 * multKC=1.5），而 BB/KC 长度默认又相等，乘数在 squeeze 判定里左右同乘直接约掉：
 * sqzOn ⟺ stdev(close,L) &lt; SMA(TR,L)。保留乘数就是保留一个死参数，故删。</p>
 */
public record SqzMomParams(
        long decisionTfMillis,   // 决策周期（5m 基础bar内部聚合；4h 定稿——≤2H 消融证伪，勿降）
        int length,              // BB/KC/动量 共用窗口长度（原版默认同为 20，冻结成一个）
        int squeezeMinBars,      // sqzOn 最少连续根数：压缩够久，释放才有能量
        double slAtrMult,        // 止损 = entry + slAtrMult×ATR，ATR=SMA(TR,length) 与 KC 同源
        double tpRMultiple       // 止盈 = entry − tpRMultiple×risk（远尾追踪/保本止损均消融否决，固定 2R 即最优）
) {

    public static SqzMomParams defaults() {
        return new SqzMomParams(4 * 3_600_000L, 20, 6, 1.5, 2.0);
    }
}
