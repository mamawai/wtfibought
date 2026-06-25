package com.mawai.wiibquant.agent.research.forecast;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.tool.CryptoIndicatorCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零侵入复用 live 的 {@link CryptoIndicatorCalculator}：KlineBar → calcAll 入参 [high,low,close,volume]。
 * 只读、纯函数；末根按已收盘处理（lastClosed=true，回测口径）。
 * &lt;30 bar 指标算不出 → 返回空 Map（不透出 calcAll 的 error 字段，由调用方按"暖机"处理）。
 */
public final class IndicatorAdapter {

    private static final ThreadLocal<Map<List<KlineBar>, Map<String, Object>>> RESEARCH_INDICATOR_CACHE =
            ThreadLocal.withInitial(IdentityHashMap::new);

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

    /** research 方向/regime 专用轻量快照；只含当前模型读取的字段，历史长跑必须走这条。 */
    public static Map<String, Object> researchIndicators(List<KlineBar> bars) {
        if (bars == null || bars.size() < 30) return Map.of();
        Map<List<KlineBar>, Map<String, Object>> cache = RESEARCH_INDICATOR_CACHE.get();
        Map<String, Object> cached = cache.get(bars);
        if (cached != null) return cached;
        // 同一轮评估里多个 forecaster 会看同一个 barsUpToNow；按对象身份缓存，避免 equals 扫整段 K 线。
        Map<String, Object> computed = CryptoIndicatorCalculator.calcResearchSnapshot(toCalcInput(bars), true);
        cache.put(bars, computed);
        return computed;
    }

    /** 每次离线评估结束必须清理线程缓存；否则长跑/线程复用会把历史 features 挂住。 */
    public static void clearThreadCache() {
        RESEARCH_INDICATOR_CACHE.remove();
    }
}
