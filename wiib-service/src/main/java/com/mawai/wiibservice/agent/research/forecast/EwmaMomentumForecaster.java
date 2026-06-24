package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 故意简单的基线：EWMA(fast)−EWMA(slow) 的符号当方向。存在只为立标尺，不为赢。 */
public final class EwmaMomentumForecaster implements Forecaster {

    private final int fast;
    private final int slow;

    public EwmaMomentumForecaster(int fast, int slow) {
        this.fast = fast;
        this.slow = slow;
    }

    @Override
    public Forecast forecast(ResearchFeatures features) {
        List<KlineBar> historyUpToNow = features.barsUpToNow();   // 纯价格腿：只取 bars，忽略链下字段
        if (historyUpToNow == null || historyUpToNow.size() < slow) return Forecast.flat();
        // research 回测会对每个历史点调用一次；这里用 double EMA，避免 BigDecimal 列表分配拖慢基线。
        double ef = emaClose(historyUpToNow, fast);
        double es = emaClose(historyUpToNow, slow);
        if (!Double.isFinite(ef) || !Double.isFinite(es) || es == 0.0) return Forecast.flat();
        double spread = (ef - es) / es;
        int dir = spread > 0.0 ? 1 : (spread < 0.0 ? -1 : 0);
        double conf = Math.min(1.0, Math.abs(spread) * 100); // 简单缩放，仅诊断用
        return new Forecast(dir, conf);
    }

    private static double emaClose(List<KlineBar> bars, int period) {
        if (bars == null || bars.size() < period) return Double.NaN;
        double seed = 0.0;
        for (int i = 0; i < period; i++) {
            seed += bars.get(i).close().doubleValue();
        }
        double ema = seed / period;
        double k = 2.0 / (period + 1.0);
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close().doubleValue() * k + ema * (1.0 - k);
        }
        return ema;
    }

    @Override
    public String name() {
        return "ewma_momentum_" + fast + "_" + slow;
    }
}
