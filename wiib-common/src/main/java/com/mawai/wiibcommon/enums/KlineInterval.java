package com.mawai.wiibcommon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * K 线周期枚举。
 * 用于"主决策周期"配置切换（trading.decision-interval），以及聚合到更大周期时的系数计算。
 */
@Getter
@AllArgsConstructor
public enum KlineInterval {

    M1("1m", 1, 1440),
    M3("3m", 3, 480),
    M5("5m", 5, 288),
    M15("15m", 15, 96),
    H1("1h", 60, 24);

    /** Binance API 字符串 */
    private final String code;
    /** 每根 K 线的分钟数（聚合系数用） */
    private final int minutes;
    /** 拉取根数（统一保持 24h 决策窗口） */
    private final int historyLimit;

    /**
     * 从当前周期聚合到更大周期需要多少根（如 M5 → M15 = 3）。
     */
    public int aggregateRatioTo(KlineInterval larger) {
        if (larger.minutes % this.minutes != 0) {
            throw new IllegalArgumentException(
                    "无法整除聚合：" + this.code + " → " + larger.code);
        }
        return larger.minutes / this.minutes;
    }

    /**
     * 从 Binance API 字符串反查枚举。
     */
    public static KlineInterval fromCode(String code) {
        for (KlineInterval iv : values()) {
            if (iv.code.equals(code)) {
                return iv;
            }
        }
        throw new IllegalArgumentException("未知周期：" + code);
    }
}
