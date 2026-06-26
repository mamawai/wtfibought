package com.mawai.wiibquant.agent.quant.domain.debate;

import com.mawai.wiibquant.agent.quant.domain.Direction;

/**
 * 弱方向 lean（debate 降级输出的展示产物）。
 *
 * <p><b>非硬核预测</b>：lean 是低置信弱倾向，{@code NO_TRADE} = 无方向态（信号矛盾时大方"看不清"）。
 * 始终配情景分布 + 后果叙事 + 反事实失效条件，可证伪、可审计。与 overall_decision 解耦
 * （Step 3 仅产出展示，方向裁决仍由 ConsensusJudge/debate 改写链负责，Step 7 总装切换）。</p>
 *
 * @param horizon      H6/H12/H24
 * @param lean         弱方向；NO_TRADE = 无方向态
 * @param bullPct      情景分布·涨
 * @param rangePct     情景分布·震荡
 * @param bearPct      情景分布·跌
 * @param consequence  后果叙事（"若X兑现 → 未来Yh可能Z"），可空
 * @param invalidation 反事实失效条件（"若A则本 lean 作废"），可空
 */
public record WeakLean(
        String horizon,
        Direction lean,
        int bullPct,
        int rangePct,
        int bearPct,
        String consequence,
        String invalidation
) {
    /** 全局展示标签：钉死"非硬核"，不滑回民科预测。 */
    public static final String LABEL = "低置信·非硬核预测";

    public String label() {
        return LABEL;
    }

    /** 从 Judge 单区间裁决组装；缺失/异常字段降级处理，不抛错。 */
    public static WeakLean from(String horizon, String leanStr,
                                Integer bullPct, Integer rangePct, Integer bearPct,
                                String consequence, String invalidation) {
        Direction lean = parseLean(leanStr);
        int[] dist = normalizeDist(bullPct, rangePct, bearPct);
        return new WeakLean(horizon, lean, dist[0], dist[1], dist[2],
                blankToNull(consequence), blankToNull(invalidation));
    }

    private static Direction parseLean(String s) {
        if (s == null) {
            return Direction.NO_TRADE;
        }
        return switch (s.toUpperCase().trim()) {
            case "LONG" -> Direction.LONG;
            case "SHORT" -> Direction.SHORT;
            default -> Direction.NO_TRADE;
        };
    }

    /** 情景分布归一化到 100；缺失或全 0 视作无信息 → 均分。 */
    private static int[] normalizeDist(Integer bull, Integer range, Integer bear) {
        if (bull == null || range == null || bear == null) {
            return new int[]{33, 34, 33};
        }
        int b = Math.max(0, bull), r = Math.max(0, range), s = Math.max(0, bear);
        int sum = b + r + s;
        if (sum == 0) {
            return new int[]{33, 34, 33};
        }
        int nb = Math.round(b * 100f / sum);
        int ns = Math.round(s * 100f / sum);
        int nr = 100 - nb - ns; // 兜底吸收舍入误差，保证三者和=100
        return new int[]{nb, nr, ns};
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
