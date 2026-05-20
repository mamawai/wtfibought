package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesSettlementService;
import com.mawai.wiibservice.service.TradeAttributionService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
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

    private static final int PRICE_SCALE = 8;

    private final UserService userService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesRiskService riskService;
    private final TradeAttributionService tradeAttributionService;
    private final RedisLockUtil redisLockUtil;

    protected record FundingFeeChargeResult(boolean success, boolean checkLiquidation) {}

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

        Map<String, Double> openLongHits = cacheService.zRangeByScoreAndRemove(openLongKey, price.doubleValue(), Double.MAX_VALUE);
        Map<String, Double> openShortHits = cacheService.zRangeByScoreAndRemove(openShortKey, 0, price.doubleValue());
        Map<String, Double> closeLongHits = cacheService.zRangeByScoreAndRemove(closeLongKey, 0, price.doubleValue());
        Map<String, Double> closeShortHits = cacheService.zRangeByScoreAndRemove(closeShortKey, price.doubleValue(), Double.MAX_VALUE);

        if (openLongHits != null && !openLongHits.isEmpty()) {
            for (String id : openLongHits.keySet()) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (openShortHits != null && !openShortHits.isEmpty()) {
            for (String id : openShortHits.keySet()) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeLongHits != null && !closeLongHits.isEmpty()) {
            for (String id : closeLongHits.keySet()) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeShortHits != null && !closeShortHits.isEmpty()) {
            for (String id : closeShortHits.keySet()) {
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

    protected void processTriggeredOrder(FuturesOrder order) {
        if (order == null || !"TRIGGERED".equals(order.getStatus())) return;

        if (!order.getOrderSide().startsWith("OPEN")) {
            String lockKey = "futures:pos:" + order.getPositionId();
            String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
            if (lockValue == null) {
                log.info("futures限价成交跳过，仓位处理中 orderId={} posId={}", order.getId(), order.getPositionId());
                return;
            }
            try {
                SpringUtils.getAopProxy(this).doProcessTriggeredOrder(order);
            } finally {
                redisLockUtil.unlock(lockKey, lockValue);
            }
            return;
        }

        SpringUtils.getAopProxy(this).doProcessTriggeredOrder(order);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doProcessTriggeredOrder(FuturesOrder order) {
        if (!"TRIGGERED".equals(order.getStatus())) return;

        // 先抢占订单处理权，再做资金/仓位副作用；否则实时触发和补偿扫描可能重复成交。
        int processing = orderMapper.casMarkProcessing(order.getId());
        if (processing == 0) return;
        order.setStatus("PROCESSING");

        User user = userService.getById(order.getUserId());
        if (user != null && Boolean.TRUE.equals(user.getIsBankrupt())) {
            orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
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
        if (!isLeverageAllowed(order.getSymbol(), order.getLeverage(), positionValue)) {
            cancelTriggeredOrderAndRefund(order, "leverage_not_allowed");
            return;
        }
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(order.getLeverage()), 2, RoundingMode.CEILING);
        boolean isTaker = isLimitTaker(order, false);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, isTaker);
        BigDecimal actualCost = margin.add(commission);

        BigDecimal frozenAmount = order.getFrozenAmount();
        int deducted = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (deducted == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
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

        int filled = orderMapper.casUpdateToFilled(order.getId(), executePrice, positionValue, commission, margin, null);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        positionIndexService.registerPositionIndex(position);

        log.info("futures限价开仓成交 orderId={} price={} feeType={} margin={}",
                order.getId(), executePrice, isTaker ? "TAKER" : "MAKER", margin);
    }

    private void processTriggeredIncreaseOrder(FuturesOrder order, BigDecimal executePrice) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            cancelTriggeredOrderAndRefund(order, "position_closed");
            return;
        }

        BigDecimal addQty = order.getQuantity();
        int leverage = position.getLeverage();
        BigDecimal newNotional = executePrice.multiply(position.getQuantity().add(addQty));
        if (!isLeverageAllowed(order.getSymbol(), leverage, newNotional)) {
            cancelTriggeredOrderAndRefund(order, "leverage_not_allowed");
            return;
        }
        BigDecimal addValue = executePrice.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        boolean isTaker = isLimitTaker(order, false);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, isTaker);
        BigDecimal actualCost = addMargin.add(commission);

        BigDecimal frozenAmount = order.getFrozenAmount();
        int deducted = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (deducted == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        if (actualCost.compareTo(frozenAmount) < 0) {
            userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount.subtract(actualCost));
        }

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(executePrice.multiply(addQty))
                .divide(newQty, PRICE_SCALE, RoundingMode.HALF_UP);

        positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);

        BigDecimal newMargin = position.getMargin().add(addMargin);
        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(), newEntryPrice, newMargin,
                newQty);
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        int filled = orderMapper.casUpdateToFilled(order.getId(), executePrice, addValue, commission, addMargin, null);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        log.info("futures限价加仓成交 orderId={} posId={} price={} feeType={} addMargin={}",
                order.getId(), position.getId(), executePrice, isTaker ? "TAKER" : "MAKER", addMargin);
    }

    private void processTriggeredCloseOrder(FuturesOrder order, BigDecimal executePrice) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
            return;
        }

        BigDecimal closeQty = order.getQuantity();
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), executePrice, closeQty);
        BigDecimal closeValue = executePrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        boolean isTaker = isLimitTaker(order, true);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, isTaker);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", executePrice, pnl);
            if (affected == 0) {
                // 仓位已被并发平掉，订单取消，禁止重复返款
                orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
                return;
            }

            positionIndexService.unregisterAll(position);
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(closeQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            int affected = positionMapper.atomicPartialClose(position.getId(), closeQty, marginReturn);
            if (affected == 0) {
                // 可平数量不足或已关闭，订单取消，禁止重复返款
                orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
                return;
            }

            // 限价部分平仓成交后 qty/margin 已变，刷新强平索引。
            BigDecimal newQty = position.getQuantity().subtract(closeQty);
            BigDecimal newMargin = position.getMargin().subtract(marginReturn);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                    position.getSymbol(), position.getSide(), position.getEntryPrice(), newMargin, newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }

        userMapper.atomicUpdateBalance(order.getUserId(), returnAmount);
        int filled = orderMapper.casUpdateToFilled(order.getId(), executePrice, closeValue, commission, null, pnl);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        if (isFullClose) {
            tradeAttributionService.recordExit(position, pnl, "UNKNOWN");
        }

        log.info("futures限价平仓成交 orderId={} price={} feeType={} pnl={}",
                order.getId(), executePrice, isTaker ? "TAKER" : "MAKER", pnl);
    }

    private boolean isLeverageAllowed(String symbol, int leverage, BigDecimal notional) {
        int maxAllowed = bracketRegistry.getEffectiveMaxLeverage(symbol, notional);
        return leverage <= maxAllowed;
    }

    private void cancelTriggeredOrderAndRefund(FuturesOrder order, String reason) {
        int affected = orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
        if (affected == 0) return;

        BigDecimal frozenAmount = order.getFrozenAmount();
        if (frozenAmount != null && frozenAmount.compareTo(BigDecimal.ZERO) > 0) {
            int deducted = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
            // 异常状态：cancel 前 frozen 必然存在，扣不到说明数据被并发改动，整事务回滚避免余额凭空增加
            if (deducted == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount);
        }
        log.info("futures限价单触发后取消 orderId={} reason={} refund={}",
                order.getId(), reason, frozenAmount);
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
                boolean success = SpringUtils.getAopProxy(this).chargeFundingFeeOne(pos);
                if (success) {
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

    protected boolean chargeFundingFeeOne(FuturesPosition pos) {
        String lockKey = "futures:pos:" + pos.getId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) {
            log.info("futures资金费跳过，仓位处理中 posId={}", pos.getId());
            return false;
        }

        FundingFeeChargeResult result;
        try {
            result = SpringUtils.getAopProxy(this).doChargeFundingFeeOne(pos.getId());
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }

        if (result.checkLiquidation()) {
            FuturesPosition latest = positionMapper.selectById(pos.getId());
            if (latest != null && "OPEN".equals(latest.getStatus())) {
                riskService.checkAndLiquidate(latest.getId(), getMarkPrice(latest.getSymbol()));
            }
        }
        return result.success();
    }

    @Transactional(rollbackFor = Exception.class)
    protected FundingFeeChargeResult doChargeFundingFeeOne(Long positionId) {
        FuturesPosition pos = positionMapper.selectById(positionId);
        if (pos == null || !"OPEN".equals(pos.getStatus())) {
            return new FundingFeeChargeResult(false, false);
        }

        BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
        BigDecimal fee = entryValue.multiply(tradingConfig.getFutures().getFundingRate())
                .setScale(2, RoundingMode.HALF_UP);

        int affected = userMapper.atomicUpdateBalance(pos.getUserId(), fee.negate());
        if (affected > 0) {
            int added = positionMapper.atomicAddFundingFeeTotal(pos.getId(), fee);
            if (added == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            return new FundingFeeChargeResult(true, false);
        }

        affected = positionMapper.atomicDeductFundingFee(pos.getId(), fee);
        if (affected > 0) {
            BigDecimal newMargin = pos.getMargin().subtract(fee);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(pos.getSymbol(), pos.getSide(), pos.getEntryPrice(),
                    newMargin, pos.getQuantity());
            positionIndexService.updateLiquidationPrice(pos.getId(), pos.getSymbol(), pos.getSide(), liqPrice);
            return new FundingFeeChargeResult(true, false);
        }

        affected = positionMapper.atomicDeductFundingFeePartial(pos.getId());
        if (affected > 0) {
            FuturesPosition updated = positionMapper.selectById(pos.getId());
            if (updated != null && "OPEN".equals(updated.getStatus())) {
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        updated.getSymbol(), updated.getSide(), updated.getEntryPrice(),
                        updated.getMargin(), updated.getQuantity());
                positionIndexService.updateLiquidationPrice(updated.getId(), updated.getSymbol(), updated.getSide(), liqPrice);
            }
            return new FundingFeeChargeResult(true, true);
        }
        return new FundingFeeChargeResult(false, false);
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

    private boolean isLimitTaker(FuturesOrder order, boolean isClose) {
        if (!"LIMIT".equals(order.getOrderType())) return true;
        if (order.getCommission() == null || order.getLimitPrice() == null || order.getQuantity() == null) {
            // 兼容旧挂单：历史限价单没有预估手续费，按maker处理
            return false;
        }

        BigDecimal baseAmount = order.getLimitPrice().multiply(order.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal makerCommission = tradingConfig.calculateFuturesCommission(baseAmount, isClose, false);
        BigDecimal takerCommission = tradingConfig.calculateFuturesCommission(baseAmount, isClose, true);
        if (makerCommission.compareTo(takerCommission) == 0) {
            return false;
        }
        return order.getCommission().compareTo(takerCommission) == 0;
    }
}
