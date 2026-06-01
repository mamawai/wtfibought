package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.Ema;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        List<BigDecimal> closes = historyUpToNow.stream().map(KlineBar::close).toList();
        BigDecimal ef = Ema.ema(closes, fast);
        BigDecimal es = Ema.ema(closes, slow);
        if (ef == null || es == null || es.signum() == 0) return Forecast.flat();
        BigDecimal spread = ef.subtract(es).divide(es, 8, RoundingMode.HALF_UP);
        int dir = spread.signum();
        double conf = Math.min(1.0, Math.abs(spread.doubleValue()) * 100); // 简单缩放，仅诊断用
        return new Forecast(dir, conf);
    }

    @Override
    public String name() {
        return "ewma_momentum_" + fast + "_" + slow;
    }
}
