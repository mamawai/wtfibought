package com.mawai.wiibsim.service;
import com.mawai.wiibcommon.cache.CacheService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.service.impl.AssetValuationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final UserService userService;
    private final CryptoPositionService cryptoPositionService;
    private final CacheService cacheService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final AssetValuationService assetValuationService;

    private static final String RANKING_KEY = "ranking:top";
    private static final int TOP_N = 50;

    @Value("${trading.initial-balance:10000}")
    private BigDecimal initialBalance;

    public List<RankingDTO> getRanking() {
        List<RankingDTO> cached = cacheService.getList(RANKING_KEY);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return refreshRanking();
    }

    public List<RankingDTO> refreshRanking() {
        log.info("开始刷新排行榜");
        long start = System.currentTimeMillis();

        // ────── 1. 用户与持仓快照 ──────
        List<User> users = userService.list();
        Map<Long, List<CryptoPosition>> cryptoPositionMap = cryptoPositionService.list().stream()
                .collect(Collectors.groupingBy(CryptoPosition::getUserId));
        List<FuturesPosition> allFuturesPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>().eq(FuturesPosition::getStatus, "OPEN"));
        Map<Long, List<FuturesPosition>> futuresPositionMap = allFuturesPositions.stream()
                .collect(Collectors.groupingBy(FuturesPosition::getUserId));

        // ────── 2. 价格 ──────
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        Map<String, BigDecimal> futuresMarkPriceMap = buildFuturesMarkPriceMap(allFuturesPositions);

        // ────── 3. 待结算金额按用户聚合（crypto settling；老股 T+1 已退） ──────
        Map<Long, BigDecimal> cryptoSettlingMap = toUserAmountMap(cryptoOrderMapper.sumAllSettlingAmounts());

        // 预测持仓可变现价值(bid×contracts)，与资产页/快照/破产判定同口径
        Map<Long, List<PredictionBet>> activeBetMap = predictionBetMapper.selectList(
                        new LambdaQueryWrapper<PredictionBet>().eq(PredictionBet::getStatus, "ACTIVE")).stream()
                .collect(Collectors.groupingBy(PredictionBet::getUserId));
        Map<String, BigDecimal> predictionBidCache = new HashMap<>();

        // ────── 4. 硬实力盈亏批量聚合（口径：交易净盈亏，优惠券另列） ──────
        Map<Long, BigDecimal> futuresNetMap = toUserAmountMap(futuresOrderMapper.sumNetPnlAfterCommissionAll());
        Map<Long, BigDecimal> futuresFundingFeeMap = toUserAmountMap(futuresPositionMapper.sumFundingFeeTotalAll());
        Map<Long, BigDecimal> predictionRealizedMap = toUserAmountMap(predictionBetMapper.sumRealizedProfitAfterBuyFeeAll());
        Map<Long, BigDecimal> cryptoBuyMap = toUserAmountMap(cryptoOrderMapper.sumBuyFilledAmountAll());
        Map<Long, BigDecimal> cryptoSellMap = toUserAmountMap(cryptoOrderMapper.sumSellFilledOrSettlingAmountAll());
        Map<Long, BigDecimal> cryptoDiscountMap = toUserAmountMap(cryptoOrderMapper.sumBuyDiscountAll());

        // ────── 5. 逐用户聚合 ──────
        List<RankingDTO> rankings = new ArrayList<>(users.size());
        for (User user : users) {
            Long uid = user.getId();

            BigDecimal balance = nz(user.getBalance());
            BigDecimal frozen = nz(user.getFrozenBalance());
            BigDecimal loanPrincipal = nz(user.getMarginLoanPrincipal());
            BigDecimal loanInterest = nz(user.getMarginInterestAccrued());

            BigDecimal cryptoMarketValue = cryptoMarketValue(cryptoPositionMap.get(uid), cryptoPriceMap);
            FuturesPnL futures = futuresPnL(futuresPositionMap.get(uid), futuresMarkPriceMap);

            BigDecimal pendingSettlement = nz(cryptoSettlingMap.get(uid));
            BigDecimal predictionValue = assetValuationService.predictionMarketValue(activeBetMap.get(uid), predictionBidCache);

            BigDecimal totalAssets = balance.add(frozen).add(nz(user.getGameBalance()))
                    .add(cryptoMarketValue).add(pendingSettlement)
                    .add(futures.value()).add(predictionValue)
                    .subtract(loanPrincipal).subtract(loanInterest);

            // 硬实力盈亏 = 合约净盈亏 + 现货现金流(扣优惠券) + 预测已结算净盈亏
            BigDecimal buffProfit = nz(cryptoDiscountMap.get(uid));
            BigDecimal futuresProfit = nz(futuresNetMap.get(uid))
                    .add(futures.unrealizedPnl())
                    .subtract(nz(futuresFundingFeeMap.get(uid)));
            BigDecimal cryptoProfit = nz(cryptoSellMap.get(uid))
                    .subtract(nz(cryptoBuyMap.get(uid)))
                    .add(cryptoMarketValue)
                    .subtract(buffProfit);
            BigDecimal predictionProfit = nz(predictionRealizedMap.get(uid));
            BigDecimal hardcoreProfit = futuresProfit.add(cryptoProfit).add(predictionProfit);

            rankings.add(getRankingDTO(user, totalAssets, hardcoreProfit, buffProfit));
        }

        // ────── 6. 排序 / 取 TopN / 缓存 ──────
        rankings.sort(Comparator.comparing(RankingDTO::getTotalAssets).reversed());
        List<RankingDTO> topN = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, rankings.size()); i++) {
            RankingDTO dto = rankings.get(i);
            dto.setRank(i + 1);
            topN.add(dto);
        }
        cacheService.setObject(RANKING_KEY, topN, 15, TimeUnit.MINUTES);

        log.info("排行榜刷新完成，共{}人，耗时{}ms", topN.size(), System.currentTimeMillis() - start);
        return topN;
    }

    private RankingDTO getRankingDTO(User user, BigDecimal totalAssets, BigDecimal hardcoreProfit, BigDecimal buffProfit) {
        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = initialBalance.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(initialBalance, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        RankingDTO dto = new RankingDTO();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setTotalAssets(totalAssets.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitPct(profitPct.setScale(2, RoundingMode.HALF_UP));
        dto.setHardcoreProfit(hardcoreProfit.setScale(2, RoundingMode.HALF_UP));
        dto.setBuffProfit(buffProfit.setScale(2, RoundingMode.HALF_UP));
        return dto;
    }

    /** 把 List<Map<"user_id"/"amount">> 转成 Map<userId, amount>，供批量聚合查询使用 */
    private static Map<Long, BigDecimal> toUserAmountMap(List<Map<String, Object>> rows) {
        Map<Long, BigDecimal> map = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            map.put(((Number) row.get("user_id")).longValue(), (BigDecimal) row.get("amount"));
        }
        return map;
    }

    /** null 兜 0，避免 user 字段或聚合 map 缺值时反复写三元 */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** 合约仓位估值同时产出 (margin+未实现盈亏) 与 单独的未实现盈亏，硬实力计算两者都要 */
    private record FuturesPnL(BigDecimal value, BigDecimal unrealizedPnl) {}

    private static BigDecimal cryptoMarketValue(List<CryptoPosition> positions, Map<String, BigDecimal> priceMap) {
        if (positions == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CryptoPosition cp : positions) {
            BigDecimal price = priceMap.getOrDefault(cp.getSymbol(), BigDecimal.ZERO);
            sum = sum.add(price.multiply(cp.getTotalQuantity()));
        }
        return sum;
    }

    private static FuturesPnL futuresPnL(List<FuturesPosition> positions, Map<String, BigDecimal> markPriceMap) {
        if (positions == null) return new FuturesPnL(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (FuturesPosition fp : positions) {
            // 缺价保留保证金、浮盈亏按0(统一口径见 AssetValuationService)；旧版缺价按0计价会算出天文亏损
            BigDecimal markPrice = markPriceMap.get(fp.getSymbol());
            value = value.add(AssetValuationService.futuresPositionValue(fp, markPrice));
            unrealized = unrealized.add(AssetValuationService.futuresUnrealizedPnl(fp, markPrice));
        }
        return new FuturesPnL(value, unrealized);
    }

    /** 合约 mark 价，缺失退回现货价；都缺不入 map(旧版 toMap 遇 null 值会 NPE 炸掉整次刷新)，下游按"保留保证金"处理 */
    private Map<String, BigDecimal> buildFuturesMarkPriceMap(List<FuturesPosition> positions) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (String symbol : positions.stream().map(FuturesPosition::getSymbol).distinct().toList()) {
            BigDecimal p = assetValuationService.resolveFuturesPrice(symbol);
            if (p != null) map.put(symbol, p);
        }
        return map;
    }
}
