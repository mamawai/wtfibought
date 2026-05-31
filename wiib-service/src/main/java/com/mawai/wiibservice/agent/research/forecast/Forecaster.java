package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 可插拔预测器。入参是决策点"当下及以前"的 bars（point-in-time，绝不含未来）。 */
public interface Forecaster {

    Forecast forecast(List<KlineBar> historyUpToNow);

    String name();
}
