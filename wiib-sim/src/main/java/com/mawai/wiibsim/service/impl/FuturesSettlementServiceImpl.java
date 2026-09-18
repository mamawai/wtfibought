package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesSettlementService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

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
    private final RedisLockUtil redisLockUtil;
    private final FundingRateService fundingRateService;

    protected record FundingFeeChargeResult(boolean success, boolean checkLiquidation) {}

    @PostConstruct
    void init() {
        // 启动重建与每小时对账是同一件事：按 DB 的 PENDING 单把索引补回去（ZADD 同 member 覆盖 score）
        reconcileLimitOrderIndex();
    }

    // ==================== 限价单触发 ====================

    @Override
    public void onPriceUpdate(String symbol, BigDecimal price) {
        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        // 只查不摘：DB是事实、索引跟着事实走，CAS落定后由triggerLimitOrder摘自己那条
        fireHits(openLongKey, cacheService.zRangeByScoreWithScores(openLongKey, price.doubleValue(), Double.MAX_VALUE), price);
        fireHits(openShortKey, cacheService.zRangeByScoreWithScores(openShortKey, 0, price.doubleValue()), price);
        fireHits(closeLongKey, cacheService.zRangeByScoreWithScores(closeLongKey, 0, price.doubleValue()), price);
        fireHits(closeShortKey, cacheService.zRangeByScoreWithScores(closeShortKey, price.doubleValue(), Double.MAX_VALUE), price);
    }

    /** 命中项逐个起虚拟线程触发，成交价按 tick 价 */
    private void fireHits(String key, Set<ZSetOperations.TypedTuple<String>> hits, BigDecimal triggerPrice) {
        if (hits == null || hits.isEmpty()) return;
        for (var tuple : hits) {
            Long orderId = Long.parseLong(Objects.requireNonNull(tuple.getValue()));
            Thread.startVirtualThread(() -> triggerLimitOrder(key, orderId, triggerPrice));
        }
    }

    private void triggerLimitOrder(String zsetKey, Long orderId, BigDecimal triggerPrice) {
        try {
            var proxy = SpringUtils.getAopProxy(this);
            // 同一单被多个tick并发打进来也只有一个CAS成功，其余affected=0，无害
            boolean triggered = proxy.markOrderTriggered(orderId, triggerPrice);
            // CAS正常返回才摘索引：没改到说明这单早不是PENDING，索引是过期项，一样该清；
            // 抛异常不摘，单子还是PENDING、索引还在，下个tick重来
            cacheService.zRemove(zsetKey, orderId.toString());
            if (!triggered) return;
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
        if (!"TRIGGERED".equals(order.getStatus())) return;

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

        // 开仓成交可能并入同向仓位：币种锁与市价开仓/调杠杆互斥，发现同向仓位再压仓位锁
        // （锁序 sym→pos 与交易侧一致防死锁）；拿不到锁直接放弃，TRIGGERED 孤儿补扫会重试
        String symLockKey = "futures:sym:" + order.getUserId() + ":" + order.getSymbol();
        String symLockValue = redisLockUtil.tryLock(symLockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (symLockValue == null) {
            log.info("futures限价开仓成交跳过，币种处理中 orderId={}", order.getId());
            return;
        }
        try {
            String side = order.getOrderSide().contains("LONG") ? "LONG" : "SHORT";
            FuturesPosition sameSide = openPositionsOf(order.getUserId(), order.getSymbol()).stream()
                    .filter(p -> p.getSide().equals(side)).findFirst().orElse(null);
            if (sameSide == null) {
                SpringUtils.getAopProxy(this).doProcessTriggeredOrder(order);
                return;
            }
            String posLockKey = "futures:pos:" + sameSide.getId();
            String posLockValue = redisLockUtil.tryLock(posLockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
            if (posLockValue == null) {
                log.info("futures限价开仓成交跳过，仓位处理中 orderId={} posId={}", order.getId(), sameSide.getId());
                return;
            }
            try {
                SpringUtils.getAopProxy(this).doProcessTriggeredOrder(order);
            } finally {
                redisLockUtil.unlock(posLockKey, posLockValue);
            }
        } finally {
            redisLockUtil.unlock(symLockKey, symLockValue);
        }
    }

    /**
     * @Ledger 只能标在这层：四个执行方法是私有自调用、AOP 拦不到，
     * 本方法经 getAopProxy 真走代理；每笔精确类型由执行方法内的 LedgerCtx.mark 覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    @Ledger(FUTURES_LIMIT_DEDUCT)
    protected void doProcessTriggeredOrder(FuturesOrder order) {
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

        // 成交价口径：被动 maker 单按挂单价成交（真实交易所语义——被动单躺在盘口被对手吃，成交价=挂单价，
        // 跳空穿越的好处不归挂单方）；挂单时即穿价的 taker 单按触发价成交（主动吃对手价）。
        // 顺带统一了实时触发（原用触发价）与宕机补漏（原用挂单价）两条路径的口径。
        boolean isCloseOrder = order.getOrderSide().startsWith("CLOSE");
        BigDecimal executePrice = isLimitTaker(order, isCloseOrder) ? order.getFilledPrice() : order.getLimitPrice();
        if (executePrice == null) executePrice = order.getLimitPrice();
        if (executePrice == null) return;

        if (order.getOrderSide().startsWith("OPEN")) {
            processTriggeredOpenOrder(order, executePrice);
        } else if (order.getOrderSide().startsWith("INCREASE")) {
            // 加仓链路已删除（同向下单即合并进仓位）：存量历史加仓挂单触发时撤单退款自清，免手动迁移
            cancelTriggeredOrderAndRefund(order, "increase_removed");
        } else {
            processTriggeredCloseOrder(order, executePrice);
        }
    }

    private void processTriggeredOpenOrder(FuturesOrder order, BigDecimal executePrice) {
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

        BigDecimal quantity = order.getQuantity();
        // 同向并入按合并后总持仓过档
        BigDecimal bracketQty = sameSide != null ? sameSide.getQuantity().add(quantity) : quantity;
        if (!isLeverageAllowed(order.getSymbol(), order.getLeverage(), executePrice.multiply(bracketQty))) {
            cancelTriggeredOrderAndRefund(order, "leverage_not_allowed");
            return;
        }

        if (sameSide != null) {
            fillOpenOrderIntoPosition(order, sameSide, executePrice);
            return;
        }

        BigDecimal positionValue = executePrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(order.getLeverage()), 2, RoundingMode.CEILING);
        boolean isTaker = isLimitTaker(order, false);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, isTaker);
        BigDecimal actualCost = margin.add(commission);

        if (isCross) {
            // 全仓：挂单期间只是占用记账，成交只实扣手续费（挂单占用随状态翻转自动消失）
            LedgerCtx.mark(FUTURES_OPEN_FEE, "FUTURES_ORDER", order.getId());
            userMapper.atomicSettleBalance(order.getUserId(), commission.negate());
        } else {
            BigDecimal frozenAmount = order.getFrozenAmount();
            LedgerCtx.mark(FUTURES_LIMIT_DEDUCT, "FUTURES_ORDER", order.getId());
            BigDecimal afterFrozen = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
            if (afterFrozen == null) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            if (actualCost.compareTo(frozenAmount) < 0) {
                BigDecimal refund = frozenAmount.subtract(actualCost);
                LedgerCtx.mark(FUTURES_LIMIT_REFUND, "FUTURES_ORDER", order.getId());
                userMapper.atomicUpdateBalance(order.getUserId(), refund);
            }
        }

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

        log.info("futures限价开仓成交 orderId={} mode={} price={} feeType={} margin={}",
                order.getId(), order.getMarginMode(), executePrice, isTaker ? "TAKER" : "MAKER", margin);
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
        boolean isTaker = isLimitTaker(order, false);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, isTaker);
        BigDecimal actualCost = addMargin.add(commission);

        if (position.isCross()) {
            // 全仓：挂单期间只是占用记账，成交只实扣手续费（挂单占用随状态翻转自动消失）
            LedgerCtx.mark(FUTURES_OPEN_FEE, "FUTURES_ORDER", order.getId());
            userMapper.atomicSettleBalance(order.getUserId(), commission.negate());
        } else {
            BigDecimal frozenAmount = order.getFrozenAmount();
            LedgerCtx.mark(FUTURES_LIMIT_DEDUCT, "FUTURES_ORDER", order.getId());
            BigDecimal afterFrozen = userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
            if (afterFrozen == null) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            if (actualCost.compareTo(frozenAmount) < 0) {
                LedgerCtx.mark(FUTURES_LIMIT_REFUND, "FUTURES_ORDER", order.getId());
                userMapper.atomicUpdateBalance(order.getUserId(), frozenAmount.subtract(actualCost));
            }
        }

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
        // 同 executeMarketMerge：全仓加仓并入必须汇入 refreshUserIndex 作废强平安全带（bump 在其内）
        if (position.isCross()) crossMarginService.refreshUserIndex(order.getUserId());

        // 回填 position_id：开仓手续费聚合进仓位已实现盈亏
        int filled = orderMapper.casUpdateToFilled(order.getId(), position.getId(), executePrice, addValue, commission, addMargin, null);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        log.info("futures限价开仓并入 orderId={} posId={} price={} feeType={} addMargin={} 新均价={}",
                order.getId(), position.getId(), executePrice, isTaker ? "TAKER" : "MAKER", addMargin, newEntryPrice);
    }

    private List<FuturesPosition> openPositionsOf(Long userId, String symbol) {
        return positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, symbol)
                .eq(FuturesPosition::getStatus, "OPEN"));
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

        BigDecimal marginPart = isFullClose ? position.getMargin()
                : position.getMargin().multiply(closeQty).divide(position.getQuantity(), 2, RoundingMode.HALF_UP);

        if (isFullClose) {
            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", executePrice, pnl);
            if (affected == 0) {
                // 仓位已被并发平掉，订单取消，禁止重复返款
                orderMapper.casUpdateStatus(order.getId(), "PROCESSING", "CANCELLED");
                return;
            }

            positionIndexService.unregisterAll(position);
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

        // 全仓只结盈亏净额(可为负，穿仓由settle处理)；逐仓返还保证金±盈亏，下限0
        if (position.isCross()) {
            crossMarginService.settle(order.getUserId(), pnl.subtract(commission));
        } else {
            // 覆盖方法级默认 FUTURES_LIMIT_DEDUCT：这笔是平仓结算返还，不是开仓扣款
            LedgerCtx.mark(FUTURES_CLOSE_SETTLE, "FUTURES_ORDER", order.getId());
            userMapper.atomicUpdateBalance(order.getUserId(), marginPart.add(pnl).subtract(commission).max(BigDecimal.ZERO));
        }
        int filled = orderMapper.casUpdateToFilled(order.getId(), null, executePrice, closeValue, commission, null, pnl);
        if (filled == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

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

    // ==================== 空窗补漏 ====================

    @Override
    public void recoverGap(String symbol, List<KlineBar> bars) {
        if (bars.isEmpty()) return;
        // 整段极值先粗筛一遍索引，逐单再按各自挂单时间算区间
        BigDecimal[] all = KlineBar.lowHighAfter(bars, 0);
        double low = all[0].doubleValue();
        double high = all[1].doubleValue();

        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        // 开多/平空看区间低点，开空/平多看高点
        int count = 0;
        count += recoverHits(openLongKey, cacheService.zRangeByScoreWithScores(openLongKey, low, Double.MAX_VALUE), bars, true);
        count += recoverHits(openShortKey, cacheService.zRangeByScoreWithScores(openShortKey, 0, high), bars, false);
        count += recoverHits(closeLongKey, cacheService.zRangeByScoreWithScores(closeLongKey, 0, high), bars, false);
        count += recoverHits(closeShortKey, cacheService.zRangeByScoreWithScores(closeShortKey, low, Double.MAX_VALUE), bars, true);

        if (count > 0) {
            log.info("futures空窗补漏触发限价单 symbol={} 共{}个", symbol, count);
        }
    }

    /** 逐单按挂单时间之后的区间复核，命中才触发，成交价按挂单价 */
    private int recoverHits(String key, Set<ZSetOperations.TypedTuple<String>> hits,
                            List<KlineBar> bars, boolean lowSide) {
        if (hits == null || hits.isEmpty()) return 0;
        int count = 0;
        for (var tuple : hits) {
            Long orderId = Long.parseLong(Objects.requireNonNull(tuple.getValue()));
            FuturesOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                // DB 里没这单了，索引是过期项，摘掉
                cacheService.zRemove(key, orderId.toString());
                continue;
            }
            BigDecimal[] range = KlineBar.lowHighAfter(bars, toEpochMs(order.getCreatedAt()));
            if (range == null) continue;   // 挂单晚于整段行情
            BigDecimal limitPrice = order.getLimitPrice();
            boolean hit = lowSide
                    ? range[0].compareTo(limitPrice) <= 0
                    : range[1].compareTo(limitPrice) >= 0;
            if (!hit) continue;
            Thread.startVirtualThread(() -> triggerLimitOrder(key, orderId, limitPrice));
            count++;
        }
        return count;
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
        // 全站唯一一次调官方资金费率接口：先刷缓存，扣费和前端查询都从缓存取
        fundingRateService.refresh();

        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));
        if (positions.isEmpty()) return;

        Map<String, BigDecimal> rateBySymbol = new HashMap<>();
        int successCount = 0;
        int failCount = 0;

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal rate = rateBySymbol.computeIfAbsent(pos.getSymbol(), fundingRateService::rateForSettlement);
                boolean success = SpringUtils.getAopProxy(this).chargeFundingFeeOne(pos, rate);
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

        log.info("futures资金费率结算完成 成功{} 失败{} 费率={}", successCount, failCount, rateBySymbol);
    }

    protected boolean chargeFundingFeeOne(FuturesPosition pos, BigDecimal rate) {
        String lockKey = "futures:pos:" + pos.getId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) {
            log.info("futures资金费跳过，仓位处理中 posId={}", pos.getId());
            return false;
        }

        FundingFeeChargeResult result;
        try {
            result = SpringUtils.getAopProxy(this).doChargeFundingFeeOne(pos.getId(), rate);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }

        if (result.checkLiquidation()) {
            if (pos.isCross()) {
                crossLiquidationService.checkUser(pos.getUserId());
            } else {
                FuturesPosition latest = positionMapper.selectById(pos.getId());
                if (latest != null && "OPEN".equals(latest.getStatus())) {
                    riskService.checkAndLiquidate(latest.getId(), getMarkPrice(latest.getSymbol()));
                }
            }
        }
        return result.success();
    }

    /**
     * 方法级 @Ledger 在这里只干两件事：给本方法内的流水挂上 symbol，以及给将来新增的资金分支一个兜底。
     * 收/付两笔各自 mark 精确类型，所以方法级这个值实际不会被用到。
     * （本方法 protected 且经 getAopProxy 走代理调进来，AOP 拦得到。）
     */
    @Transactional(rollbackFor = Exception.class)
    @Ledger(FUNDING_FEE_PAY)
    protected FundingFeeChargeResult doChargeFundingFeeOne(Long positionId, BigDecimal rate) {
        FuturesPosition pos = positionMapper.selectById(positionId);
        if (pos == null || !"OPEN".equals(pos.getStatus())) {
            return new FundingFeeChargeResult(false, false);
        }
        LedgerCtx.symbol(pos.getSymbol());

        // 名义额对齐 Binance：按 mark 价×数量结算；mark 不可得退回开仓价（罕见，别让结算卡死）
        BigDecimal notionalPrice;
        try {
            notionalPrice = getMarkPrice(pos.getSymbol());
        } catch (Exception e) {
            notionalPrice = pos.getEntryPrice();
        }
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
        // 但不能拿 611 行那次 selectById 的 pos.getMargin()：那是无锁快照，并发追加/减少保证金后
        // 它就不是实际扣款额了，记出来的会是"delta=旧快照 / balanceAfter=0"这种自相矛盾的行。
        // 按项目对整体覆写的既定做法先加行锁读一次，锁住之后 UPDATE 抹掉的就是这个数。
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
