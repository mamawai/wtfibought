package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.OptionContractMapper;
import com.mawai.wiibservice.mapper.PredictionBetMapper;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final UserService userService;
    private final PositionService positionService;
    private final CryptoPositionService cryptoPositionService;
    private final CacheService cacheService;
    private final SettlementService settlementService;
    private final StockCacheService stockCacheService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final OptionPositionService optionPositionService;
    private final OptionContractMapper optionContractMapper;
    private final OptionPricingService optionPricingService;
    private final PredictionBetMapper predictionBetMapper;

    private static final String RANKING_KEY = "ranking:top";
    private static final int TOP_N = 50;

    @Value("${trading.initial-balance:100000}")
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
        Map<Long, List<Position>> stockPositionMap = positionService.list().stream()
                .collect(Collectors.groupingBy(Position::getUserId));
        Map<Long, List<CryptoPosition>> cryptoPositionMap = cryptoPositionService.list().stream()
                .collect(Collectors.groupingBy(CryptoPosition::getUserId));
        List<FuturesPosition> allFuturesPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>().eq(FuturesPosition::getStatus, "OPEN"));
        Map<Long, List<FuturesPosition>> futuresPositionMap = allFuturesPositions.stream()
                .collect(Collectors.groupingBy(FuturesPosition::getUserId));
        List<OptionPosition> allOptionPositions = optionPositionService.list(
                new LambdaQueryWrapper<OptionPosition>().gt(OptionPosition::getQuantity, 0));
        Map<Long, List<OptionPosition>> optionPositionMap = allOptionPositions.stream()
                .collect(Collectors.groupingBy(OptionPosition::getUserId));

        // ────── 2. 价格 / 合约元数据 ──────
        Map<Long, BigDecimal> stockPriceMap = buildStockPriceMap();
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        Map<String, BigDecimal> futuresMarkPriceMap = buildFuturesMarkPriceMap(allFuturesPositions);
        Map<Long, OptionContract> optionContractMap = buildOptionContractMap(allOptionPositions);

        // ────── 3. 待结算金额按用户聚合 ──────
        Map<Long, BigDecimal> pendingMap = settlementService.getAllPendingSettlements().stream()
                .collect(Collectors.groupingBy(Settlement::getUserId,
                        Collectors.reducing(BigDecimal.ZERO, Settlement::getAmount, BigDecimal::add)));
        Map<Long, BigDecimal> cryptoSettlingMap = toUserAmountMap(cryptoOrderMapper.sumAllSettlingAmounts());

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

            BigDecimal stockMarketValue = stockMarketValue(stockPositionMap.get(uid), stockPriceMap);
            BigDecimal cryptoMarketValue = cryptoMarketValue(cryptoPositionMap.get(uid), cryptoPriceMap);
            FuturesPnL futures = futuresPnL(futuresPositionMap.get(uid), futuresMarkPriceMap);
            BigDecimal optionMarketValue = optionMarketValue(optionPositionMap.get(uid), optionContractMap, stockPriceMap);

            BigDecimal pendingSettlement = nz(pendingMap.get(uid)).add(nz(cryptoSettlingMap.get(uid)));

            BigDecimal totalAssets = balance.add(frozen)
                    .add(stockMarketValue).add(cryptoMarketValue).add(pendingSettlement)
                    .add(futures.value()).add(optionMarketValue)
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

    private static BigDecimal stockMarketValue(List<Position> positions, Map<Long, BigDecimal> priceMap) {
        if (positions == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal price = priceMap.getOrDefault(p.getStockId(), BigDecimal.ZERO);
            sum = sum.add(price.multiply(BigDecimal.valueOf(p.getTotalQuantity())));
        }
        return sum;
    }

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
            BigDecimal markPrice = markPriceMap.getOrDefault(fp.getSymbol(), BigDecimal.ZERO);
            BigDecimal pnl = "LONG".equals(fp.getSide())
                    ? markPrice.subtract(fp.getEntryPrice()).multiply(fp.getQuantity())
                    : fp.getEntryPrice().subtract(markPrice).multiply(fp.getQuantity());
            value = value.add(fp.getMargin()).add(pnl);
            unrealized = unrealized.add(pnl);
        }
        return new FuturesPnL(value, unrealized);
    }

    private BigDecimal optionMarketValue(List<OptionPosition> positions,
                                         Map<Long, OptionContract> contractMap,
                                         Map<Long, BigDecimal> stockPriceMap) {
        if (positions == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (OptionPosition op : positions) {
            OptionContract contract = contractMap.get(op.getContractId());
            if (contract == null) continue;
            BigDecimal spotPrice = stockPriceMap.getOrDefault(contract.getStockId(), BigDecimal.ZERO);
            BigDecimal premium = optionPricingService.calculatePremium(
                    contract.getOptionType(), spotPrice, contract.getStrike(),
                    contract.getExpireAt(), contract.getSigma());
            sum = sum.add(premium.multiply(BigDecimal.valueOf(op.getQuantity())));
        }
        return sum;
    }

    /** 缓存命中走实时价，否则退回 prevClose，全无则 0 */
    private Map<Long, BigDecimal> buildStockPriceMap() {
        Set<Long> stockIds = stockCacheService.getAllStockIds();
        Map<Long, BigDecimal> cached = cacheService.getCurrentPrices(new ArrayList<>(stockIds));
        return stockIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> {
                    if (cached.containsKey(id)) return cached.get(id);
                    Map<String, String> stockStatic = stockCacheService.getStockStatic(id);
                    if (stockStatic != null && stockStatic.get("prevClose") != null) {
                        return new BigDecimal(stockStatic.get("prevClose"));
                    }
                    return BigDecimal.ZERO;
                }
        ));
    }

    /** 合约 mark 价，缺失退回现货价 */
    private Map<String, BigDecimal> buildFuturesMarkPriceMap(List<FuturesPosition> positions) {
        return positions.stream()
                .map(FuturesPosition::getSymbol).distinct()
                .collect(Collectors.toMap(Function.identity(), s -> {
                    BigDecimal mp = cacheService.getMarkPrice(s);
                    return mp != null ? mp : cacheService.getCryptoPrice(s);
                }));
    }

    private Map<Long, OptionContract> buildOptionContractMap(List<OptionPosition> positions) {
        Set<Long> contractIds = positions.stream()
                .map(OptionPosition::getContractId).collect(Collectors.toSet());
        if (contractIds.isEmpty()) return Map.of();
        return optionContractMapper.selectByIds(contractIds).stream()
                .collect(Collectors.toMap(OptionContract::getId, Function.identity()));
    }
}
