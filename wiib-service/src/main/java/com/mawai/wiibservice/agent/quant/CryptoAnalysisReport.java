package com.mawai.wiibservice.agent.quant;

import lombok.Data;

import java.math.BigDecimal;
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

    public boolean isValid() {
        if (direction == null) return false;
        if (direction.ultraShort == null || direction.shortTerm == null
                || direction.mid == null || direction.longTerm == null) return false;
        if (keyLevels == null || keyLevels.support == null || keyLevels.resistance == null) return false;
        if (positionAdvice == null || riskWarnings == null) return false;
        return true;
    }
}
