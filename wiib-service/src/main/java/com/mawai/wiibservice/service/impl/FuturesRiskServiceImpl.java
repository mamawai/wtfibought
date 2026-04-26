package com.mawai.wiibservice.service.impl;

import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
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
import com.mawai.wiibservice.service.TradeAttributionService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.mawai.wiibservice.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesRiskServiceImpl implements FuturesRiskService {

    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final UserMapper userMapper;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final TradeAttributionService tradeAttributionService;

    // ==================== 设置止损 ====================

    @Override
    public void setStopLoss(Long userId, FuturesStopLossRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doSetStopLoss(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doSetStopLoss(Long userId, FuturesStopLossRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        List<FuturesStopLossRequest.StopLossItem> items = request.getStopLosses();
        if (items != null && items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);

        List<FuturesStopLoss> oldSls = position.getStopLosses();
        if (oldSls != null && !oldSls.isEmpty()) {
            positionIndexService.unregisterStopLosses(position.getId(), position.getSymbol(), position.getSide(), oldSls);
        }

        if (items == null || items.isEmpty()) {
            positionMapper.updateStopLosses(position.getId(), null);
            return;
        }

        BigDecimal markPrice = getMarkPrice(position.getSymbol());
        BigDecimal totalQty = BigDecimal.ZERO;
        List<FuturesStopLoss> newList = new ArrayList<>();
        for (FuturesStopLossRequest.StopLossItem item : items) {
            validateSlPrice(item.getPrice(), position.getSide(), markPrice);
            validateQty(item.getQuantity());
            totalQty = totalQty.add(item.getQuantity());
            newList.add(new FuturesStopLoss(genId(), item.getPrice(), item.getQuantity()));
        }
        if (totalQty.compareTo(position.getQuantity()) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);

        positionMapper.updateStopLosses(position.getId(), newList);
        positionIndexService.registerStopLosses(position.getId(), position.getSymbol(), position.getSide(), newList);

        log.info("futures设置止损 userId={} posId={} count={}", userId, request.getPositionId(), newList.size());
    }

    // ==================== 设置止盈 ====================

    @Override
    public void setTakeProfit(Long userId, FuturesTakeProfitRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doSetTakeProfit(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doSetTakeProfit(Long userId, FuturesTakeProfitRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        List<FuturesTakeProfitRequest.TakeProfitItem> items = request.getTakeProfits();
        if (items != null && items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);

        List<FuturesTakeProfit> oldTps = position.getTakeProfits();
        if (oldTps != null && !oldTps.isEmpty()) {
            positionIndexService.unregisterTakeProfits(position.getId(), position.getSymbol(), position.getSide(), oldTps);
        }

        if (items == null || items.isEmpty()) {
            positionMapper.updateTakeProfits(position.getId(), null);
            log.info("futures清空止盈 userId={} posId={}", userId, request.getPositionId());
            return;
        }

        BigDecimal markPrice = getMarkPrice(position.getSymbol());
        BigDecimal totalQty = BigDecimal.ZERO;
        List<FuturesTakeProfit> newList = new ArrayList<>();
        for (FuturesTakeProfitRequest.TakeProfitItem item : items) {
            validateTpPrice(item.getPrice(), position.getSide(), markPrice);
            validateQty(item.getQuantity());
            totalQty = totalQty.add(item.getQuantity());
            newList.add(new FuturesTakeProfit(genId(), item.getPrice(), item.getQuantity()));
        }
        if (totalQty.compareTo(position.getQuantity()) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);

        positionMapper.updateTakeProfits(position.getId(), newList);
        positionIndexService.registerTakeProfits(position.getId(), position.getSymbol(), position.getSide(), newList);

        log.info("futures设置止盈 userId={} posId={} count={}", userId, request.getPositionId(), newList.size());
    }

    // ==================== 强制平仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceClose(Long positionId, BigDecimal price) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, position.getQuantity());
        BigDecimal closeValue = price.multiply(position.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        BigDecimal returnAmount = position.getMargin().add(pnl).subtract(commission);
        returnAmount = returnAmount.max(BigDecimal.ZERO);

        int affected = positionMapper.casClosePosition(positionId, "LIQUIDATED", price, pnl);
        if (affected == 0) return;

        positionIndexService.unregisterAll(position);

        if (returnAmount.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(positionId);
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(position.getQuantity());
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(price);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("LIQUIDATED");
        orderMapper.insert(order);
        tradeAttributionService.recordExit(position, pnl, "UNKNOWN");

        log.warn("futures强制平仓 posId={} userId={} price={} pnl={}", positionId, position.getUserId(), price, pnl);
    }

    // ==================== 检查并强平 ====================

    @Override
    public void checkAndLiquidate(Long positionId, BigDecimal currentPrice) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, position.getQuantity());
        BigDecimal effectiveMargin = position.getMargin().add(unrealizedPnl);
        BigDecimal positionValue = currentPrice.multiply(position.getQuantity());
        BigDecimal maintenanceMargin = positionValue.multiply(
                tradingConfig.getFutures().maintenanceMarginRateForLeverage(position.getLeverage()));

        if (effectiveMargin.compareTo(maintenanceMargin) <= 0) {
            SpringUtils.getAopProxy(this).forceClose(positionId, currentPrice);
        }
    }

    // ==================== 批量止损触发 ====================

    @Override
    public void batchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doBatchTriggerStopLoss(positionId, slIds, price);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doBatchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) return;

        List<FuturesStopLoss> sls = position.getStopLosses();
        if (sls == null) return;

        Set<String> idSet = new HashSet<>(slIds);
        BigDecimal totalCloseQty = BigDecimal.ZERO;
        for (FuturesStopLoss sl : sls) {
            if (idSet.contains(sl.getId())) {
                totalCloseQty = totalCloseQty.add(sl.getQuantity());
            }
        }
        if (totalCloseQty.compareTo(BigDecimal.ZERO) == 0) return;

        totalCloseQty = totalCloseQty.min(position.getQuantity());
        boolean isFullClose = totalCloseQty.compareTo(position.getQuantity()) >= 0;

        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, totalCloseQty);
        BigDecimal closeValue = price.multiply(totalCloseQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission).max(BigDecimal.ZERO);
            int affected = positionMapper.casClosePosition(positionId, "CLOSED", price, pnl);
            if (affected == 0) return;
            positionIndexService.unregisterAll(position);
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(totalCloseQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission).max(BigDecimal.ZERO);
            int affected = positionMapper.atomicPartialClose(positionId, totalCloseQty, marginReturn);
            if (affected == 0) return;

            sls.removeIf(s -> idSet.contains(s.getId()));
            positionMapper.updateStopLosses(positionId, sls);
            FuturesPosition updated = positionMapper.selectById(positionId);
            if (updated != null && "OPEN".equals(updated.getStatus())) {
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        updated.getSide(), updated.getEntryPrice(), updated.getMargin(), updated.getQuantity(),
                        updated.getLeverage());
                positionIndexService.updateLiquidationPrice(updated.getId(), updated.getSymbol(), updated.getSide(), liqPrice);
            }
        }

        if (returnAmount.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(positionId);
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(totalCloseQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(price);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("STOP_LOSS");
        orderMapper.insert(order);
        if (isFullClose) {
            tradeAttributionService.recordExit(position, pnl, "SL");
        }

        log.info("futures批量止损平仓 posId={} slIds={} qty={} price={} pnl={}", positionId, slIds, totalCloseQty, price, pnl);
    }

    // ==================== 批量止盈触发 ====================

    @Override
    public void batchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doBatchTriggerTakeProfit(positionId, tpIds, price);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doBatchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) return;

        List<FuturesTakeProfit> tps = position.getTakeProfits();
        if (tps == null) return;

        Set<String> idSet = new HashSet<>(tpIds);
        BigDecimal totalCloseQty = BigDecimal.ZERO;
        for (FuturesTakeProfit tp : tps) {
            if (idSet.contains(tp.getId())) {
                totalCloseQty = totalCloseQty.add(tp.getQuantity());
            }
        }
        if (totalCloseQty.compareTo(BigDecimal.ZERO) == 0) return;

        totalCloseQty = totalCloseQty.min(position.getQuantity());
        boolean isFullClose = totalCloseQty.compareTo(position.getQuantity()) >= 0;

        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, totalCloseQty);
        BigDecimal closeValue = price.multiply(totalCloseQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission).max(BigDecimal.ZERO);
            int affected = positionMapper.casClosePosition(positionId, "CLOSED", price, pnl);
            if (affected == 0) return;
            positionIndexService.unregisterAll(position);
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(totalCloseQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission).max(BigDecimal.ZERO);
            int affected = positionMapper.atomicPartialClose(positionId, totalCloseQty, marginReturn);
            if (affected == 0) return;

            tps.removeIf(t -> idSet.contains(t.getId()));
            positionMapper.updateTakeProfits(positionId, tps);
            FuturesPosition updated = positionMapper.selectById(positionId);
            if (updated != null && "OPEN".equals(updated.getStatus())) {
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        updated.getSide(), updated.getEntryPrice(), updated.getMargin(), updated.getQuantity(),
                        updated.getLeverage());
                positionIndexService.updateLiquidationPrice(updated.getId(), updated.getSymbol(), updated.getSide(), liqPrice);
            }
        }

        if (returnAmount.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(positionId);
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(totalCloseQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(price);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("TAKE_PROFIT");
        orderMapper.insert(order);
        if (isFullClose) {
            tradeAttributionService.recordExit(position, pnl, "TP");
        }

        log.info("futures批量止盈平仓 posId={} tpIds={} qty={} price={} pnl={}", positionId, tpIds, totalCloseQty, price, pnl);
    }

    // ==================== 内部工具 ====================

    private BigDecimal getMarkPrice(String symbol) {
        BigDecimal mp = cacheService.getMarkPrice(symbol);
        if (mp != null) return mp;
        BigDecimal price = cacheService.getCryptoPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    private FuturesPosition getUserPosition(Long userId, Long positionId) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !position.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);
        }
        if (!"OPEN".equals(position.getStatus())) {
            throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        }
        return position;
    }
}
