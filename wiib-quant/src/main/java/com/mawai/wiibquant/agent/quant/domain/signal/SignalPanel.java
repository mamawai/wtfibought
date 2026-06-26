package com.mawai.wiibquant.agent.quant.domain.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 信号面板：本轮所有可读信号的汇总。一等展示产物，喂给"支柱①信号叙事"与脆弱度合成。
 *
 * <p>只承载信号本身，不做方向裁决——弱方向 lean / 脆弱度合成在后续 step 处理。</p>
 */
public record SignalPanel(List<Signal> signals) {

    public SignalPanel {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public static SignalPanel empty() {
        return new SignalPanel(List.of());
    }

    /** 按分组归并，保持枚举声明顺序；空组不入 map。 */
    public Map<SignalGroup, List<Signal>> byGroup() {
        Map<SignalGroup, List<Signal>> map = new LinkedHashMap<>();
        for (SignalGroup g : SignalGroup.values()) {
            List<Signal> inGroup = signals.stream().filter(s -> s.group() == g).toList();
            if (!inGroup.isEmpty()) {
                map.put(g, inGroup);
            }
        }
        return map;
    }

    /** 组内净倾向计票（偏多−偏空）：>0 偏多、<0 偏空、=0 中性/分歧。 */
    public int netLean(SignalGroup group) {
        return signals.stream()
                .filter(s -> s.group() == group)
                .mapToInt(s -> s.lean().vote())
                .sum();
    }

    /** 全盘净倾向计票。 */
    public int netLean() {
        return signals.stream().mapToInt(s -> s.lean().vote()).sum();
    }
}
