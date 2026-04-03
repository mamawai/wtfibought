package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesSettlementService;
import com.mawai.wiibservice.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.mawai.wiibservice.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesSettlementServiceImpl implements FuturesSettlementService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesRiskService riskService;

    @PostConstruct
    void init() {
        rebuildLimitOrderZSets();
    }

    // ==================== 限价单触发 ====================

    @Override
    public void onPriceUpdate(String symbol, BigDecimal price) {
        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        Set<String> openLongHits = cacheService.zRangeByScore(openLongKey, price.doubleValue(), Double.MAX_VALUE);
        Set<String> openShortHits = cacheService.zRangeByScore(openShortKey, 0, price.doubleValue());
        Set<String> closeLongHits = cacheService.zRangeByScore(closeLongKey, 0, price.doubleValue());
        Set<String> closeShortHits = cacheService.zRangeByScore(closeShortKey, price.doubleValue(), Double.MAX_VALUE);

        if (openLongHits != null && !openLongHits.isEmpty()) {
            cacheService.zRemove(openLongKey, openLongHits.toArray());
            for (String id : openLongHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (openShortHits != null && !openShortHits.isEmpty()) {
            cacheService.zRemove(openShortKey, openShortHits.toArray());
            for (String id : openShortHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeLongHits != null && !closeLongHits.isEmpty()) {
            cacheService.zRemove(closeLongKey, closeLongHits.toArray());
            for (String id : closeLongHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeShortHits != null && !closeShortHits.isEmpty()) {
            cacheService.zRemove(closeShortKey, closeShortHits.toArray());
            for (String id : closeShortHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
    }

    private void triggerLimitOrder(Long orderId, BigDecimal triggerPrice) {
        try {
            var proxy = SpringUtils.getAopProxy(this);
            if (!proxy.markOrderTriggered(orderId, triggerPrice)) return;
            FuturesOrder order = orderMapper.selectById(orderId);
            if (order != null) {
                proxy.processTriggeredOrder(order);
            }
        } catch (Exception e) {
            log.error("futures限价单触发失败 orderId={}", orderId, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected boolean markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = orderMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) {
            log.info("futures限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
            return true;
        }
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void processTriggeredOrder(FuturesOrder order) {
        if (!"TRIGGERED".equals(order.getStatus())) return;

        User user = userService.getById(order.getUserId());
        if (user != null && Boolean.TRUE.equals(user.getIsBankrupt())) {
            orderMapper.casUpdateStatus(order.getId(), "TRIGGERED", "CANCELLED");
            return;
        }

        BigDecimal executePrice = order.getFilledPrice();
        if (executePrice == null) return;

        if (order.getOrderSide().startsWith("OPEN")) {
            processTriggeredOpenOrder(order, executePrice);
        } else if (order.getOrderSide().startsWith("INCREASE")) {
            processTriggeredIncreaseOrder(order, executePrice);
        } else {
            processTriggeredCloseOrder(order, executePrice);
        }
    }

    private void processTriggeredOpenOrder(FuturesOrder order, BigDecimal executePrice) {
        BigDecimal quantity = order.getQuantity();
        BigDecimal positionValue = executePrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(order.getLeverage()), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false);
        BigDecimal actualCost = margin.add(commission);

        BigDecimal frozenAmount = order.getFrozenAmount();
        userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (actualCost.compareTo(frozenAmount) < 0) {
            BigDecimal refund = frozenAmount.subtract(actualCost);
            userMapper.atomicUpdateBalance(order.getUserId(), refund);
        }

        FuturesPosition position = new FuturesPosition();
        position.setUserId(order.getUserId());
        position.setSymbol(order.getSymbol());
        position.setSide(order.getOrderSide().contains("LONG") ? "LONG" : "SHORT");
        position.setLeverage(order.getLeverage());
        position.setQuantity(quantity);
        position.setEntryPrice(executePrice);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setStatus("OPEN");

        position.setStopLosses(order.getStopLosses());
        position.setTakeProfits(order.getTakeProfits());

        positionMapper.insert(position);

        orderMapper.casUpdateToFilled(order.getId(), executePrice, positionValue, commission, margin, null);

        positionIndexService.registerPositionIndex(position);

        log.info("futures限价开仓成交 orderId={} price={} margin={}", order.getId(), executePrice, margin);
    }

    private void processTriggeredIncreaseOrder(FuturesOrder order, BigDecimal executePrice) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            orderMapper.casUpdateStatus(order.getId(), "TRIGGERED", "CANCELLED");
            BigDecimal frozenAmount = order.getFrozenAmount();
            userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
            userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount);
            return;
        }

        BigDecimal addQty = order.getQuantity();
        int leverage = position.getLeverage();
        BigDecimal addValue = executePrice.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false);
        BigDecimal actualCost = addMargin.add(commission);

        BigDecimal frozenAmount = order.getFrozenAmount();
        userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (actualCost.compareTo(frozenAmount) < 0) {
            userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount.subtract(actualCost));
        }

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(executePrice.multiply(addQty))
                .divide(newQty, 2, RoundingMode.HALF_UP);

        positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);

        BigDecimal newMargin = position.getMargin().add(addMargin);
        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), newEntryPrice, newMargin, newQty);
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        orderMapper.casUpdateToFilled(order.getId(), executePrice, addValue, commission, addMargin, null);

        log.info("futures限价加仓成交 orderId={} posId={} price={} addMargin={}", order.getId(), position.getId(), executePrice, addMargin);
    }

    private void processTriggeredCloseOrder(FuturesOrder order, BigDecimal executePrice) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            orderMapper.casUpdateStatus(order.getId(), "TRIGGERED", "CANCELLED");
            return;
        }

        BigDecimal closeQty = order.getQuantity();
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), executePrice, closeQty);
        BigDecimal closeValue = executePrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            positionMapper.casClosePosition(position.getId(), "CLOSED", executePrice, pnl);

            positionIndexService.unregisterAll(position);
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(closeQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            positionMapper.atomicPartialClose(position.getId(), closeQty, marginReturn);
        }

        userMapper.atomicUpdateBalance(order.getUserId(), returnAmount);
        orderMapper.casUpdateToFilled(order.getId(), executePrice, closeValue, commission, null, pnl);

        log.info("futures限价平仓成交 orderId={} price={} pnl={}", order.getId(), executePrice, pnl);
    }

    // ==================== 恢复限价单 ====================

    @Override
    public void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh) {
        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        var openLongHits = cacheService.zRangeByScoreWithScores(openLongKey, periodLow.doubleValue(), Double.MAX_VALUE);
        var openShortHits = cacheService.zRangeByScoreWithScores(openShortKey, 0, periodHigh.doubleValue());
        var closeLongHits = cacheService.zRangeByScoreWithScores(closeLongKey, 0, periodHigh.doubleValue());
        var closeShortHits = cacheService.zRangeByScoreWithScores(closeShortKey, periodLow.doubleValue(), Double.MAX_VALUE);

        int count = 0;
        count += recoverHits(openLongKey, openLongHits);
        count += recoverHits(openShortKey, openShortHits);
        count += recoverHits(closeLongKey, closeLongHits);
        count += recoverHits(closeShortKey, closeShortHits);

        if (count > 0) {
            log.info("futures恢复触发限价单 symbol={} low={} high={} 共{}个", symbol, periodLow, periodHigh, count);
        }
    }

    private int recoverHits(String key, Set<ZSetOperations.TypedTuple<String>> hits) {
        if (hits == null || hits.isEmpty()) return 0;
        cacheService.zRemove(key, hits.stream().map(ZSetOperations.TypedTuple::getValue).toArray());
        for (var tuple : hits) {
            BigDecimal limitPrice = BigDecimal.valueOf(tuple.getScore());
            Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(Objects.requireNonNull(tuple.getValue())), limitPrice));
        }
        return hits.size();
    }

    // ==================== 过期限价单处理 ====================

    @Override
    public void expireLimitOrders() {
        List<FuturesOrder> expiredOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT")
                .lt(FuturesOrder::getExpireAt, LocalDateTime.now()));
        if (expiredOrders.isEmpty()) return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        Semaphore semaphore = new Semaphore(maxConcurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (FuturesOrder order : expiredOrders) {
                executor.submit(() -> {
                    try {
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) return;
                        try { processExpiredOrder(order); }
                        finally { semaphore.release(); }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }

    private void processExpiredOrder(FuturesOrder order) {
        try {
            SpringUtils.getAopProxy(this).doExpireOrder(order);
        } catch (Exception e) {
            log.error("futures过期订单处理失败 orderId={}", order.getId(), e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doExpireOrder(FuturesOrder order) {
        int affected = orderMapper.casUpdateStatus(order.getId(), "PENDING", "EXPIRED");
        if (affected == 0) return;

        removeFromLimitZSet(order, cacheService);

        if (!order.getOrderSide().startsWith("CLOSE")) {
            userMapper.atomicUnfreezeBalance(order.getUserId(), order.getFrozenAmount());
        }

        log.info("futures限价单过期 orderId={}", order.getId());
    }

    // ==================== 补处理TRIGGERED孤儿单 ====================

    @Override
    public void executeTriggeredOrders() {
        List<FuturesOrder> triggeredOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "TRIGGERED")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        if (triggeredOrders.isEmpty()) return;

        for (FuturesOrder order : triggeredOrders) {
            Thread.startVirtualThread(() -> {
                try {
                    SpringUtils.getAopProxy(this).processTriggeredOrder(order);
                } catch (Exception e) {
                    log.error("补处理TRIGGERED订单失败 orderId={}", order.getId(), e);
                }
            });
        }
        log.info("补处理TRIGGERED孤儿单 共{}个", triggeredOrders.size());
    }

    // ==================== 资金费率扣除 ====================

    @Override
    public void chargeFundingFeeAll() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));
        if (positions.isEmpty()) return;

        int successCount = 0;
        int failCount = 0;

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
                BigDecimal fee = entryValue.multiply(tradingConfig.getFutures().getFundingRate())
                        .setScale(2, RoundingMode.HALF_UP);

                int affected = userMapper.atomicUpdateBalance(pos.getUserId(), fee.negate());
                if (affected > 0) {
                    positionMapper.atomicAddFundingFeeTotal(pos.getId(), fee);
                    successCount++;
                    continue;
                }

                affected = positionMapper.atomicDeductFundingFee(pos.getId(), fee);
                if (affected > 0) {
                    BigDecimal newMargin = pos.getMargin().subtract(fee);
                    BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(pos.getSide(), pos.getEntryPrice(), newMargin, pos.getQuantity());
                    positionIndexService.updateLiquidationPrice(pos.getId(), pos.getSymbol(), pos.getSide(), liqPrice);
                    successCount++;
                    continue;
                }

                affected = positionMapper.atomicDeductFundingFeePartial(pos.getId());
                if (affected > 0) {
                    BigDecimal mp = getMarkPrice(pos.getSymbol());
                    riskService.checkAndLiquidate(pos.getId(), mp);
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.error("futures扣除资金费率失败 posId={}", pos.getId(), e);
                failCount++;
            }
        }

        log.info("futures资金费率扣除完成 成功{} 失败{}", successCount, failCount);
    }

    // ==================== ZSet索引重建 ====================

    private void rebuildLimitOrderZSets() {
        List<FuturesOrder> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        if (pendingOrders.isEmpty()) return;

        for (FuturesOrder order : pendingOrders) {
            addToLimitZSet(order, cacheService);
        }
        log.info("重建futures限价单ZSet索引 共{}个订单", pendingOrders.size());
    }

    // ==================== 内部工具 ====================

    private BigDecimal getMarkPrice(String symbol) {
        BigDecimal mp = cacheService.getMarkPrice(symbol);
        if (mp != null) return mp;
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }
}
