package com.mawai.wiibquant.agent.quant;

import lombok.Data;

import java.util.List;

@Data
public class CryptoAnalysisReport {

    private String summary;
    private String analysisBasis;
    private String reasoning;
    private DirectionInfo direction;
    private KeyLevels keyLevels;
    private String indicators;
    private List<ImportantNews> importantNews;  // 重要新闻
    private List<PositionAdvice> positionAdvice;
    private List<String> riskWarnings;
    private int confidence;
    private DebateSummary debateSummary;
    private String macroContext;
    private List<VolProfile> volProfile;  // 波动画像（主腿，vol 经样本外验证唯一 skilled）

    @Data
    public static class DirectionInfo {
        private String ultraShort;
        private String shortTerm;
        private String mid;
        private String longTerm;
    }

    @Data
    public static class KeyLevels {
        private List<String> support;
        private List<String> resistance;
    }

    @Data
    public static class PositionAdvice {
        private String period;
        private String type;
        private String entry;
        private String stopLoss;
        private String takeProfit;
        private String riskReward;
    }

    @Data
    public static class ImportantNews {
        private String title;
        private String sentiment;
        private String summary;
    }

    @Data
    public static class DebateSummary {
        private String bullArgument;
        private String bearArgument;
        private String judgeReasoning;
    }

    /** 波动画像：每 horizon 一项，agent 唯一被验证 skilled 的腿。 */
    @Data
    public static class VolProfile {
        private String horizon;             // H6/H12/H24
        private String volState;            // 波动状态档
        private int expectedMoveBps;        // 预期波动 bps
        private double trailingPercentile;  // 历史分位 0-1
        private double riskBudgetHint;      // 风险预算 0-1
    }

    public boolean isValid() {
        if (direction == null) return false;
        if (direction.ultraShort == null || direction.shortTerm == null
                || direction.mid == null || direction.longTerm == null) return false;
        if (keyLevels == null || keyLevels.support == null || keyLevels.resistance == null) return false;
        if (positionAdvice == null || riskWarnings == null) return false;
        return true;
    }
}
