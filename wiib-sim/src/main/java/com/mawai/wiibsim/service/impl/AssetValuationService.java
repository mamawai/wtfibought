package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 资产估值统一口径。资产页/每日快照/排行榜/破产判定四处共用，防止口径漂移：
 * 曾分叉出"缺价整仓蒸发"(快照)、"缺价按0计天文亏损"(排行榜)、"永远不算浮盈亏"(破产)三种坏口径。
 */
@Service
@RequiredArgsConstructor
public class AssetValuationService {

    private final CacheService cacheService;
    private final PredictionBetMapper predictionBetMapper;

    /** mark 价优先，现货价兜底；都缺返回 null，由估值函数按"保留保证金跳过浮盈亏"处理。 */
    public BigDecimal resolveFuturesPrice(String symbol) {
        BigDecimal mark = cacheService.getMarkPrice(symbol);
        return mark != null ? mark : cacheService.getCryptoPrice(symbol);
    }

    /**
     * 合约仓位估值。逐仓 = 保证金 + 浮盈亏（保证金已从余额划走，住在仓位里）；
     * 全仓 = 仅浮盈亏（占用制，保证金从没离开余额钱包，再加就重复计钱）。
     * 行情短缺(price=null)时浮盈亏按0。
     */
    public static BigDecimal futuresPositionValue(FuturesPosition fp, BigDecimal markPrice) {
        if (fp.isCross()) {
            return futuresUnrealizedPnl(fp, markPrice);
        }
        if (markPrice == null) {
            return fp.getMargin();
        }
        return fp.getMargin().add(futuresUnrealizedPnl(fp, markPrice));
    }

    /** 未实现盈亏；行情短缺时按 0 计。 */
    public static BigDecimal futuresUnrealizedPnl(FuturesPosition fp, BigDecimal markPrice) {
        if (markPrice == null) {
            return BigDecimal.ZERO;
        }
        return "LONG".equals(fp.getSide())
                ? markPrice.subtract(fp.getEntryPrice()).multiply(fp.getQuantity())
                : fp.getEntryPrice().subtract(markPrice).multiply(fp.getQuantity());
    }

    /** 单笔预测持仓按立刻卖出价(bid)估值；无 bid 视为不可变现计 0。 */
    public static BigDecimal predictionBetValue(PredictionBet bet, BigDecimal bid) {
        if (bid == null || bid.signum() <= 0 || bet.getContracts() == null) {
            return BigDecimal.ZERO;
        }
        return bet.getContracts().multiply(bid);
    }

    /** 单用户预测持仓可变现价值。 */
    public BigDecimal predictionMarketValue(Long userId) {
        List<PredictionBet> bets = predictionBetMapper.selectList(
                new LambdaQueryWrapper<PredictionBet>()
                        .eq(PredictionBet::getUserId, userId)
                        .eq(PredictionBet::getStatus, "ACTIVE"));
        return predictionMarketValue(bets, new java.util.HashMap<>());
    }

    /** 批量场景复用：bidBySide 作为跨用户的 side→bid 缓存(可含 null 值)，缺的内部补查。 */
    public BigDecimal predictionMarketValue(List<PredictionBet> bets, Map<String, BigDecimal> bidBySide) {
        if (bets == null || bets.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PredictionBet bet : bets) {
            String side = bet.getSide();
            if (!bidBySide.containsKey(side)) {
                bidBySide.put(side, cacheService.getPredictionBid(side));
            }
            total = total.add(predictionBetValue(bet, bidBySide.get(side)));
        }
        return total;
    }
}
