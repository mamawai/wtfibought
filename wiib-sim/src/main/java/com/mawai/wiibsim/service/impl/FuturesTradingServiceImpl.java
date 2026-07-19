package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesTradingServiceImpl implements FuturesTradingService {

    private static final int PRICE_SCALE = 8;

    private final UserService userService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;

    // ==================== 开仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        validateOpenRequest(request);
        getAndValidateUser(userId);

        BigDecimal price = getPrice(request.getSymbol());
        int leverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());

        if ("MARKET".equals(request.getOrderType())) {
            // BTC/ETH 等已配置档位的 symbol 按 Binance 档位二次校验杠杆（按 notional 取档）
            validateLeverageByBracket(request.getSymbol(), leverage, price.multiply(request.getQuantity()));
            return executeMarketOpen(userId, request, price, leverage);
        } else {
            BigDecimal markPrice = getMarkPrice(request.getSymbol());
            tradingConfig.validateLimitPrice(request.getLimitPrice(), markPrice);
            validateLeverageByBracket(request.getSymbol(), leverage, request.getLimitPrice().multiply(request.getQuantity()));
            return createLimitOpenOrder(userId, request, leverage, markPrice);
        }
    }

    /** 按 notional 取档校验 leverage 不超过该档最大值；未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。 */
    private void validateLeverageByBracket(String symbol, int leverage, BigDecimal notional) {
        int maxAllowed = bracketRegistry.getEffectiveMaxLeverage(symbol, notional);
        if (leverage > maxAllowed) {
            throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE.getCode(),
                    symbol + " 当前仓位档位最大支持 " + maxAllowed + "x，请求 " + leverage + "x 超限");
        }
    }

    private FuturesOrderResponse executeMarketOpen(Long userId, FuturesOpenRequest request, BigDecimal price, int leverage) {
        String marginMode = normalizeMarginMode(request.getMarginMode());
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, true);
        BigDecimal totalCost = margin.add(commission);

        if (FuturesPosition.CROSS.equals(marginMode)) {
            // 全仓占用制：钱不动，只校验可用额度（含浮盈亏，浮盈开仓天然成立）；手续费实扣
            crossMarginService.assertCanAfford(userId, totalCost);
            userMapper.atomicSettleBalance(userId, commission.negate());
        } else {
            // 逐仓划扣制：把保证金从余额钱包划走；对全仓池而言是资金流出，先过硬底线
            crossMarginService.assertOutflowAllowed(userId, totalCost);
            User user = userService.getById(userId);
            // 允许0.05 USDT的价格滑点容差，避免前后端价格时间差导致误报余额不足
            BigDecimal tolerance = tradingConfig.getFutures().getBalanceTolerance();
            if (user.getBalance().add(tolerance).compareTo(totalCost) < 0) {
                throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
            }
            int affected = userMapper.atomicUpdateBalance(userId, totalCost.negate());
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        FuturesPosition position = new FuturesPosition();
        position.setUserId(userId);
        position.setSymbol(request.getSymbol());
        position.setSide(request.getSide());
        position.setMarginMode(marginMode);
        position.setLeverage(leverage);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setMemo(request.getMemo());
        position.setStatus("OPEN");

        position.setStopLosses(buildStopLosses(request.getStopLosses(), request.getSide(), price, quantity));
        position.setTakeProfits(buildTakeProfits(request.getTakeProfits(), request.getSide(), price, quantity));

        positionMapper.insert(position);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(request.getSymbol());
        order.setOrderSide("LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
        order.setOrderType("MARKET");
        order.setMarginMode(marginMode);
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setFilledPrice(price);
        order.setFilledAmount(positionValue);
        order.setMarginAmount(margin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        positionIndexService.registerPositionIndex(position);
        if (position.isCross()) crossMarginService.refreshUserIndex(userId);

        log.info("futures市价开仓 userId={} {} side={} mode={} qty={} price={} leverage={} margin={}",
                userId, request.getSymbol(), request.getSide(), marginMode, quantity, price, leverage, margin);

        return buildOrderResponse(order);
    }

    private FuturesOrderResponse createLimitOpenOrder(Long userId, FuturesOpenRequest request, int leverage,
                                                       BigDecimal markPrice) {
        String marginMode = normalizeMarginMode(request.getMarginMode());
        BigDecimal limitPrice = request.getLimitPrice();
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = limitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        String orderSide = "LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT";
        boolean isTaker = isLimitTaker(orderSide, limitPrice, markPrice);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, isTaker);
        BigDecimal frozenAmount = margin.add(commission);

        if (FuturesPosition.CROSS.equals(marginMode)) {
            // 全仓挂单不物理冻结：frozenAmount 只作"挂单占用"记账，入库后自动进可用余额扣减项
            crossMarginService.assertCanAfford(userId, frozenAmount);
        } else {
            crossMarginService.assertOutflowAllowed(userId, frozenAmount);
            int affected = userMapper.atomicFreezeBalance(userId, frozenAmount);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setSymbol(request.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
        order.setMarginMode(marginMode);
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFrozenAmount(frozenAmount);
        order.setCommission(commission);
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);

        String side = request.getSide();
        order.setStopLosses(buildStopLosses(request.getStopLosses(), side, limitPrice, quantity));
        order.setTakeProfits(buildTakeProfits(request.getTakeProfits(), side, limitPrice, quantity));

        orderMapper.insert(order);

        addToLimitZSet(order, cacheService);

        log.info("futures限价开仓单 userId={} {} side={} qty={} limit={} mark={} feeType={} frozen={}",
                userId, request.getSymbol(), request.getSide(), quantity, limitPrice, markPrice,
                isTaker ? "TAKER" : "MAKER", frozenAmount);

        return buildOrderResponse(order);
    }

    // ==================== 平仓 ====================

    @Override
    public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            return SpringUtils.getAopProxy(this).doClosePosition(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FuturesOrderResponse doClosePosition(Long userId, FuturesCloseRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        // quantity 空=全平：锁内取实时持仓量，消除"查列表→加锁"窗口期数量变化（如SL/TP部分成交）的误差
        BigDecimal closeQty = request.getQuantity() != null ? request.getQuantity() : position.getQuantity();
        // ≤0 必须拦：负数会让 atomicPartialClose 的 quantity-(-x) 反向加仓、盈亏符号翻转
        if (closeQty.signum() <= 0 || closeQty.compareTo(position.getQuantity()) > 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }

        if ("MARKET".equals(request.getOrderType())) {
            return executeMarketClose(userId, position, closeQty);
        } else {
            BigDecimal markPrice = getMarkPrice(position.getSymbol());
            tradingConfig.validateLimitPrice(request.getLimitPrice(), markPrice);
            return createLimitCloseOrder(userId, position, closeQty, request.getLimitPrice(), markPrice);
        }
    }

    @Override
    public CloseAllResult closeAllPositions(Long userId) {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN"));

        int closed = 0;
        List<String> failures = new ArrayList<>();
        for (FuturesPosition pos : positions) {
            try {
                FuturesCloseRequest req = new FuturesCloseRequest();
                req.setPositionId(pos.getId());
                req.setOrderType("MARKET");   // quantity 不设=锁内全平
                closePosition(userId, req);   // 复用单仓锁+独立事务，单仓失败不影响已平的
                closed++;
            } catch (Exception e) {
                log.warn("一键全平单仓失败 userId={} posId={} symbol={}", userId, pos.getId(), pos.getSymbol(), e);
                failures.add(pos.getSymbol() + "#" + pos.getId() + ": " + e.getMessage());
            }
        }
        log.info("一键全平完成 userId={} closed={} failed={}", userId, closed, failures.size());
        return new CloseAllResult(closed, failures);
    }

    private FuturesOrderResponse executeMarketClose(Long userId, FuturesPosition position, BigDecimal closeQty) {
        BigDecimal currentPrice = getPrice(position.getSymbol());
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, closeQty);
        BigDecimal closeValue = currentPrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        // 占用/划扣两种记账：全仓保证金没离开过余额，平仓只结盈亏净额（可为负，亏穿走破产）；
        // 逐仓把仓位口袋里的钱(保证金±盈亏-费)拿回来，亏损上限就是保证金，返还不为负
        BigDecimal marginPart = isFullClose ? position.getMargin()
                : position.getMargin().multiply(closeQty).divide(position.getQuantity(), 2, RoundingMode.HALF_UP);

        if (isFullClose) {
            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", currentPrice, pnl);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
            positionIndexService.unregisterAll(position);
        } else {
            int affected = positionMapper.atomicPartialClose(position.getId(), closeQty, marginPart);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

            if (!position.isCross()) {
                // 部分平仓改了 qty/margin，逐仓静态强平索引必须同步（全仓无此索引）
                BigDecimal newQty = position.getQuantity().subtract(closeQty);
                BigDecimal newMargin = position.getMargin().subtract(marginPart);
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(
                        position.getSymbol(), position.getSide(), position.getEntryPrice(), newMargin, newQty);
                positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
            }
        }

        if (position.isCross()) {
            crossMarginService.settle(userId, pnl.subtract(commission));
        } else {
            userMapper.atomicUpdateBalance(userId, marginPart.add(pnl).subtract(commission).max(BigDecimal.ZERO));
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(currentPrice);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        log.info("futures市价平仓 userId={} posId={} mode={} qty={} price={} pnl={}",
                userId, position.getId(), position.getMarginMode(), closeQty, currentPrice, pnl);

        return buildOrderResponse(order);
    }

    private FuturesOrderResponse createLimitCloseOrder(Long userId, FuturesPosition position,
                                                        BigDecimal closeQty, BigDecimal limitPrice,
                                                        BigDecimal markPrice) {
        BigDecimal pendingCloseQty = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                        .eq(FuturesOrder::getUserId, userId)
                        .eq(FuturesOrder::getPositionId, position.getId())
                        .eq(FuturesOrder::getStatus, "PENDING")
                        .in(FuturesOrder::getOrderSide, "CLOSE_LONG", "CLOSE_SHORT"))
                .stream()
                .map(FuturesOrder::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal availableCloseQty = position.getQuantity().subtract(pendingCloseQty);
        if (closeQty.compareTo(availableCloseQty) > 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());
        String orderSide = "LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT";
        boolean isTaker = isLimitTaker(orderSide, limitPrice, markPrice);
        BigDecimal closeValue = limitPrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, isTaker);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setLimitPrice(limitPrice);
        order.setCommission(commission);
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);
        orderMapper.insert(order);

        addToLimitZSet(order, cacheService);

        log.info("futures限价平仓单 userId={} posId={} qty={} limit={} mark={} feeType={}",
                userId, position.getId(), closeQty, limitPrice, markPrice, isTaker ? "TAKER" : "MAKER");

        return buildOrderResponse(order);
    }

    // ==================== 取消限价单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        int affected = orderMapper.casUpdateStatus(orderId, "PENDING", "CANCELLED");
        if (affected == 0) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        removeFromLimitZSet(order, cacheService);

        // 逐仓开/加仓单解冻资金；全仓单没冻结过钱，状态一改挂单占用自动消失
        if (!order.getOrderSide().startsWith("CLOSE") && !FuturesPosition.CROSS.equals(order.getMarginMode())) {
            userMapper.atomicUnfreezeBalance(userId, order.getFrozenAmount());
        }

        order.setStatus("CANCELLED");
        log.info("futures取消限价单 userId={} orderId={}", userId, orderId);
        return buildOrderResponse(order);
    }

    // ==================== 追加保证金 ====================

    @Override
    public void addMargin(Long userId, FuturesAddMarginRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doAddMargin(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doAddMargin(Long userId, FuturesAddMarginRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());
        if (position.isCross()) throw new BizException(ErrorCode.FUTURES_CROSS_MARGIN_ADJUST);

        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // 往逐仓仓位里划钱 = 全仓池资金流出
        crossMarginService.assertOutflowAllowed(userId, amount);
        int affected = userMapper.atomicUpdateBalance(userId, amount.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        int affected2 = positionMapper.atomicAddMargin(request.getPositionId(), amount);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        BigDecimal newMargin = position.getMargin().add(amount);
        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(), position.getEntryPrice(),
                newMargin, position.getQuantity());
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        log.info("futures追加保证金 userId={} posId={} amount={}", userId, request.getPositionId(), amount);
    }

    // ==================== 减少保证金 ====================

    @Override
    public void reduceMargin(Long userId, FuturesReduceMarginRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doReduceMargin(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doReduceMargin(Long userId, FuturesReduceMarginRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());
        if (position.isCross()) throw new BizException(ErrorCode.FUTURES_CROSS_MARGIN_ADJUST);

        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        BigDecimal newMargin = position.getMargin().subtract(amount);
        if (newMargin.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);
        }

        BigDecimal markPrice = getMarkPrice(position.getSymbol());
        BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), markPrice, position.getQuantity());
        BigDecimal markNotional = markPrice.multiply(position.getQuantity());
        BigDecimal maintenanceMargin = bracketRegistry.calcMaintenanceMargin(position.getSymbol(), markNotional);
        BigDecimal markIM = markNotional.divide(BigDecimal.valueOf(position.getLeverage()), 2, RoundingMode.CEILING);
        // Binance 完整公式两条独立约束（min 取严）
        // (1) 减后承诺金 ≥ MM：仓位本身要有兜底，未实现盈利不可顶替
        if (newMargin.compareTo(maintenanceMargin) < 0) {
            throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);
        }
        // (2) 减后净值 ≥ markIM：防偷偷降杠杆
        if (newMargin.add(unrealizedPnl).compareTo(markIM) < 0) {
            throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);
        }

        int affected = positionMapper.atomicReduceMargin(position.getId(), amount);
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);

        int credited = userMapper.atomicUpdateBalance(userId, amount);
        if (credited == 0) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                position.getEntryPrice(), newMargin, position.getQuantity());
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        log.info("futures减少保证金 userId={} posId={} amount={}", userId, request.getPositionId(), amount);
    }

    // ==================== 调杠杆 ====================

    @Override
    public void adjustLeverage(Long userId, FuturesAdjustLeverageRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            SpringUtils.getAopProxy(this).doAdjustLeverage(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doAdjustLeverage(Long userId, FuturesAdjustLeverageRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());
        int newLeverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());
        if (newLeverage == position.getLeverage()) return;

        BigDecimal markPrice = getMarkPrice(position.getSymbol());
        validateLeverageByBracket(position.getSymbol(), newLeverage, markPrice.multiply(position.getQuantity()));

        // 新目标保证金 = 开仓名义额/新杠杆（与开仓口径一致）。举例：0.1 BTC@60000 用 10x 占 600，
        // 调到 5x 目标变 1200 要多占 600；调到 20x 目标 300 释放 300
        BigDecimal newMargin = position.getEntryPrice().multiply(position.getQuantity())
                .divide(BigDecimal.valueOf(newLeverage), 2, RoundingMode.CEILING);

        if (position.isCross()) {
            // 全仓占用制：只改占用数字，钱不动。调低多占的部分要过可用额度；调高白释放
            BigDecimal delta = newMargin.subtract(position.getMargin());
            if (delta.signum() > 0) crossMarginService.assertCanAfford(userId, delta);
            int affected = positionMapper.updateLeverageAndMargin(position.getId(), newLeverage, newMargin);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        } else {
            // 逐仓只能调高：调低=要补钱，走追加保证金即可，不重复做一套
            if (newLeverage < position.getLeverage()) throw new BizException(ErrorCode.FUTURES_LEVERAGE_ONLY_UP);

            BigDecimal release = position.getMargin().subtract(newMargin);
            if (release.signum() > 0) {
                // 释放多余保证金回钱包，安全约束与减保证金同一套：
                // (1) 剩余承诺金 ≥ 维持保证金 (2) 剩余净值 ≥ 新杠杆下的 markIM
                BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), markPrice, position.getQuantity());
                BigDecimal markNotional = markPrice.multiply(position.getQuantity());
                BigDecimal maintenanceMargin = bracketRegistry.calcMaintenanceMargin(position.getSymbol(), markNotional);
                BigDecimal markIM = markNotional.divide(BigDecimal.valueOf(newLeverage), 2, RoundingMode.CEILING);
                if (newMargin.compareTo(maintenanceMargin) < 0
                        || newMargin.add(unrealizedPnl).compareTo(markIM) < 0) {
                    throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);
                }

                int affected = positionMapper.updateLeverageAndMargin(position.getId(), newLeverage, newMargin);
                if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
                userMapper.atomicUpdateBalance(userId, release);

                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                        position.getEntryPrice(), newMargin, position.getQuantity());
                positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
            } else {
                // 保证金已被资金费吃到目标线以下：只改杠杆数字，不动钱
                int affected = positionMapper.updateLeverageAndMargin(position.getId(), newLeverage, position.getMargin());
                if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
            }
        }

        log.info("futures调杠杆 userId={} posId={} mode={} {}x→{}x margin={}",
                userId, position.getId(), position.getMarginMode(), position.getLeverage(), newLeverage, newMargin);
    }

    // ==================== 加仓 ====================

    @Override
    public FuturesOrderResponse increasePosition(Long userId, FuturesIncreaseRequest request) {
        if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            return SpringUtils.getAopProxy(this).doIncreasePosition(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FuturesOrderResponse doIncreasePosition(Long userId, FuturesIncreaseRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());
        getAndValidateUser(userId);

        if ("MARKET".equals(request.getOrderType())) {
            return executeMarketIncrease(userId, position, request.getQuantity());
        } else if ("LIMIT".equals(request.getOrderType())) {
            BigDecimal markPrice = getMarkPrice(position.getSymbol());
            tradingConfig.validateLimitPrice(request.getLimitPrice(), markPrice);
            return createLimitIncreaseOrder(userId, position, request.getQuantity(), request.getLimitPrice(), markPrice);
        } else {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private FuturesOrderResponse executeMarketIncrease(Long userId, FuturesPosition position, BigDecimal addQty) {
        BigDecimal price = getPrice(position.getSymbol());
        int leverage = position.getLeverage();

        BigDecimal addValue = price.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, true);
        BigDecimal totalCost = addMargin.add(commission);

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        // 加仓后 notional 跨档校验：按当前价×总持仓评估市值（对齐 Binance 实操），超档直接拒
        BigDecimal newNotional = price.multiply(newQty);
        validateLeverageByBracket(position.getSymbol(), leverage, newNotional);

        if (position.isCross()) {
            crossMarginService.assertCanAfford(userId, totalCost);
            userMapper.atomicSettleBalance(userId, commission.negate());
        } else {
            crossMarginService.assertOutflowAllowed(userId, totalCost);
            int affected = userMapper.atomicUpdateBalance(userId, totalCost.negate());
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(price.multiply(addQty))
                .divide(newQty, PRICE_SCALE, RoundingMode.HALF_UP);

        int affected2 = positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        if (!position.isCross()) {
            BigDecimal newMargin = position.getMargin().add(addMargin);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(), newEntryPrice, newMargin,
                    newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "INCREASE_LONG" : "INCREASE_SHORT");
        order.setOrderType("MARKET");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(addQty);
        order.setLeverage(leverage);
        order.setFilledPrice(price);
        order.setFilledAmount(addValue);
        order.setMarginAmount(addMargin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        log.info("futures市价加仓 userId={} posId={} addQty={} price={} addMargin={}",
                userId, position.getId(), addQty, price, addMargin);

        return buildOrderResponse(order);
    }

    private FuturesOrderResponse createLimitIncreaseOrder(Long userId, FuturesPosition position,
                                                           BigDecimal addQty, BigDecimal limitPrice,
                                                           BigDecimal markPrice) {
        int leverage = position.getLeverage();
        BigDecimal addValue = limitPrice.multiply(addQty).setScale(2, RoundingMode.HALF_UP);

        // 限价加仓挂单时即按当前 markPrice×总持仓评估市值校验档位（对齐 Binance 实操），避免成交时才拒单造成 PENDING 残留
        BigDecimal newNotional = markPrice.multiply(position.getQuantity().add(addQty));
        validateLeverageByBracket(position.getSymbol(), leverage, newNotional);

        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        String orderSide = "LONG".equals(position.getSide()) ? "INCREASE_LONG" : "INCREASE_SHORT";
        boolean isTaker = isLimitTaker(orderSide, limitPrice, markPrice);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, isTaker);
        BigDecimal frozenAmount = addMargin.add(commission);

        if (position.isCross()) {
            crossMarginService.assertCanAfford(userId, frozenAmount);
        } else {
            crossMarginService.assertOutflowAllowed(userId, frozenAmount);
            int affected = userMapper.atomicFreezeBalance(userId, frozenAmount);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(addQty);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFrozenAmount(frozenAmount);
        order.setCommission(commission);
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);
        orderMapper.insert(order);

        addToLimitZSet(order, cacheService);

        log.info("futures限价加仓单 userId={} posId={} addQty={} limit={} mark={} feeType={} frozen={}",
                userId, position.getId(), addQty, limitPrice, markPrice, isTaker ? "TAKER" : "MAKER", frozenAmount);

        return buildOrderResponse(order);
    }

    // ==================== 查询仓位 ====================

    @Override
    public List<FuturesPositionDTO> getUserPositions(Long userId, String symbol) {
        LambdaQueryWrapper<FuturesPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesPosition::getUserId, userId);
        wrapper.eq(FuturesPosition::getStatus, "OPEN");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesPosition::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesPosition::getCreatedAt);

        List<FuturesPosition> positions = positionMapper.selectList(wrapper);
        List<FuturesPositionDTO> result = new ArrayList<>();

        // 全仓强平价依赖账户整体状态，快照每用户算一次全列表共用
        CrossMarginService.CrossAccount crossAccount = positions.stream().anyMatch(FuturesPosition::isCross)
                ? crossMarginService.snapshot(userId) : null;

        // 持仓期已实现盈亏（开/加仓手续费+部分平仓净额）从订单流水一次聚合，全列表共用
        Map<Long, BigDecimal> realizedMap = sumRealizedByPosition(positions);

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal markPrice = getMarkPrice(pos.getSymbol());
                FuturesPositionDTO dto = buildPositionDTO(pos, markPrice, crossAccount);
                dto.setRealizedPnl(realizedMap.getOrDefault(pos.getId(), BigDecimal.ZERO));
                result.add(dto);
            } catch (Exception e) {
                log.warn("查询仓位价格失败 posId={}", pos.getId(), e);
            }
        }

        return result;
    }

    private Map<Long, BigDecimal> sumRealizedByPosition(List<FuturesPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        List<Long> ids = positions.stream().map(FuturesPosition::getId).toList();
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : orderMapper.sumRealizedPnlByPositionIds(ids)) {
            map.put(((Number) row.get("position_id")).longValue(), (BigDecimal) row.get("amount"));
        }
        return map;
    }

    @Override
    public List<FuturesPositionDTO> getClosedPositions(Long userId, String symbol, int limit) {
        LambdaQueryWrapper<FuturesPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesPosition::getUserId, userId);
        wrapper.in(FuturesPosition::getStatus, "CLOSED", "LIQUIDATED");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesPosition::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesPosition::getUpdatedAt);
        wrapper.last("LIMIT " + Math.clamp(limit, 1, 500));
        // 已平仓只出静态字段（closedPnl 即终值），不走 buildPositionDTO 的实时价计算
        return positionMapper.selectList(wrapper).stream().map(pos -> {
            FuturesPositionDTO dto = new FuturesPositionDTO();
            dto.setId(pos.getId());
            dto.setUserId(pos.getUserId());
            dto.setSymbol(pos.getSymbol());
            dto.setSide(pos.getSide());
            dto.setMarginMode(pos.getMarginMode());
            dto.setLeverage(pos.getLeverage());
            dto.setQuantity(pos.getQuantity());
            dto.setEntryPrice(pos.getEntryPrice());
            dto.setMargin(pos.getMargin());
            dto.setFundingFeeTotal(pos.getFundingFeeTotal());
            dto.setStatus(pos.getStatus());
            dto.setClosedPrice(pos.getClosedPrice());
            dto.setClosedPnl(pos.getClosedPnl());
            dto.setMemo(pos.getMemo());
            dto.setCreatedAt(pos.getCreatedAt());
            dto.setUpdatedAt(pos.getUpdatedAt());
            return dto;
        }).toList();
    }

    private FuturesPositionDTO buildPositionDTO(FuturesPosition pos, BigDecimal markPrice,
                                                CrossMarginService.CrossAccount crossAccount) {
        FuturesPositionDTO dto = new FuturesPositionDTO();
        dto.setId(pos.getId());
        dto.setUserId(pos.getUserId());
        dto.setSymbol(pos.getSymbol());
        dto.setSide(pos.getSide());
        dto.setMarginMode(pos.getMarginMode());
        dto.setLeverage(pos.getLeverage());
        dto.setQuantity(pos.getQuantity());
        dto.setEntryPrice(pos.getEntryPrice());
        dto.setMargin(pos.getMargin());
        dto.setFundingFeeTotal(pos.getFundingFeeTotal());
        dto.setStopLosses(pos.getStopLosses());
        dto.setTakeProfits(pos.getTakeProfits());
        dto.setStatus(pos.getStatus());
        dto.setClosedPrice(pos.getClosedPrice());
        dto.setClosedPnl(pos.getClosedPnl());
        dto.setMemo(pos.getMemo());
        dto.setCreatedAt(pos.getCreatedAt());
        dto.setUpdatedAt(pos.getUpdatedAt());

        dto.setCurrentPrice(getPrice(pos.getSymbol()));
        dto.setMarkPrice(markPrice);
        BigDecimal positionValue = markPrice.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        dto.setPositionValue(positionValue);

        BigDecimal unrealizedPnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), markPrice, pos.getQuantity());
        dto.setUnrealizedPnl(unrealizedPnl);

        BigDecimal unrealizedPnlPct = pos.getMargin().compareTo(BigDecimal.ZERO) > 0
                ? unrealizedPnl.divide(pos.getMargin(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        dto.setUnrealizedPnlPct(unrealizedPnlPct);

        BigDecimal effectiveMargin = pos.getMargin().add(unrealizedPnl);
        dto.setEffectiveMargin(effectiveMargin);

        BigDecimal maintenanceMargin = bracketRegistry.calcMaintenanceMargin(
                pos.getSymbol(), positionValue);
        dto.setMaintenanceMargin(maintenanceMargin);

        // 逐仓强平价是静态的；全仓强平价随余额和其他仓位浮盈亏动态变化，按当下账户状态估算
        BigDecimal liquidationPrice = pos.isCross() && crossAccount != null
                ? crossMarginService.estimateLiqPrice(pos, crossAccount)
                : positionIndexService.calcStaticLiqPrice(pos.getSymbol(), pos.getSide(), pos.getEntryPrice(),
                        pos.getMargin(), pos.getQuantity());
        dto.setLiquidationPrice(liquidationPrice);

        BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
        BigDecimal fundingFeePerCycle = entryValue.multiply(tradingConfig.getFutures().getFundingRate());
        dto.setFundingFeePerCycle(fundingFeePerCycle);

        return dto;
    }

    // ==================== 查询订单 ====================

    @Override
    public FuturesOrderResponse getOrder(Long userId, Long orderId) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return buildOrderResponse(order);
    }

    @Override
    public List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol) {
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getUserId, userId).eq(FuturesOrder::getStatus, "PENDING");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesOrder::getSymbol, symbol);
        }
        return orderMapper.selectList(wrapper).stream().map(FuturesHelper::buildOrderResponse).toList();
    }

    @Override
    public IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol) {
        Page<FuturesOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(FuturesOrder::getStatus, status);
        }
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesOrder::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesOrder::getCreatedAt);
        orderMapper.selectPage(page, wrapper);
        return page.convert(FuturesHelper::buildOrderResponse);
    }

    @Override
    public List<FuturesOrderResponse> getLatestOrders() {
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getStatus, "FILLED")
                .orderByDesc(FuturesOrder::getCreatedAt)
                .last("LIMIT 20");
        return orderMapper.selectList(wrapper).stream()
                .map(FuturesHelper::buildOrderResponse)
                .toList();
    }

    // ==================== 内部工具 ====================

    private BigDecimal getPrice(String symbol) {
        return FuturesHelper.latestFuturesPrice(cacheService, symbol);
    }

    private BigDecimal getMarkPrice(String symbol) {
        return FuturesHelper.markPrice(cacheService, symbol);
    }

    private boolean isLimitTaker(String orderSide, BigDecimal limitPrice, BigDecimal markPrice) {
        boolean buy = switch (orderSide) {
            case "OPEN_LONG", "INCREASE_LONG", "CLOSE_SHORT" -> true;
            case "OPEN_SHORT", "INCREASE_SHORT", "CLOSE_LONG" -> false;
            default -> throw new IllegalArgumentException("Invalid orderSide: " + orderSide);
        };
        return buy ? limitPrice.compareTo(markPrice) >= 0 : limitPrice.compareTo(markPrice) <= 0;
    }

    FuturesPosition getUserPosition(Long userId, Long positionId) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !position.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);
        }
        if (!"OPEN".equals(position.getStatus())) {
            throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        }
        return position;
    }

    private void getAndValidateUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (Boolean.TRUE.equals(user.getIsBankrupt())) throw new BizException(ErrorCode.USER_BANKRUPT);
    }
}
