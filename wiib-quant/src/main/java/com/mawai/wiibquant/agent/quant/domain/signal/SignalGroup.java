package com.mawai.wiibquant.agent.quant.domain.signal;

/**
 * 信号分组：按"人话语义"分组（非按来源 agent），方便简报阅读与脆弱度合成。
 *
 * <p>POSITIONING（持仓情绪）/ VOLATILITY（波动状态）两组，分别是脆弱度
 * "多头拥挤度腿 / vol-state 腿"的信号来源。</p>
 */
public enum SignalGroup {
    MOMENTUM("趋势动量"),
    MICROSTRUCTURE("盘口微结构"),
    POSITIONING("持仓情绪"),
    VOLATILITY("波动状态"),
    NEWS("新闻事件");

    private final String cn;

    SignalGroup(String cn) {
        this.cn = cn;
    }

    public String cn() {
        return cn;
    }
}
