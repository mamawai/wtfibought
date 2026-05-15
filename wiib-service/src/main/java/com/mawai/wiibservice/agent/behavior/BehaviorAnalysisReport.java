package com.mawai.wiibservice.agent.behavior;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class BehaviorAnalysisReport {

    private Overview overview;
    private TradeBehavior tradeBehavior;
    private GameBehavior gameBehavior;
    private RiskProfile riskProfile;
    private List<String> suggestions;

    @Data
    public static class Overview {
        private BigDecimal totalAssets;
        private BigDecimal totalProfitPct;
        private List<AssetDistribution> distribution;
        private List<AssetTrend> trend;
    }

    @Data
    public static class AssetDistribution {
        private String category;
        private BigDecimal value;
    }

    @Data
    public static class AssetTrend {
        private String date;
        private BigDecimal totalAssets;
    }

    @Data
    public static class TradeBehavior {
        private StockBehavior stock;
        private CryptoBehavior crypto;
        private FuturesBehavior futures;
        private OptionBehavior option;
        private PredictionBehavior prediction;
    }

    @Data
    public static class StockBehavior {
        private int positionCount;
        private int orderCount;
        private BigDecimal totalBuyAmount;
        private String preference;
    }

    @Data
    public static class CryptoBehavior {
        private int positionCount;
        private BigDecimal totalBuyAmount;
        private BigDecimal totalSellAmount;
        private String leverageUsage;
    }

    @Data
    public static class FuturesBehavior {
        private BigDecimal realizedPnl;
        private int orderCount;
        private String direction;
        private BigDecimal avgLeverage;
        private BigDecimal stopLossRate;
        private int liquidationCount;
    }

    @Data
    public static class OptionBehavior {
        private BigDecimal totalBtoAmount;
        private BigDecimal totalStcAmount;
    }

    @Data
    public static class PredictionBehavior {
        private int frequency;
        private BigDecimal netProfit;
        private BigDecimal winRate;
        private String directionPreference;
    }

    @Data
    public static class GameBehavior {
        private BlackjackBehavior blackjack;
        private MinesBehavior mines;
        private VideoPokerBehavior videoPoker;
    }

    @Data
    public static class BlackjackBehavior {
        private long totalHands;
        private long totalWon;
        private long totalLost;
        private long biggestWin;
        private long todayConverted;
    }

    @Data
    public static class MinesBehavior {
        private int frequency;
        private BigDecimal netProfit;
    }

    @Data
    public static class VideoPokerBehavior {
        private int frequency;
        private BigDecimal netProfit;
    }

    @Data
    public static class RiskProfile {
        private String riskLevel;
        private int bankruptCount;
        private String maxDrawdown;
        private String bankruptAt;
    }

    public boolean isValid() {
        if (overview == null || overview.getDistribution() == null
                || overview.getTotalAssets() == null || overview.getTotalProfitPct() == null) return false;
        if (tradeBehavior == null || tradeBehavior.getStock() == null
                || tradeBehavior.getStock().getTotalBuyAmount() == null) return false;
        if (tradeBehavior.getCrypto() == null
                || tradeBehavior.getCrypto().getTotalBuyAmount() == null
                || tradeBehavior.getCrypto().getTotalSellAmount() == null) return false;
        if (tradeBehavior.getFutures() == null
                || tradeBehavior.getFutures().getRealizedPnl() == null
                || tradeBehavior.getFutures().getAvgLeverage() == null) return false;
        if (tradeBehavior.getPrediction() == null
                || tradeBehavior.getPrediction().getNetProfit() == null
                || tradeBehavior.getPrediction().getWinRate() == null) return false;
        return riskProfile != null && suggestions != null;
        // gameBehavior 为可选，不参与校验
    }
}