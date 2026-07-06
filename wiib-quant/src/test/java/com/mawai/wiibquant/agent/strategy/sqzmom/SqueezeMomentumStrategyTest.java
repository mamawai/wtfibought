package com.mawai.wiibquant.agent.strategy.sqzmom;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqueezeMomentumStrategyTest {

    private static final long TF = 300_000L;   // 测试用决策周期=5m，决策bar即基础bar，免聚合噪音
    private static final String SYMBOL = "SOLUSDT";
    private static final SqzMomParams PARAMS = new SqzMomParams(TF, 20, 6, 1.5, 2.0);

    /** 纯线性序列上 LSMA 端点=序列末值（回归完美拟合），验证 linreg(src,len,0) 口径。 */
    @Test
    void lsmaEndpointFitsPerfectLine() {
        double[] y = new double[30];
        for (int i = 0; i < y.length; i++) y[i] = 3.0 + 2.0 * i;
        assertThat(SqueezeMomentumStrategy.lsmaEndpoint(y, 29, 20)).isCloseTo(y[29], within(1e-9));
        assertThat(SqueezeMomentumStrategy.lsmaEndpoint(y, 25, 20)).isCloseTo(y[25], within(1e-9));
    }

    /**
     * 端到端：宽幅震荡(基线波动) → 窄幅压缩(sqzOn蓄能) → 放量向下崩(sqzOff释放+亮红柱)。
     * 断言：压缩阶段不触发；崩盘阶段发出 SHORT MARKET 信号且 SL/TP 几何正确(RR=tpRMultiple)。
     */
    @Test
    void emitsShortSignalOnSqueezeBreakdown() {
        SqueezeMomentumStrategy strategy = new SqueezeMomentumStrategy(PARAMS, List.of(SYMBOL));
        WindowedMarketView view = new WindowedMarketView(10_000);

        List<KlineBar> bars = syntheticMove(false);
        int firstSignalIdx = -1;
        StrategySignal signal = null;
        for (int i = 0; i < bars.size(); i++) {
            view.append(bars.get(i));
            Optional<StrategySignal> s = strategy.onBarClosed(SYMBOL, view);
            if (s.isPresent() && firstSignalIdx < 0) {
                firstSignalIdx = i;
                signal = s.get();
            }
        }

        assertThat(signal).as("崩盘阶段应触发信号").isNotNull();
        assertThat(firstSignalIdx).as("压缩阶段(<48)不许触发").isGreaterThanOrEqualTo(48);
        assertThat(signal.isLong()).isFalse();
        assertThat(signal.orderType()).isEqualTo("MARKET");
        assertThat(signal.stopLossPrice()).isGreaterThan(signal.entryRefPrice());
        assertThat(signal.takeProfitPrice()).isLessThan(signal.entryRefPrice());
        double risk = signal.stopLossPrice().subtract(signal.entryRefPrice()).doubleValue();
        double reward = signal.entryRefPrice().subtract(signal.takeProfitPrice()).doubleValue();
        assertThat(reward / risk).as("RR=tpRMultiple").isCloseTo(2.0, within(1e-6));
    }

    /** 只做空是硬性行为：同样的压缩后向上突破（原多头信号场景），全程不许出信号。 */
    @Test
    void ignoresUpwardBreakout() {
        SqueezeMomentumStrategy strategy = new SqueezeMomentumStrategy(PARAMS, List.of(SYMBOL));
        WindowedMarketView view = new WindowedMarketView(10_000);

        for (KlineBar bar : syntheticMove(true)) {
            view.append(bar);
            assertThat(strategy.onBarClosed(SYMBOL, view)).isEmpty();
        }
    }

    /**
     * 合成行情：0..33 宽幅震荡(±0.8收盘摆动,TR≈2)；34..47 窄幅压缩(close=100,TR≈0.2，
     * stdev迅速跌破SMA(TR)→sqzOn连击)；48..57 连续大幅单边(收盘每根±2.5，stdev爆升穿越
     * SMA(TR)→sqzOff，动量柱同向放大)。up 控制突破方向。
     */
    private static List<KlineBar> syntheticMove(boolean up) {
        List<KlineBar> bars = new ArrayList<>();
        double prevClose = 100;
        for (int i = 0; i < 58; i++) {
            double close, high, low;
            if (i < 34) {
                close = 100 + (i % 2 == 0 ? 0.8 : -0.8);
                high = close + 0.4;
                low = close - 0.4;
            } else if (i < 48) {
                close = 100;
                high = 100.1;
                low = 99.9;
            } else if (up) {
                close = prevClose + 2.5;
                high = close + 0.3;
                low = prevClose - 0.1;
            } else {
                close = prevClose - 2.5;
                low = close - 0.3;
                high = prevClose + 0.1;
            }
            long openTime = i * TF;
            bars.add(new KlineBar(openTime, openTime + TF - 1,
                    bd(prevClose), bd(high), bd(low), bd(close), BigDecimal.ONE));
            prevClose = close;
        }
        return bars;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static org.assertj.core.data.Offset<Double> within(double d) {
        return org.assertj.core.data.Offset.offset(d);
    }
}
