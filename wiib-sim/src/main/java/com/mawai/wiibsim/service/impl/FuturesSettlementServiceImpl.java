package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesSettlementService;
import com.mawai.wiibsim.service.PriceTick;
import com.mawai.wiibsim.service.TradeEventPublisher;
import com.mawai.wiibsim.service.TradeHit;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.locks.Lock;

import static com.mawai.wiibcommon.enums.LedgerBizType.*;
import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

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
    private final CrossMarginService crossMarginService;
    private final CrossLiquidationService crossLiquidationService;
    private final FairLockRegistry lockRegistry;
    private final LedgerTx ledgerTx;
    /** 限价开仓成交告诉 agent：trader 的单子成交了，当场叫醒它 */
    private final TradeEventPublisher eventPublisher;

    record FundingFeeChargeResult(boolean success, boolean checkLiquidation) {}

    @PostConstruct
    void init() {
        // 启动重建与每小时对账是同一件事：按 DB 的 PENDING 单把索引补回去（ZADD 同 member 覆盖 score）
        reconcileLimitOrderIndex();
    }

    // ==================== 限价单触发 ====================

    @Override
    public List<TradeHit> limitHits(String symbol, List<PriceTick> ticks) {
        BigDecimal[] range = PriceTick.lowHigh(ticks);
        double low = Math.nextUp(range[0].doubleValue());
        double high = Math.nextDown(range[1].doubleValue());
        // 要严格穿过：买单只看挂单价高于最低价的，卖单只看低于最高价的
        List<TradeHit> hits = new ArrayList<>();
        collectLimitHits(LIMIT_OPEN_LONG_PREFIX + symbol, low, Double.MAX_VALUE, ticks, true, hits);
        collectLimitHits(LIMIT_CLOSE_SHORT_PREFIX + symbol, low, Double.MAX_VALUE, ticks, true, hits);
        collectLimitHits(LIMIT_OPEN_SHORT_PREFIX + symbol, 0, high, ticks, false, hits);
        collectLimitHits(LIMIT_CLOSE_LONG_PREFIX + symbol, 0, high, ticks, false, hits);
        return hits;
    }

    /** 只查不摘：DB是事实、索引跟着事实走，CAS落定后由triggerLimitOrder摘自己那条 */
    private void collectLimitHits(String key, double min, double max, List<PriceTick> ticks, boolean buy,
                                  List<TradeHit> hits) {
        Set<ZSetOperations.TypedTuple<String>> candidates = cacheService.zRangeByScoreWithScores(key, min, max);
        if (candidates == null) return;
        for (var tuple : candidates) {
            Long orderId = Long.parseLong(Objects.requireNonNull(tuple.getValue()));
            FuturesOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                // DB 里没这单了，索引是过期项，摘掉
                cacheService.zRemove(key, orderId.toString());
                continue;
            }
            int i = PriceTick.firstTouch(ticks, toEpochMs(order.getCreatedAt()), order.getLimitPrice(), buy, true);
            if (i < 0) continue;
            BigDecimal triggerPrice = ticks.get(i).touchPrice(order.getLimitPrice(), buy);
            hits.add(new TradeHit(i, false, order.getUserId(), () -> triggerLimitOrder(key, orderId, triggerPrice)));
        }
    }

    private void triggerLimitOrder(String zsetKey, Long orderId, BigDecimal triggerPrice) {
        try {
            // 同一单被几条路径（成交 Stream、补漏、巡检）并发打进来也只有一个CAS成功，其余affected=0，无害
            boolean triggered = markOrderTriggered(orderId, triggerPrice);
            // CAS正常返回才摘索引：没改到说明这单早不是PENDING，索引是过期项，一样该清；
            // 抛异常不摘，单子还是PENDING、索引还在，下一笔穿过再来
            cacheService.zRemove(zsetKey, orderId.toString());
            if (!triggered) return;
            FuturesOrder order = orderMapper.selectById(orderId);
            if (order != null) {
                processTriggeredOrder(order);
            }
        } catch (Exception e) {
            log.error("futures限价单触发失败 orderId={}", orderId, e);
        }
    }

    boolean markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = orderMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) {
            log.info("futures限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
            return true;
        }
        return false;
    }

    void processTriggeredOrder(FuturesOrder order) {
        if (!"TRIGGERED".equals(order.getStatus())) return;

        if (!order.getOrderSide().startsWith("OPEN")) {
            Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + order.getPositionId());
            if (lock == null) {
                log.warn("futures限价成交获锁超时，交给补扫 orderId={} posId={}", order.getId(), order.getPositionId());
                return;
            }
            try {
                processLocked(order);
            } finally {
                lock.unlock();
            }
            return;
        }

        // 开仓成交可能并入同向仓位：币种锁与市价开仓/调杠杆互斥，发现同向仓位再压仓位锁
        // （锁序 sym→pos 与交易侧一致防死锁）；等锁超时留给 TRIGGERED 孤儿补扫
        Lock symLock = lockRegistry.tryLockAsSystem("futures:sym:" + order.getUserId() + ":" + order.getSymbol());
        if (symLock == null) {
            log.warn("futures限价开仓成交获锁超时，交给补扫 orderId={}", order.getId());
            return;
        }
        try {
            String side = order.getOrderSide().contains("LONG") ? "LONG" : "SHORT";
            FuturesPosition sameSide = openPositionsOf(order.getUserId(), order.getSymbol()).stream()
                    .filter(p -> p.getSide().equals(side)).findFirst().orElse(null);
            if (sameSide == null) {
                processLocked(order);
                return;
            }
            Lock posLock = lockRegistry.tryLockAsSystem("futures:pos:" + sameSide.getId());
            if (posLock == null) {
                log.warn("futures限价开仓成交获锁超时，交给补扫 orderId={} posId={}", order.getId(), sameSide.getId());
                return;
            }
            try {
                processLocked(order);
            } finally {
                posLock.unlock();
            }
        } finally {
            symLock.unlock();
        }
    }

    // 锁内处理一单。每笔的精确类型由四个执行方法里的 LedgerCtx.mark 给，FUTURES_LIMIT_DEDUCT 只是默认
    private void processLocked(FuturesOrder order) {
        ledgerTx.run(FUTURES_LIMIT_DEDUCT, () -> doProcessTriggeredOrder(order));
    }

    void doProcessTriggeredOrder(FuturesOrder order) {
        LedgerCtx.symbol(order.getSymbol());
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

        if (order.getOrderSide().startsWith("INCREASE")) {
            // 加仓链路已删除（同向下单即合并进仓位）：存量历史加仓挂单触发时撤单退款自清，免手动迁移
            cancelTriggeredOrderAndRefund(order, "increase_removed");
            return;
        }

        if (order.getOrderSide().startsWith("CLOSE")) {
            processTriggeredCloseOrder(order);
        } else {
            processTriggeredOpenOrder(order);
        }
    }

    private void processTriggeredOpenOrder(FuturesOrder order) {
        boolean isCross = FuturesPosition.CROSS.equals(order.getMarginMode());
        String side = order.getOrderSide().contains("LONG") ? "LONG" : "SHORT";

        // 挂单期间币种格局可能已变（新开了仓/调了杠杆）：成交前按币种级一致性复查，
        // 冲突不硬成交也不改单，直接撤单退款（Binance订单不带杠杆无此问题，我们以撤代改最干净）
        FuturesPosition sameSide = null;
        for (FuturesPosition p : openPositionsOf(order.getUserId(), order.getSymbol())) {
            if (p.isCross() != isCross) {
                cancelTriggeredOrderAndRefund(order, "margin_mode_conflict");
                return;
            }
            if (!Objects.equals(p.getLeverage(), order.getLeverage())) {
                cancelTriggeredOrderAndRefund(order, "leverage_mismatch");
                return;
            }
            if (side.equals(p.getSide())) sameSide = p;
        }

        // 挂着的单是被别人吃掉的，成交价就是挂单价，穿价那一下的好处不归挂单方
        BigDecimal executePrice = order.getLimitPrice();
        BigDecimal quantity = order.getQuantity();
        // 同向并入按合并后总持仓过档
        BigDecimal bracketQty = sameSide != null ? sameSide.getQuantity().add(quantity) : quantity;
        if (!isLeverageAllowed(order.getSymbol(), order.getLeverage(), executePrice.multiply(bracketQty))) {
            cancelTriggeredOrderAndRefund(order, "leverage_not_allowed");
            return;
        }

        // 随单的止损止盈从成交这一刻起算
        long filledAt = System.currentTimeMillis();
        if (order.getStopLosses() != null) order.getStopLosses().forEach(sl -> sl.setCreatedAt(filledAt));
        if (order.getTakeProfits() != null) order.getTakeProfits().forEach(tp -> tp.setCreatedAt(filledAt));

        if (sameSide != null) {
            fillOpenOrderIntoPosition(order, sameSide, executePrice);
            return;
        }

        BigDecimal positionValue = executePrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(order.getLeverage()), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, false);
        if (!chargeOpenFill(order, margin, commission)) return;

        FuturesPosition position = new FuturesPosition();
        position.setUserId(order.getUserId());
        position.setSymbol(order.getSymbol());
        position.setSide(order.getOrderSide().contains("LONG") ? "LONG" : "SHORT");
        position.setMarginMode(order.getMarginMode());
        position.setLeverage(order.getLeverage());
        position.setQuantity(quantity);
        position.setEntryPrice(executePrice);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setStatus("OPEN");

        position.setStopLosses(order.getStopLosses());
        position.setTakeProfits(order.getTakeProfits());

        positionMapper.insert(position);

        // 回填 position_id：限价开仓下单时仓位未生成，不补上这单的开仓手续费就聚合不进仓位已实现盈亏
        int filled = orderMapper.casUpdateToFilled(order.getId(), position.getId(), executePrice, positionValue, commission, margin, null);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        positionIndexService.registerPositionIndex(position);
        if (isCross) crossMarginService.refreshUserIndex(order.getUserId());
        eventPublisher.publishAfterCommit(new TradeEvent(TradeEvent.LIMIT_FILLED, order.getUserId(), position.getId(),
                order.getId(), order.getSymbol(), position.getSide(), quantity, executePrice, null, System.currentTimeMillis()));

        log.info("futures限价开仓成交 orderId={} mode={} price={} margin={}",
                order.getId(), order.getMarginMode(), executePrice, margin);
    }

    /**
     * 限价开仓单成交并入同向仓位（Binance 语义：同向下单即合并，均价加权）。
     * 随单SL/TP追加到仓位现有档位；合计条数超4撤单退款（总量恒不超：仓位≤持仓、订单≤订单量）。
     */
    private void fillOpenOrderIntoPosition(FuturesOrder order, FuturesPosition position, BigDecimal executePrice) {
        BigDecimal addQty = order.getQuantity();
        int leverage = position.getLeverage(); // 一致性已复查，与订单杠杆相同
        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);

        List<FuturesStopLoss> mergedSl;
        List<FuturesTakeProfit> mergedTp;
        try {
            mergedSl = mergeSlList(position.getStopLosses(), order.getStopLosses(), newQty);
            mergedTp = mergeTpList(position.getTakeProfits(), order.getTakeProfits(), newQty);
        } catch (BizException e) {
            // 动钱之前拦住：档位超限不硬成交，撤单退款
            cancelTriggeredOrderAndRefund(order, "sltp_split_limit");
            return;
        }

        BigDecimal addValue = executePrice.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, false);
        if (!chargeOpenFill(order, addMargin, commission)) return;

        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(executePrice.multiply(addQty))
                .divide(newQty, PRICE_SCALE, RoundingMode.HALF_UP);

        int affected = positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);
        if (affected == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        if (mergedSl != null) positionMapper.updateStopLosses(position.getId(), mergedSl);
        if (mergedTp != null) positionMapper.updateTakeProfits(position.getId(), mergedTp);
        // 只注册新增档位的触发索引（存量已在册）
        if (order.getStopLosses() != null && !order.getStopLosses().isEmpty()) {
            positionIndexService.registerStopLosses(position.getId(), position.getSymbol(), position.getSide(), order.getStopLosses());
        }
        if (order.getTakeProfits() != null && !order.getTakeProfits().isEmpty()) {
            positionIndexService.registerTakeProfits(position.getId(), position.getSymbol(), position.getSide(), order.getTakeProfits());
        }

        if (!position.isCross()) {
            BigDecimal newMargin = position.getMargin().add(addMargin);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                    newEntryPrice, newMargin, newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }
        // 同 mergeNow：全仓加仓并入必须汇入 refreshUserIndex 作废强平安全带（bump 在其内）
        if (position.isCross()) crossMarginService.refreshUserIndex(order.getUserId());

        // 回填 position_id：开仓手续费聚合进仓位已实现盈亏
        int filled = orderMapper.casUpdateToFilled(order.getId(), position.getId(), executePrice, addValue, commission, addMargin, null);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        eventPublisher.publishAfterCommit(new TradeEvent(TradeEvent.LIMIT_FILLED, order.getUserId(), position.getId(),
                order.getId(), order.getSymbol(), position.getSide(), addQty, executePrice, null, System.currentTimeMillis()));

        log.info("futures限价开仓并入 orderId={} posId={} price={} addMargin={} 新均价={}",
                order.getId(), position.getId(), executePrice, addMargin, newEntryPrice);
    }

    /**
     * 限价开仓成交的资金动作，成本 = 成交价下的保证金+手续费。
     * 成本超出挂单时冻结/预留的 frozenAmount（挂单后手续费率调高了）：超出部分要过全仓可用额度，
     * 逐仓还要从余额补扣，过不去就撤单退款、返回 false。
     */
    private boolean chargeOpenFill(FuturesOrder order, BigDecimal margin, BigDecimal commission) {
        BigDecimal frozenAmount = order.getFrozenAmount();
        BigDecimal extra = margin.add(commission).subtract(frozenAmount);
        // 可用额度里已扣掉本单预留，只校验超出部分
        if (extra.signum() > 0) {
            // 先锁 user 行再读可用额度，同 assertCanAfford，不够撤单不抛异常
            userMapper.selectByIdForUpdate(order.getUserId());
            if (crossMarginService.snapshot(order.getUserId()).available().compareTo(extra) < 0) {
                cancelTriggeredOrderAndRefund(order, "insufficient_available");
                return false;
            }
        }

        if (FuturesPosition.CROSS.equals(order.getMarginMode())) {
            // 全仓：挂单期间只是占用记账，成交只实扣手续费（挂单占用随状态翻转自动消失）
            LedgerCtx.mark(FUTURES_OPEN_FEE, "FUTURES_ORDER", order.getId());
            userMapper.atomicSettleBalance(order.getUserId(), commission.negate());
            return true;
        }

        // 逐仓：超出部分先从余额补扣，补不上撤单退冻结；补上了再销冻结
        if (extra.signum() > 0) {
            LedgerCtx.mark(FUTURES_LIMIT_DEDUCT, "FUTURES_ORDER", order.getId());
            if (userMapper.atomicUpdateBalance(order.getUserId(), extra.negate()) == null) {
                cancelTriggeredOrderAndRefund(order, "insufficient_balance");
                return false;
            }
        }
        LedgerCtx.mark(FUTURES_LIMIT_DEDUCT, "FUTURES_ORDER", order.getId());
        BigDecimal afterFrozen = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (afterFrozen == null) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        if (extra.signum() < 0) {
            LedgerCtx.mark(FUTURES_LIMIT_REFUND, "FUTURES_ORDER", order.getId());
            userMapper.atomicUpdateBalance(order.getUserId(), extra.negate());
        }
        return true;
    }

    private List<FuturesPosition> openPositionsOf(Long userId, String symbol) {
        return positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, symbol)
                .eq(FuturesPosition::getStatus, "OPEN"));
    }

    private void processTriggeredCloseOrder(FuturesOrder order) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
            return;
        }

        BigDecimal closeQty = order.getQuantity();
        // 挂着的单是被别人吃掉的，成交价就是挂单价
        BigDecimal executePrice = order.getLimitPrice();

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal marginPart = isFullClose ? position.getMargin()
                : position.getMargin().multiply(closeQty).divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
        CloseFill fill = closeFill(position, executePrice, closeQty, marginPart, tradingConfig, false);
        BigDecimal closeValue = fill.value(closeQty);

        if (isFullClose) {
            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", fill.price(), fill.pnl());
            if (affected == 0) {
                // 仓位已被并发平掉，订单取消，禁止重复返款
                orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
                return;
            }

            positionIndexService.unregisterAll(position);
            cancelCloseOrders(position.getId(), orderMapper, cacheService);
        } else {
            int affected = positionMapper.atomicPartialClose(position.getId(), closeQty, marginPart);
            if (affected == 0) {
                // 可平数量不足或已关闭，订单取消，禁止重复返款
                orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
                return;
            }

            if (!position.isCross()) {
                // 限价部分平仓成交后 qty/margin 已变，刷新逐仓强平索引。
                BigDecimal newQty = position.getQuantity().subtract(closeQty);
                BigDecimal newMargin = position.getMargin().subtract(marginPart);
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        position.getSymbol(), position.getSide(), position.getEntryPrice(), newMargin, newQty);
                positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
            }
        }

        // 全仓只结盈亏净额(可为负，穿仓由settle处理)；逐仓返还保证金±盈亏−手续费，closeFill 已封顶
        if (position.isCross()) {
            crossMarginService.settle(order.getUserId(), fill.pnl().subtract(fill.commission()));
        } else {
            // 覆盖方法级默认 FUTURES_LIMIT_DEDUCT：这笔是平仓结算返还，不是开仓扣款
            LedgerCtx.mark(FUTURES_CLOSE_SETTLE, "FUTURES_ORDER", order.getId());
            userMapper.atomicUpdateBalance(order.getUserId(), fill.left(marginPart));
        }
        int filled = orderMapper.casUpdateToFilled(order.getId(), null, fill.price(), closeValue,
                fill.commission(), null, fill.pnl());
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        log.info("futures限价平仓成交 orderId={} price={} pnl={}",
                order.getId(), fill.price(), fill.pnl());
    }

    private boolean isLeverageAllowed(String symbol, int leverage, BigDecimal notional) {
        int maxAllowed = bracketRegistry.getEffectiveMaxLeverage(symbol, notional);
        return leverage <= maxAllowed;
    }

    private void cancelTriggeredOrderAndRefund(FuturesOrder order, String reason) {
        int affected = orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
        if (affected == 0) return;

        // 全仓单没冻结过钱，状态翻转后挂单占用自动消失，无需退款
        BigDecimal frozenAmount = order.getFrozenAmount();
        if (!FuturesPosition.CROSS.equals(order.getMarginMode())
                && frozenAmount != null && frozenAmount.compareTo(BigDecimal.ZERO) > 0) {
            // 退款是"扣冻结 + 进可用"两笔，语义不同：前者销账、后者才是真退回
            LedgerCtx.mark(FUTURES_LIMIT_DEDUCT, "FUTURES_ORDER", order.getId());
            BigDecimal afterFrozen = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
            // 异常状态：cancel 前 frozen 必然存在，扣不到说明数据被并发改动，整事务回滚避免余额凭空增加
            if (afterFrozen == null) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            LedgerCtx.mark(FUTURES_LIMIT_UNFREEZE, "FUTURES_ORDER", order.getId());
            userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount);
        }
        log.info("futures限价单触发后取消 orderId={} reason={} refund={}",
                order.getId(), reason, frozenAmount);
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
                    processTriggeredOrder(order);
                } catch (Exception e) {
                    log.error("补处理TRIGGERED订单失败 orderId={}", order.getId(), e);
                }
            });
        }
        log.info("补处理TRIGGERED孤儿单 共{}个", triggeredOrders.size());
    }

    // ==================== 资金费率扣除 ====================

    /** 逐个仓位收，单仓失败不影响别的 */
    @Override
    public void chargeFundingFee(String symbol, long fundingTime, BigDecimal rate, List<FuturesPosition> positions) {
        int failCount = 0;
        for (FuturesPosition pos : positions) {
            try {
                if (!chargeFundingFeeOne(pos, rate)) failCount++;
            } catch (Exception e) {
                log.error("futures扣除资金费率失败 posId={}", pos.getId(), e);
                failCount++;
            }
        }
        log.info("资金费结算完成 symbol={} 结算时刻={} 费率={} 成功{} 失败{}",
                symbol, fundingTime, rate, positions.size() - failCount, failCount);
    }

    boolean chargeFundingFeeOne(FuturesPosition pos, BigDecimal rate) {
        // 名义额按结算时刻的 mark 价算（对齐 Binance），等锁期间价格变了也不换；mark 取不到传 null，退回开仓价
        BigDecimal markPrice = markPriceOrNull(pos.getSymbol());

        Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + pos.getId());
        if (lock == null) {
            log.warn("futures资金费获锁超时，本期未收 posId={}", pos.getId());
            return false;
        }

        FundingFeeChargeResult result;
        try {
            result = ledgerTx.call(FUNDING_FEE_PAY, () -> doChargeFundingFeeOne(pos.getId(), rate, markPrice));
        } finally {
            lock.unlock();
        }

        if (result.checkLiquidation()) {
            if (pos.isCross()) {
                crossLiquidationService.checkUser(pos.getUserId());
            } else {
                FuturesPosition latest = positionMapper.selectById(pos.getId());
                if (latest != null && "OPEN".equals(latest.getStatus())) {
                    BigDecimal price = markPriceOrNull(latest.getSymbol());
                    if (price == null) {
                        log.warn("futures资金费扣光保证金后取不到mark价，本次跳过强平复核 posId={}", latest.getId());
                    } else {
                        riskService.checkAndLiquidate(latest.getId(), price);
                    }
                }
            }
        }
        return result.success();
    }

    /**
     * 调用方压的 FUNDING_FEE_PAY 在这里只干两件事：让本方法内的流水挂得上 symbol，以及给将来新增的资金分支一个兜底。
     * 收/付两笔各自 mark 精确类型，所以这个默认值实际不会被用到。
     * <p>
     * 调用方拿着仓位锁；markPrice 是结算时刻的 mark 价，null 退回开仓价。
     */
    FundingFeeChargeResult doChargeFundingFeeOne(Long positionId, BigDecimal rate, BigDecimal markPrice) {
        FuturesPosition pos = positionMapper.selectById(positionId);
        if (pos == null || !"OPEN".equals(pos.getStatus())) {
            return new FundingFeeChargeResult(false, false);
        }
        LedgerCtx.symbol(pos.getSymbol());

        BigDecimal notionalPrice = markPrice != null ? markPrice : pos.getEntryPrice();
        BigDecimal notional = notionalPrice.multiply(pos.getQuantity());
        // 真实转移机制：正=本仓应付，负=本仓应收（费率>0 多付空收，费率<0 反向）
        BigDecimal transfer = fundingTransfer(pos.getSide(), notional, rate);
        if (transfer.signum() == 0) return new FundingFeeChargeResult(true, false);

        if (pos.isCross()) {
            // 全仓：资金费直接对余额结算（可为负，没有"扣保证金"兜底——保证金只是占用数字）；应付方结算后账户级复核
            crossMarginService.settle(pos.getUserId(), transfer.negate());
            int added = positionMapper.atomicAddFundingFeeTotal(pos.getId(), transfer);
            if (added == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            return new FundingFeeChargeResult(true, transfer.signum() > 0);
        }

        if (transfer.signum() < 0) {
            // 收取方：入余额；funding_fee_total 记负（净口径，排行榜按累计净付扣回时自然冲正）
            LedgerCtx.mark(FUNDING_FEE_RECV, "POSITION", pos.getId());
            userMapper.atomicUpdateBalance(pos.getUserId(), transfer.negate());
            int added = positionMapper.atomicAddFundingFeeTotal(pos.getId(), transfer);
            if (added == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            return new FundingFeeChargeResult(true, false);
        }

        // 支付方三级兜底：余额 → 保证金 → 保证金扣光并触发强平复核
        // 注意：这句返 null（余额不够）是正常分支，切面照样把 mark 取走丢掉、不记账，
        // 不会泄漏到下面扣保证金那条 SQL 上。
        BigDecimal fee = transfer;
        LedgerCtx.mark(FUNDING_FEE_PAY, "POSITION", pos.getId());
        BigDecimal afterPay = userMapper.atomicUpdateBalance(pos.getUserId(), fee.negate());
        if (afterPay != null) {
            int added = positionMapper.atomicAddFundingFeeTotal(pos.getId(), fee);
            if (added == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            return new FundingFeeChargeResult(true, false);
        }

        // 后两级扣的是仓位保证金，不穿过 user 表。那条 SQL 的参数里只有 positionId，
        // 切面既拿不到 userId 也拿不到扣款额，两者都得在这儿标进去。
        // fee 已在 fundingTransfer 里 setScale(2)，与 futures_position.margin 的 numeric(18,2) 对齐，
        // 所以账本的 delta 与 balanceAfter 严格自洽，没有舍入偏差
        LedgerCtx.markPositionFee(pos.getUserId(), pos.getId(), fee);
        BigDecimal marginAfter = positionMapper.atomicDeductFundingFee(pos.getId(), fee);
        if (marginAfter != null) {
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(pos.getSymbol(), pos.getSide(), pos.getEntryPrice(),
                    marginAfter, pos.getQuantity());
            positionIndexService.updateLiquidationPrice(pos.getId(), pos.getSymbol(), pos.getSide(), liqPrice);
            return new FundingFeeChargeResult(true, false);
        }

        // 扣光那条连金额参数都没有（SET margin = 0），扣的就是当前全部保证金，同样由调用点带进来。
        // 按项目对整体覆写的既定做法先加行锁读一次，锁住之后 UPDATE 抹掉的就是这个数，
        // 不会记出"delta=旧快照 / balanceAfter=0"这种自相矛盾的行。
        // 读不到（仓位已关/已删）说明没什么可扣，直接返回——紧跟的 UPDATE 本来也一行不改
        BigDecimal deducted = positionMapper.selectMarginForUpdate(pos.getId());
        if (deducted == null) {
            return new FundingFeeChargeResult(false, false);
        }
        LedgerCtx.markPositionFee(pos.getUserId(), pos.getId(), deducted);
        marginAfter = positionMapper.atomicDeductFundingFeePartial(pos.getId());
        if (marginAfter != null) {
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

    // ==================== ZSet索引重建/对账 ====================

    /**
     * 周期对账：把DB里所有PENDING挂单补回索引。纯追加不删——ZADD同member只覆盖score，
     * 重复跑无害；Redis丢键、或触发/撤单路径中途出岔子掉出索引的挂单，靠这个捞回来接着盯价。
     */
    @Override
    public void reconcileLimitOrderIndex() {
        List<FuturesOrder> pendingOrders = pendingLimitOrders();
        if (pendingOrders.isEmpty()) return;
        for (FuturesOrder order : pendingOrders) {
            addToLimitZSet(order, cacheService);
        }
        log.info("futures限价单索引对账 挂单{}个", pendingOrders.size());
    }

    private List<FuturesOrder> pendingLimitOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
    }

    // ==================== 内部工具 ====================

    private BigDecimal getMarkPrice(String symbol) {
        return FuturesHelper.markPrice(cacheService, symbol);
    }

    private BigDecimal markPriceOrNull(String symbol) {
        try {
            return getMarkPrice(symbol);
        } catch (Exception e) {
            return null;
        }
    }
}
