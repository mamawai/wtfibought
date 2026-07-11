package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

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
    private final FuturesLeverageBracketRegistry bracketRegistry;

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

        // 先完成全部校验再动 Redis 索引：校验抛异常时 DB 回滚而 Redis 删除不会回滚，
        // 顺序反了会留下"DB 有旧止损但索引已删、永不触发"的不一致
        List<FuturesStopLoss> newList = null;
        if (items != null && !items.isEmpty()) {
            BigDecimal markPrice = getMarkPrice(position.getSymbol());
            BigDecimal totalQty = BigDecimal.ZERO;
            newList = new ArrayList<>();
            for (FuturesStopLossRequest.StopLossItem item : items) {
                validateSlPrice(item.getPrice(), position.getSide(), markPrice);
                validateQty(item.getQuantity());
                totalQty = totalQty.add(item.getQuantity());
                newList.add(new FuturesStopLoss(genId(), item.getPrice(), item.getQuantity()));
            }
            if (totalQty.compareTo(position.getQuantity()) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }

        List<FuturesStopLoss> oldSls = position.getStopLosses();
        if (oldSls != null && !oldSls.isEmpty()) {
            positionIndexService.unregisterStopLosses(position.getId(), position.getSymbol(), position.getSide(), oldSls);
        }

        if (newList == null) {
            positionMapper.updateStopLosses(position.getId(), null);
            return;
        }

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

        // 同 doSetStopLoss：先校验后动 Redis 索引，防止 DB 回滚后索引已删的不一致
        List<FuturesTakeProfit> newList = null;
        if (items != null && !items.isEmpty()) {
            BigDecimal markPrice = getMarkPrice(position.getSymbol());
            BigDecimal totalQty = BigDecimal.ZERO;
            newList = new ArrayList<>();
            for (FuturesTakeProfitRequest.TakeProfitItem item : items) {
                validateTpPrice(item.getPrice(), position.getSide(), markPrice);
                validateQty(item.getQuantity());
                totalQty = totalQty.add(item.getQuantity());
                newList.add(new FuturesTakeProfit(genId(), item.getPrice(), item.getQuantity()));
            }
            if (totalQty.compareTo(position.getQuantity()) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }

        List<FuturesTakeProfit> oldTps = position.getTakeProfits();
        if (oldTps != null && !oldTps.isEmpty()) {
            positionIndexService.unregisterTakeProfits(position.getId(), position.getSymbol(), position.getSide(), oldTps);
        }

        if (newList == null) {
            positionMapper.updateTakeProfits(position.getId(), null);
            log.info("futures清空止盈 userId={} posId={}", userId, request.getPositionId());
            return;
        }

        positionMapper.updateTakeProfits(position.getId(), newList);
        positionIndexService.registerTakeProfits(position.getId(), position.getSymbol(), position.getSide(), newList);

        log.info("futures设置止盈 userId={} posId={} count={}", userId, request.getPositionId(), newList.size());
    }

    // ==================== 强制平仓 ====================

    @Override
    public void forceClose(Long positionId, BigDecimal price) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doForceClose(positionId, price);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doForceClose(Long positionId, BigDecimal price) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        forceCloseInCurrentTransaction(position, price);
    }

    private void forceCloseInCurrentTransaction(FuturesPosition position, BigDecimal price) {
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, position.getQuantity());
        BigDecimal closeValue = price.multiply(position.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        BigDecimal returnAmount = position.getMargin().add(pnl).subtract(commission);
        returnAmount = returnAmount.max(BigDecimal.ZERO);

        int affected = positionMapper.casClosePosition(position.getId(), "LIQUIDATED", price, pnl);
        if (affected == 0) return;

        positionIndexService.unregisterAll(position);

        if (returnAmount.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(position.getId());
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

        log.warn("futures强制平仓 posId={} userId={} price={} pnl={}", position.getId(), position.getUserId(), price, pnl);
    }

    // ==================== 检查并强平 ====================

    @Override
    public void checkAndLiquidate(Long positionId, BigDecimal currentPrice) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doCheckAndLiquidate(positionId, currentPrice);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doCheckAndLiquidate(Long positionId, BigDecimal currentPrice) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, position.getQuantity());
        BigDecimal effectiveMargin = position.getMargin().add(unrealizedPnl);
        BigDecimal positionValue = currentPrice.multiply(position.getQuantity());
        BigDecimal maintenanceMargin;
        try {
            maintenanceMargin = bracketRegistry.calcMaintenanceMargin(position.getSymbol(), positionValue);
        } catch (BizException e) {
            // 未配置档位的 symbol 跳过本次强平判定，避免吞错导致仓位卡死，运维需关注此告警
            log.error("强平判定跳过 posId={} symbol={} reason={}", positionId, position.getSymbol(), e.getMessage());
            return;
        }

        if (effectiveMargin.compareTo(maintenanceMargin) <= 0) {
            forceCloseInCurrentTransaction(position, currentPrice);
        }
    }

    // ==================== 批量止损/止盈触发（共用平仓流程） ====================

    @Override
    public void batchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doBatchTrigger(positionId, slIds, price, true);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Override
    public void batchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price) {
        String lockKey = "futures:pos:" + positionId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doBatchTrigger(positionId, tpIds, price, false);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * SL/TP 批量触发平仓的单一流程（曾是两段逐行镜像的 80 行，改平仓逻辑只改一半的事故温床）。
     * isStopLoss 只决定三件事：读哪张保护单列表、部分平仓后剩余列表写回哪个字段、订单状态字面量。
     */
    @Transactional(rollbackFor = Exception.class)
    protected void doBatchTrigger(Long positionId, Collection<String> ids, BigDecimal price, boolean isStopLoss) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) return;

        List<FuturesStopLoss> sls = isStopLoss ? position.getStopLosses() : null;
        List<FuturesTakeProfit> tps = isStopLoss ? null : position.getTakeProfits();
        if (isStopLoss ? sls == null : tps == null) return;

        Set<String> idSet = new HashSet<>(ids);
        BigDecimal totalCloseQty = BigDecimal.ZERO;
        if (isStopLoss) {
            for (FuturesStopLoss sl : sls) {
                if (idSet.contains(sl.getId())) totalCloseQty = totalCloseQty.add(sl.getQuantity());
            }
        } else {
            for (FuturesTakeProfit tp : tps) {
                if (idSet.contains(tp.getId())) totalCloseQty = totalCloseQty.add(tp.getQuantity());
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

            if (isStopLoss) {
                sls.removeIf(s -> idSet.contains(s.getId()));
                positionMapper.updateStopLosses(positionId, sls);
            } else {
                tps.removeIf(t -> idSet.contains(t.getId()));
                positionMapper.updateTakeProfits(positionId, tps);
            }
            FuturesPosition updated = positionMapper.selectById(positionId);
            if (updated != null && "OPEN".equals(updated.getStatus())) {
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        updated.getSymbol(), updated.getSide(), updated.getEntryPrice(), updated.getMargin(), updated.getQuantity());
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
        order.setStatus(isStopLoss ? "STOP_LOSS" : "TAKE_PROFIT");
        orderMapper.insert(order);

        log.info("futures批量{}平仓 posId={} ids={} qty={} price={} pnl={}",
                isStopLoss ? "止损" : "止盈", positionId, ids, totalCloseQty, price, pnl);
    }

    // ==================== 内部工具 ====================

    private BigDecimal getMarkPrice(String symbol) {
        return FuturesHelper.markPrice(cacheService, symbol);
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
