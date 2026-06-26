package com.mawai.wiibquant.agent.quant.domain.signal;

/**
 * 信号倾向：信号面板里每条信号的方向语义。
 *
 * <p>NEUTRAL=中性/活跃度类（如成交骤升）；RISK=风险无方向（极端波动、数据降级等告警）。
 * 二者均不参与净倾向计票。</p>
 */
public enum SignalLean {
    BULLISH("偏多"),
    BEARISH("偏空"),
    NEUTRAL("中性"),
    RISK("风险");

    private final String cn;

    SignalLean(String cn) {
        this.cn = cn;
    }

    public String cn() {
        return cn;
    }

    /** 净倾向计票：偏多 +1，偏空 -1，中性/风险不计。 */
    public int vote() {
        return switch (this) {
            case BULLISH -> 1;
            case BEARISH -> -1;
            default -> 0;
        };
    }

    /** 新闻 sentiment（bullish/bearish/neutral）映射为倾向。 */
    public static SignalLean fromSentiment(String sentiment) {
        if (sentiment == null) {
            return NEUTRAL;
        }
        return switch (sentiment.trim().toLowerCase()) {
            case "bullish" -> BULLISH;
            case "bearish" -> BEARISH;
            default -> NEUTRAL;
        };
    }
}
