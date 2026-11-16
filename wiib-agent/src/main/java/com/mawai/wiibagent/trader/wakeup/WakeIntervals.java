package com.mawai.wiibagent.trader.wakeup;

import java.util.Map;
import java.util.Set;

/** 唤醒档位：心跳周期的码和毫秒数，以及把时刻对齐到档位边界 */
public final class WakeIntervals {

    /** interval → 毫秒；唤醒预算、建 trader 校验、提示词都共用同一份。1d 只留数学（日线交接边界用），不再是唤醒档位 */
    public static final Map<String, Long> MS = Map.of(
            "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);
    /** 心跳档位只有三档：5m、1d 已下线，不在这里的档位例行 / 手动 / 非例行都不叫（存量 5m 由 init.sql 迁 15m） */
    public static final Set<String> CODES = Set.of("15m", "1h", "4h");
    /** 新建 trader 和没 trader 时的预览都按这个档位 */
    public static final String DEFAULT = "1h";

    private WakeIntervals() {
    }

    /** time 所在那一根 K 线的起点：往回对齐到 intervalMs 的整数倍 */
    public static long align(long time, long intervalMs) {
        return time - Math.floorMod(time, intervalMs);
    }
}
