package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;
import com.mawai.wiibcommon.dto.OptionPositionDTO;
import com.mawai.wiibcommon.dto.PositionDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetSnapshotServiceImpl implements AssetSnapshotService {

    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final PositionService positionService;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final OptionPositionService optionPositionService;
    private final PredictionBetMapper predictionBetMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final BlackjackConvertLogMapper blackjackConvertLogMapper;
    private final SettlementService settlementService;
    private final CryptoOrderMapper cryptoOrderMapper;

    @org.springframework.beans.factory.annotation.Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

    private final Cache<Long, AssetSnapshotDTO> realtimeCache = Caffeine.newBuilder()
            .maximumSize(2000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    private final LoadingCache<Integer, Map<Long, CategoryAveragesDTO>> categoryRankCache = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build(this::buildCategoryRankMap);

    @Override
    public void snapshotAll() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<User> users = userMapper.selectList(null);
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();

        int concurrency = 5;
        Semaphore semaphore = new Semaphore(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] cfs = users.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            computeAndUpsert(user, yesterday, cryptoPriceMap);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("快照用户{}被中断: {}", user.getId(), e.getMessage());
                        } catch (Exception e) {
                            log.warn("快照用户{}失败: {}", user.getId(), e.getMessage(), e);
                        } finally {
                            semaphore.release();
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(cfs).join();
        }

        log.info("每日资产快照完成({}), 共{}人", yesterday, users.size());
    }

    @Override
    public AssetSnapshotDTO getRealtimeSnapshot(Long userId) {
        AssetSnapshotDTO cached = realtimeCache.getIfPresent(userId);
        if (cached != null) return cached;

        User user = userMapper.selectById(userId);
        if (user == null) return null;

        LocalDate today = LocalDate.now();
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        UserAssetSnapshot snapshot = computeSnapshot(user, today, cryptoPriceMap);

        UserAssetSnapshot yesterday = snapshotMapper.selectByUserAndDate(userId, today.minusDays(1));
        AssetSnapshotDTO dto = toDTO(snapshot, yesterday);

        realtimeCache.put(userId, dto);
        return dto;
    }

    @Override
    public List<AssetSnapshotDTO> getHistory(Long userId, int days) {
        LocalDate startDate = LocalDate.now().minusDays(days);
        LocalDate queryStart = startDate.minusDays(1);
        List<UserAssetSnapshot> snapshots = snapshotMapper.listByUserAndDateRange(userId, queryStart);

        List<AssetSnapshotDTO> result = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            UserAssetSnapshot cur = snapshots.get(i);
            if (cur.getSnapshotDate().isBefore(startDate)) continue;
            UserAssetSnapshot prev = (i > 0) ? snapshots.get(i - 1) : null;
            result.add(toDTO(cur, prev));
        }
        return result;
    }

    @Override
    public CategoryAveragesDTO getCategoryAverages(Long userId, int days) {
        Map<Long, CategoryAveragesDTO> rankMap = categoryRankCache.get(days);
        return rankMap.getOrDefault(userId, new CategoryAveragesDTO());
    }

    private Map<Long, CategoryAveragesDTO> buildCategoryRankMap(int days) {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate startDate = today.minusDays(days);

        List<User> users = userMapper.selectList(null);
        if (users.isEmpty()) {
            return Map.of();
        }

        Map<Long, UserAssetSnapshot> baselineMap = new HashMap<>();
        if (!startDate.isAfter(yesterday)) {
            List<UserAssetSnapshot> snapshots = snapshotMapper.listByDateRangeUntil(startDate, yesterday);
            for (UserAssetSnapshot snapshot : snapshots) {
                baselineMap.putIfAbsent(snapshot.getUserId(), snapshot);
            }
        }

        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        Map<Long, UserAssetSnapshot> currentMap = computeRealtimeSnapshots(users, today, cryptoPriceMap);

        Map<Long, BigDecimal[]> userTotals = new HashMap<>();
        for (User user : users) {
            UserAssetSnapshot current = currentMap.get(user.getId());
            if (current == null) {
                continue;
            }
            userTotals.put(user.getId(), diffCategoryProfits(current, baselineMap.get(user.getId())));
        }

        return buildRankResult(userTotals);
    }

    private Map<Long, UserAssetSnapshot> computeRealtimeSnapshots(List<User> users,
                                                                  LocalDate date,
                                                                  Map<String, BigDecimal> cryptoPriceMap) {
        Map<Long, UserAssetSnapshot> snapshots = new ConcurrentHashMap<>();
        int concurrency = 5;
        Semaphore semaphore = new Semaphore(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] cfs = users.stream()
                    .map(user -> CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            snapshots.put(user.getId(), computeSnapshot(user, date, cryptoPriceMap));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("实时快照用户{}被中断: {}", user.getId(), e.getMessage());
                        } catch (Exception e) {
                            log.warn("实时快照用户{}失败: {}", user.getId(), e.getMessage(), e);
                        } finally {
                            semaphore.release();
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(cfs).join();
        }

        return snapshots;
    }

    private Map<Long, CategoryAveragesDTO> buildRankResult(Map<Long, BigDecimal[]> userTotals) {
        int userCount = userTotals.size();
        if (userCount == 0) {
            return Map.of();
        }

        Map<Long, CategoryAveragesDTO> result = new HashMap<>();
        for (Map.Entry<Long, BigDecimal[]> entry : userTotals.entrySet()) {
            BigDecimal[] userRank = new BigDecimal[6];
            for (int i = 0; i < 6; i++) {
                BigDecimal userVal = entry.getValue()[i];
                if (userCount == 1) {
                    userRank[i] = new BigDecimal("100");
                    continue;
                }

                final int idx = i;
                long countBelow = userTotals.values().stream()
                        .filter(values -> values[idx].compareTo(userVal) < 0)
                        .count();

                userRank[i] = BigDecimal.valueOf(countBelow)
                        .divide(BigDecimal.valueOf(userCount - 1), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
            result.put(entry.getKey(), toCategoryAveragesDTO(userRank));
        }
        return Map.copyOf(result);
    }

    private CategoryAveragesDTO toCategoryAveragesDTO(BigDecimal[] values) {
        CategoryAveragesDTO dto = new CategoryAveragesDTO();
        dto.setStockProfit(values[0]);
        dto.setCryptoProfit(values[1]);
        dto.setFuturesProfit(values[2]);
        dto.setOptionProfit(values[3]);
        dto.setPredictionProfit(values[4]);
        dto.setGameProfit(values[5]);
        return dto;
    }

    private BigDecimal[] diffCategoryProfits(UserAssetSnapshot current, UserAssetSnapshot baseline) {
        BigDecimal[] diff = new BigDecimal[6];
        diff[0] = sub(current.getStockProfit(), baseline != null ? baseline.getStockProfit() : null);
        diff[1] = sub(current.getCryptoProfit(), baseline != null ? baseline.getCryptoProfit() : null);
        diff[2] = sub(current.getFuturesProfit(), baseline != null ? baseline.getFuturesProfit() : null);
        diff[3] = sub(current.getOptionProfit(), baseline != null ? baseline.getOptionProfit() : null);
        diff[4] = sub(current.getPredictionProfit(), baseline != null ? baseline.getPredictionProfit() : null);
        diff[5] = sub(current.getGameProfit(), baseline != null ? baseline.getGameProfit() : null);
        return diff;
    }

    private void computeAndUpsert(User user, LocalDate date, Map<String, BigDecimal> cryptoPriceMap) {
        UserAssetSnapshot snapshot = computeSnapshot(user, date, cryptoPriceMap);
        snapshotMapper.upsert(snapshot);
    }

    private UserAssetSnapshot computeSnapshot(User user, LocalDate date, Map<String, BigDecimal> cryptoPriceMap) {
        Long userId = user.getId();

        List<PositionDTO> stockPositions = positionService.getUserPositions(userId);
        BigDecimal stockMarketValue = stockPositions.stream().map(PositionDTO::getMarketValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stockProfit = stockPositions.stream().map(PositionDTO::getProfit).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cryptoProfit = BigDecimal.ZERO;
        BigDecimal cryptoMarketValue = BigDecimal.ZERO;
        List<CryptoPosition> cryptoPositions = cryptoPositionService.getUserPositions(userId);
        for (CryptoPosition cp : cryptoPositions) {
            BigDecimal price = cryptoPriceMap.get(cp.getSymbol());
            if (price != null) {
                BigDecimal totalQty = cp.getQuantity().add(cp.getFrozenQuantity() != null ? cp.getFrozenQuantity() : BigDecimal.ZERO);
                BigDecimal mv = price.multiply(totalQty);
                cryptoMarketValue = cryptoMarketValue.add(mv);
                cryptoProfit = cryptoProfit.add(mv.subtract(cp.getAvgCost().multiply(totalQty)));
            }
        }

        BigDecimal futuresProfit = BigDecimal.ZERO;
        BigDecimal futuresValue = BigDecimal.ZERO;
        List<FuturesPosition> fps = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : fps) {
            BigDecimal markPrice = cacheService.getMarkPrice(fp.getSymbol());
            if (markPrice == null) markPrice = cacheService.getCryptoPrice(fp.getSymbol());
            BigDecimal pnl = "LONG".equals(fp.getSide())
                    ? markPrice.subtract(fp.getEntryPrice()).multiply(fp.getQuantity())
                    : fp.getEntryPrice().subtract(markPrice).multiply(fp.getQuantity());
            futuresProfit = futuresProfit.add(pnl);
            futuresValue = futuresValue.add(fp.getMargin()).add(pnl);
        }

        List<OptionPositionDTO> optionPositions = optionPositionService.getUserPositions(userId);
        BigDecimal optionProfit = optionPositions.stream().map(OptionPositionDTO::getPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal optionValue = optionPositions.stream().map(OptionPositionDTO::getMarketValue).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pendingSettlement = settlementService.getPendingSettlements(userId).stream()
                .map(Settlement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        pendingSettlement = pendingSettlement.add(cryptoOrderMapper.sumSettlingAmount(userId));

        BigDecimal predictionProfit = predictionBetMapper.sumRealizedProfit(userId);

        BigDecimal gameProfit = minesGameMapper.sumNetProfit(userId)
                .add(videoPokerGameMapper.sumNetProfit(userId))
                .add(blackjackConvertLogMapper.sumTotalConverted(userId));

        BigDecimal frozenBalance = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal marginLoan = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal marginInterest = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;
        BigDecimal totalAssets = user.getBalance()
                .add(frozenBalance)
                .add(stockMarketValue).add(cryptoMarketValue)
                .add(pendingSettlement)
                .add(futuresValue)
                .add(optionValue)
                .subtract(marginLoan)
                .subtract(marginInterest);

        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = profit.divide(initialBalance, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));

        UserAssetSnapshot snapshot = new UserAssetSnapshot();
        snapshot.setUserId(userId);
        snapshot.setSnapshotDate(date);
        snapshot.setTotalAssets(totalAssets);
        snapshot.setProfit(profit);
        snapshot.setProfitPct(profitPct);
        snapshot.setStockProfit(stockProfit);
        snapshot.setCryptoProfit(cryptoProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setFuturesProfit(futuresProfit.setScale(2, RoundingMode.HALF_UP));
        snapshot.setOptionProfit(optionProfit);
        snapshot.setPredictionProfit(predictionProfit);
        snapshot.setGameProfit(gameProfit);
        snapshot.setCreatedAt(LocalDateTime.now());
        return snapshot;
    }

    private AssetSnapshotDTO toDTO(UserAssetSnapshot cur, UserAssetSnapshot prev) {
        AssetSnapshotDTO dto = new AssetSnapshotDTO();
        dto.setDate(cur.getSnapshotDate());
        dto.setTotalAssets(cur.getTotalAssets());
        dto.setProfit(cur.getProfit());
        dto.setProfitPct(cur.getProfitPct());
        dto.setStockProfit(cur.getStockProfit());
        dto.setCryptoProfit(cur.getCryptoProfit());
        dto.setFuturesProfit(cur.getFuturesProfit());
        dto.setOptionProfit(cur.getOptionProfit());
        dto.setPredictionProfit(cur.getPredictionProfit());
        dto.setGameProfit(cur.getGameProfit());

        if (prev != null) {
            dto.setDailyProfit(cur.getTotalAssets().subtract(prev.getTotalAssets()));
            BigDecimal prevTotal = prev.getTotalAssets();
            dto.setDailyProfitPct(prevTotal.signum() == 0 ? BigDecimal.ZERO
                    : dto.getDailyProfit().divide(prevTotal, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
            dto.setDailyStockProfit(sub(cur.getStockProfit(), prev.getStockProfit()));
            dto.setDailyCryptoProfit(sub(cur.getCryptoProfit(), prev.getCryptoProfit()));
            dto.setDailyFuturesProfit(sub(cur.getFuturesProfit(), prev.getFuturesProfit()));
            dto.setDailyOptionProfit(sub(cur.getOptionProfit(), prev.getOptionProfit()));
            dto.setDailyPredictionProfit(sub(cur.getPredictionProfit(), prev.getPredictionProfit()));
            dto.setDailyGameProfit(sub(cur.getGameProfit(), prev.getGameProfit()));
        } else {
            dto.setDailyProfit(cur.getProfit());
            dto.setDailyProfitPct(cur.getProfitPct());
            dto.setDailyStockProfit(cur.getStockProfit());
            dto.setDailyCryptoProfit(cur.getCryptoProfit());
            dto.setDailyFuturesProfit(cur.getFuturesProfit());
            dto.setDailyOptionProfit(cur.getOptionProfit());
            dto.setDailyPredictionProfit(cur.getPredictionProfit());
            dto.setDailyGameProfit(cur.getGameProfit());
        }
        return dto;
    }

    private static BigDecimal sub(BigDecimal a, BigDecimal b) {
        return (a != null ? a : BigDecimal.ZERO).subtract(b != null ? b : BigDecimal.ZERO);
    }
}
