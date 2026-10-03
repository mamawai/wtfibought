package com.mawai.wiibagent.behavior;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class BehaviorAnalysisReport {

    private Overview overview;
    private TradeBehavior tradeBehavior;
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
        private CryptoBehavior crypto;
        private BStockBehavior bstock;
        private FuturesBehavior futures;
        private PredictionBehavior prediction;
    }

    /** bStock 代币化美股：共用现货引擎，靠 symbol 集与 crypto 区分（数据源 bstock-stats） */
    @Data
    public static class BStockBehavior {
        private int positionCount;
        private BigDecimal totalBuyAmount;
        private BigDecimal totalSellAmount;
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
        /** 分品类拆解（与资产四分类同源符号集）；软字段不参与 isValid 硬校验 */
        private FuturesCategoryBreakdown byCategory;
    }

    /** crypto=加密永续、commodity=金/油、tradfi=美股ETF永续 */
    @Data
    public static class FuturesCategoryBreakdown {
        private FuturesCategoryStats crypto;
        private FuturesCategoryStats commodity;
        private FuturesCategoryStats tradfi;
    }

    @Data
    public static class FuturesCategoryStats {
        private BigDecimal realizedPnl;
        private int orderCount;
    }

    @Data
    public static class PredictionBehavior {
        private int frequency;
        private BigDecimal netProfit;
        private BigDecimal winRate;
        private String directionPreference;
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
        // 只校验现役维度（crypto/futures/prediction）——已下线维度的硬校验会逼模型瞎编，见 BehaviorReportValidityTest
        if (tradeBehavior == null) return false;
        if (tradeBehavior.getCrypto() == null
                || tradeBehavior.getCrypto().getTotalBuyAmount() == null
                || tradeBehavior.getCrypto().getTotalSellAmount() == null) return false;
        if (tradeBehavior.getBstock() == null
                || tradeBehavior.getBstock().getTotalBuyAmount() == null) return false;
        if (tradeBehavior.getFutures() == null
                || tradeBehavior.getFutures().getRealizedPnl() == null
                || tradeBehavior.getFutures().getAvgLeverage() == null) return false;
        if (tradeBehavior.getPrediction() == null
                || tradeBehavior.getPrediction().getNetProfit() == null
                || tradeBehavior.getPrediction().getWinRate() == null) return false;
        return riskProfile != null && suggestions != null;
    }
}