package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.AfterCommit;
import com.mawai.wiibsim.util.FairLockRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.locks.Lock;

import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_CLOSE_SETTLE;
import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_LIQUIDATION_RETURN;
import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesRiskServiceImpl implements FuturesRiskService {

    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final UserMapper userMapper;
    private final TradingConfig tradingConfig;
    private final FairLockRegistry lockRegistry;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;
    private final TradeNotificationService tradeNotificationService;
    private final TransactionTemplate transactionTemplate;
    private final LedgerTx ledgerTx;
    private final TradeFilterRegistry filterRegistry;
    private final BankruptcyService bankruptcyService;
    private final BookFillPricer fillPricer;

    // ==================== 设置止损 ====================

    @Override
    public void setStopLoss(Long userId, FuturesStopLossRequest request) {
        Lock lock = lockRegistry.tryLockAsUser("futures:pos:" + request.getPositionId());
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            transactionTemplate.executeWithoutResult(_ -> doSetStopLoss(userId, request));
        } finally {
            lock.unlock();
        }
    }

    void doSetStopLoss(Long userId, FuturesStopLossRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        List<FuturesStopLossRequest.StopLossItem> items = request.getStopLosses();
        if (items != null && items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);

        // 先完成全部校验再动 Redis 索引：校验抛异常时 DB 回滚而 Redis 删除不会回滚，
        // 顺序反了会留下"DB 有旧止损但索引已删、永不触发"的不一致
        List<FuturesStopLoss> newList = null;
        if (items != null && !items.isEmpty()) {
            // 止损看成交价触发，按最新成交价校验方向
            BigDecimal lastPrice = latestFuturesPrice(cacheService, position.getSymbol());
            BigDecimal totalQty = BigDecimal.ZERO;
            newList = new ArrayList<>();
            for (FuturesStopLossRequest.StopLossItem item : items) {
                validateSlPrice(item.getPrice(), position.getSide(), lastPrice);
                validateQty(item.getQuantity());
                totalQty = totalQty.add(item.getQuantity());
                FuturesStopLoss sl = new FuturesStopLoss(genId(), item.getPrice(), item.getQuantity());
                sl.setCreatedAt(System.currentTimeMillis());
                newList.add(sl);
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
        Lock lock = lockRegistry.tryLockAsUser("futures:pos:" + request.getPositionId());
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            transactionTemplate.executeWithoutResult(_ -> doSetTakeProfit(userId, request));
        } finally {
            lock.unlock();
        }
    }

    void doSetTakeProfit(Long userId, FuturesTakeProfitRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        List<FuturesTakeProfitRequest.TakeProfitItem> items = request.getTakeProfits();
        if (items != null && items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);

        // 同 doSetStopLoss：先校验后动 Redis 索引，防止 DB 回滚后索引已删的不一致
        List<FuturesTakeProfit> newList = null;
        if (items != null && !items.isEmpty()) {
            BigDecimal lastPrice = latestFuturesPrice(cacheService, position.getSymbol());
            BigDecimal totalQty = BigDecimal.ZERO;
            newList = new ArrayList<>();
            for (FuturesTakeProfitRequest.TakeProfitItem item : items) {
                validateTpPrice(item.getPrice(), position.getSide(), lastPrice);
                validateQty(item.getQuantity());
                totalQty = totalQty.add(item.getQuantity());
                FuturesTakeProfit tp = new FuturesTakeProfit(genId(), item.getPrice(), item.getQuantity());
                tp.setCreatedAt(System.currentTimeMillis());
                newList.add(tp);
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
    public void forceClose(Long positionId, BigDecimal price, long priceAt) {
        Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + positionId);
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            ledgerTx.run(FUTURES_LIQUIDATION_RETURN, () -> doForceClose(positionId, price, priceAt));
        } finally {
            lock.unlock();
        }
    }

    void doForceClose(Long positionId, BigDecimal price, long priceAt) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }
        LedgerCtx.symbol(position.getSymbol());

        // 锁内按传入价复核，不满足就不平，按当前保证金/数量把强平索引放回去
        if (!reachesLiquidation(position, price)) {
            positionIndexService.registerLiquidation(position);
            return;
        }
        forceCloseInCurrentTransaction(position, price, priceAt);
    }

    /** 逐仓强平条件：保证金 + 浮盈亏 ≤ 维持保证金。档位没配抛 FUTURES_SYMBOL_NOT_CONFIGURED */
    private boolean reachesLiquidation(FuturesPosition position, BigDecimal price) {
        BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, position.getQuantity());
        BigDecimal maintenanceMargin = bracketRegistry.calcMaintenanceMargin(position.getSymbol(),
                price.multiply(position.getQuantity()));
        return position.getMargin().add(unrealizedPnl).compareTo(maintenanceMargin) <= 0;
    }

    /**
     * triggerPrice 是判强平用的标记价，priceAt 是它出现的时刻；成交按盘口扫，没盘口或是旧价用它。
     * 返回强平单，CAS 没抢到（别的路径先平了）返回 null
     */
    private FuturesOrder forceCloseInCurrentTransaction(FuturesPosition position, BigDecimal triggerPrice, long priceAt) {
        BigDecimal qty = position.getQuantity();
        BigDecimal price = fillPricer.futures(position.getSymbol(), "SHORT".equals(position.getSide()), qty,
                triggerPrice, priceAt);
        CloseFill fill = closeFill(position, price, qty, position.getMargin(), tradingConfig, true);
        BigDecimal closeValue = fill.value(qty);
        BigDecimal left = fill.left(position.getMargin());
        // 清算费从剩下的钱里扣，扣到 0 为止；破产仓剩的就是 0，等于不收
        BigDecimal liqFee = filterRegistry.liquidationFee(position.getSymbol(), closeValue).min(left);
        BigDecimal returnAmount = left.subtract(liqFee);

        int affected = positionMapper.casClosePosition(position.getId(), "LIQUIDATED", fill.price(), fill.pnl());
        if (affected == 0) return null;

        positionIndexService.unregisterAll(position);
        cancelCloseOrders(position.getId(), orderMapper, cacheService);

        if (returnAmount.signum() > 0) {
            // 这笔是强平返还（手动平仓转成强平时外层是平仓结算的类型）
            LedgerCtx.mark(FUTURES_LIQUIDATION_RETURN);
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(qty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(fill.price());
        order.setFilledAmount(closeValue);
        order.setCommission(fill.commission());
        order.setLiquidationFee(liqFee);
        order.setRealizedPnl(fill.pnl());
        order.setStatus("LIQUIDATED");
        orderMapper.insert(order);

        log.warn("futures强制平仓 posId={} userId={} price={} pnl={} liqFee={}",
                position.getId(), position.getUserId(), fill.price(), fill.pnl(), liqFee);

        // 挂在 CAS 之后：巡检和价格触发两条路径都汇到这儿，重复调用被 casClosePosition 挡掉了，不会重复通知。
        // 通知里的盈亏是扣完手续费和清算费的净额，跟全仓爆仓那条一个口径
        tradeNotificationService.liquidation(position, fill.price(),
                fill.pnl().subtract(fill.commission()).subtract(liqFee));
        // 强平提交了再判破产：判破产出错不能把这次强平一起回滚
        AfterCommit.run(() -> bankruptcyService.bankruptIfNothingLeft(position.getUserId()));
        return order;
    }

    @Override
    public FuturesOrder liquidateIfReached(FuturesPosition position, BigDecimal markPrice) {
        if (position.isCross()) return null;
        try {
            if (!reachesLiquidation(position, markPrice)) return null;
        } catch (BizException e) {
            // 没配档位判不了强平，按普通平仓走（下架的币要能平掉）
            return null;
        }
        return forceCloseInCurrentTransaction(position, markPrice, System.currentTimeMillis());
    }

    // ==================== 检查并强平 ====================

    @Override
    public void checkAndLiquidate(Long positionId, BigDecimal currentPrice) {
        Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + positionId);
        if (lock == null) {
            log.warn("futures强平复核获锁超时，本次不查 posId={}", positionId);
            return;
        }
        try {
            ledgerTx.run(FUTURES_LIQUIDATION_RETURN, () -> doCheckAndLiquidate(positionId, currentPrice));
        } finally {
            lock.unlock();
        }
    }

    void doCheckAndLiquidate(Long positionId, BigDecimal currentPrice) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }
        LedgerCtx.symbol(position.getSymbol());

        boolean hit;
        try {
            hit = reachesLiquidation(position, currentPrice);
        } catch (BizException e) {
            // 未配置档位的 symbol 跳过本次强平判定，避免吞错导致仓位卡死，运维需关注此告警
            log.error("强平判定跳过 posId={} symbol={} reason={}", positionId, position.getSymbol(), e.getMessage());
            return;
        }

        if (hit) {
            forceCloseInCurrentTransaction(position, currentPrice, System.currentTimeMillis());
        }
    }

    // ==================== 批量止损/止盈触发（共用平仓流程） ====================

    @Override
    public void batchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price, long priceAt) {
        Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + positionId);
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            ledgerTx.run(FUTURES_CLOSE_SETTLE, () -> doBatchTrigger(positionId, slIds, price, priceAt, true));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void batchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price, long priceAt) {
        Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + positionId);
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            ledgerTx.run(FUTURES_CLOSE_SETTLE, () -> doBatchTrigger(positionId, tpIds, price, priceAt, false));
        } finally {
            lock.unlock();
        }
    }

    /**
     * SL/TP 批量触发平仓的单一流程（曾是两段逐行镜像的 80 行，改平仓逻辑只改一半的事故温床）。
     * isStopLoss 只决定三件事：读哪张保护单列表、部分平仓后剩余列表写回哪个字段、订单状态字面量。
     * <p>
     * 账本语义是 FUTURES_CLOSE_SETTLE 而不是强平返还：止盈止损触发是保护单成交平仓
     * （落库的订单状态就是 STOP_LOSS / TAKE_PROFIT，仓位是 CLOSED），跟 LIQUIDATED 是两件事。
     * 标成强平会把每一次正常止盈都写成"你被强平了"。
     */
    void doBatchTrigger(Long positionId, Collection<String> ids, BigDecimal triggerPrice, long priceAt, boolean isStopLoss) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) return;
        LedgerCtx.symbol(position.getSymbol());

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

        // 触发后就是一张市价单：按盘口扫，没盘口或触发价是旧价就用触发价
        BigDecimal price = fillPricer.futures(position.getSymbol(), "SHORT".equals(position.getSide()),
                totalCloseQty, triggerPrice, priceAt);

        BigDecimal marginPart = isFullClose ? position.getMargin()
                : position.getMargin().multiply(totalCloseQty).divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
        CloseFill fill = closeFill(position, price, totalCloseQty, marginPart, tradingConfig, true);
        BigDecimal closeValue = fill.value(totalCloseQty);

        if (isFullClose) {
            int affected = positionMapper.casClosePosition(positionId, "CLOSED", fill.price(), fill.pnl());
            if (affected == 0) return;
            positionIndexService.unregisterAll(position);
            cancelCloseOrders(positionId, orderMapper, cacheService);
        } else {
            int affected = positionMapper.atomicPartialClose(positionId, totalCloseQty, marginPart);
            if (affected == 0) return;

            if (isStopLoss) {
                sls.removeIf(s -> idSet.contains(s.getId()));
                positionMapper.updateStopLosses(positionId, sls);
            } else {
                tps.removeIf(t -> idSet.contains(t.getId()));
                positionMapper.updateTakeProfits(positionId, tps);
            }
            if (!position.isCross()) {
                FuturesPosition updated = positionMapper.selectById(positionId);
                if (updated != null && "OPEN".equals(updated.getStatus())) {
                    BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                            updated.getSymbol(), updated.getSide(), updated.getEntryPrice(), updated.getMargin(), updated.getQuantity());
                    positionIndexService.updateLiquidationPrice(updated.getId(), updated.getSymbol(), updated.getSide(), liqPrice);
                }
            }
        }

        // 全仓只结盈亏净额(可为负)；逐仓返还保证金±盈亏−手续费，closeFill 已封顶，不会为负
        if (position.isCross()) {
            crossMarginService.settle(position.getUserId(), fill.pnl().subtract(fill.commission()));
        } else {
            BigDecimal returnAmount = fill.left(marginPart);
            if (returnAmount.signum() > 0) {
                userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
            }
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(positionId);
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(totalCloseQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(fill.price());
        order.setFilledAmount(closeValue);
        order.setCommission(fill.commission());
        order.setRealizedPnl(fill.pnl());
        order.setStatus(isStopLoss ? "STOP_LOSS" : "TAKE_PROFIT");
        orderMapper.insert(order);

        log.info("futures批量{}平仓 posId={} ids={} qty={} price={} pnl={}",
                isStopLoss ? "止损" : "止盈", positionId, ids, totalCloseQty, fill.price(), fill.pnl());

        // 用 totalCloseQty 不用 position.getQuantity()：保护单可部分平仓，后者会把"平了0.2"说成"平了0.5"。
        // 盈亏扣掉手续费，跟强平、全仓爆仓的通知一个口径
        tradeNotificationService.protectiveClose(position, totalCloseQty, fill.price(),
                fill.pnl().subtract(fill.commission()), isStopLoss);
    }

    // ==================== 内部工具 ====================

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
