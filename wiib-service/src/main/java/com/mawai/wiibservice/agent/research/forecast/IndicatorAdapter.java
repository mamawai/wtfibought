package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 零侵入复用 live 的 {@link CryptoIndicatorCalculator}：KlineBar → calcAll 入参 [high,low,close,volume]。
 * 只读、纯函数；末根按已收盘处理（lastClosed=true，回测口径）。
 * &lt;30 bar 指标算不出 → 返回空 Map（不透出 calcAll 的 error 字段，由调用方按"暖机"处理）。
 */
public final class IndicatorAdapter {

    private IndicatorAdapter() {
    }

    /** KlineBar → calcAll 入参，每行 [high, low, close, volume]（顺序须与 calcAll 约定一致，错位会污染 ATR/ADX/boll）。 */
    public static List<BigDecimal[]> toCalcInput(List<KlineBar> bars) {
        List<BigDecimal[]> out = new ArrayList<>(bars.size());
        for (KlineBar b : bars) {
            out.add(new BigDecimal[]{b.high(), b.low(), b.close(), b.volume()});
        }
        return out;
    }

    /** 全套指标快照（字段见 CryptoIndicatorCalculator）；&lt;30 bar（calcAll 下限）返回空 Map。 */
    public static Map<String, Object> indicators(List<KlineBar> bars) {
        if (bars == null || bars.size() < 30) return Map.of();
        return CryptoIndicatorCalculator.calcAll(toCalcInput(bars), true);
    }
}
