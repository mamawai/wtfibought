package com.mawai.wiibservice.service.impl;

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
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import com.mawai.wiibservice.service.FuturesTradingService;
import com.mawai.wiibservice.service.TradeAttributionService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.task.AiTradingScheduler;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.mawai.wiibservice.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesTradingServiceImpl implements FuturesTradingService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final TradeAttributionService tradeAttributionService;

    // ==================== 开仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        validateOpenRequest(request);
        getAndValidateUser(userId);

        BigDecimal price = getPrice(request.getSymbol());
        int leverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());

        if ("MARKET".equals(request.getOrderType())) {
            return executeMarketOpen(userId, request, price, leverage);
        } else {
            BigDecimal markPrice = getMarkPrice(request.getSymbol());
            tradingConfig.validateLimitPrice(request.getLimitPrice(), markPrice);
            return createLimitOpenOrder(userId, request, leverage, markPrice);
        }
    }

    private FuturesOrderResponse executeMarketOpen(Long userId, FuturesOpenRequest request, BigDecimal price, int leverage) {
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, true);
        BigDecimal totalCost = margin.add(commission);

        User user = userService.getById(userId);
        // 允许0.05 USDT的价格滑点容差，避免前后端价格时间差导致误报余额不足
        BigDecimal tolerance = tradingConfig.getFutures().getBalanceTolerance();
        if (user.getBalance().add(tolerance).compareTo(totalCost) < 0) {
            throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        int affected = userMapper.atomicUpdateBalance(userId, totalCost.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        FuturesPosition position = new FuturesPosition();
        position.setUserId(userId);
        position.setSymbol(request.getSymbol());
        position.setSide(request.getSide());
        position.setLeverage(leverage);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setMemo(request.getMemo());
        position.setStatus("OPEN");

        List<FuturesOpenRequest.StopLoss> stopLosses = request.getStopLosses();
        if (stopLosses != null && !stopLosses.isEmpty()) {
            if (stopLosses.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
            BigDecimal slTotal = BigDecimal.ZERO;
            List<FuturesStopLoss> slList = new ArrayList<>();
            for (FuturesOpenRequest.StopLoss sl : stopLosses) {
                validateSlPrice(sl.getPrice(), request.getSide(), price);
                validateQty(sl.getQuantity());
                slTotal = slTotal.add(sl.getQuantity());
                slList.add(new FuturesStopLoss(genId(), sl.getPrice(), sl.getQuantity()));
            }
            if (slTotal.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
            position.setStopLosses(slList);
        }
        List<FuturesOpenRequest.TakeProfit> takeProfits = request.getTakeProfits();
        if (takeProfits != null && !takeProfits.isEmpty()) {
            if (takeProfits.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
            BigDecimal tpTotal = BigDecimal.ZERO;
            List<FuturesTakeProfit> tpList = new ArrayList<>();
            for (FuturesOpenRequest.TakeProfit tp : takeProfits) {
                validateTpPrice(tp.getPrice(), request.getSide(), price);
                validateQty(tp.getQuantity());
                tpTotal = tpTotal.add(tp.getQuantity());
                tpList.add(new FuturesTakeProfit(genId(), tp.getPrice(), tp.getQuantity()));
            }
            if (tpTotal.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
            position.setTakeProfits(tpList);
        }

        positionMapper.insert(position);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(request.getSymbol());
        order.setOrderSide("LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setFilledPrice(price);
        order.setFilledAmount(positionValue);
        order.setMarginAmount(margin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        positionIndexService.registerPositionIndex(position);

        log.info("futures市价开仓 userId={} {} side={} qty={} price={} leverage={} margin={}",
                userId, request.getSymbol(), request.getSide(), quantity, price, leverage, margin);

        return buildOrderResponse(order);
    }

    private FuturesOrderResponse createLimitOpenOrder(Long userId, FuturesOpenRequest request, int leverage,
                                                       BigDecimal markPrice) {
        BigDecimal limitPrice = request.getLimitPrice();
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = limitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        String orderSide = "LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT";
        boolean isTaker = isLimitTaker(orderSide, limitPrice, markPrice);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, isTaker);
        BigDecimal frozenAmount = margin.add(commission);

        int affected = userMapper.atomicFreezeBalance(userId, frozenAmount);
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setSymbol(request.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFrozenAmount(frozenAmount);
        order.setCommission(commission);
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);

        String side = request.getSide();
        List<FuturesOpenRequest.StopLoss> stopLosses = request.getStopLosses();
        if (stopLosses != null && !stopLosses.isEmpty()) {
            if (stopLosses.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
            BigDecimal slTotal = BigDecimal.ZERO;
            List<FuturesStopLoss> slList = new ArrayList<>();
            for (FuturesOpenRequest.StopLoss sl : stopLosses) {
                validateSlPrice(sl.getPrice(), side, limitPrice);
                validateQty(sl.getQuantity());
                slTotal = slTotal.add(sl.getQuantity());
                slList.add(new FuturesStopLoss(genId(), sl.getPrice(), sl.getQuantity()));
            }
            if (slTotal.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
            order.setStopLosses(slList);
        }
        List<FuturesOpenRequest.TakeProfit> takeProfits = request.getTakeProfits();
        if (takeProfits != null && !takeProfits.isEmpty()) {
            if (takeProfits.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
            BigDecimal tpTotal = BigDecimal.ZERO;
            List<FuturesTakeProfit> tpList = new ArrayList<>();
            for (FuturesOpenRequest.TakeProfit tp : takeProfits) {
                validateTpPrice(tp.getPrice(), side, limitPrice);
                validateQty(tp.getQuantity());
                tpTotal = tpTotal.add(tp.getQuantity());
                tpList.add(new FuturesTakeProfit(genId(), tp.getPrice(), tp.getQuantity()));
            }
            if (tpTotal.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
            order.setTakeProfits(tpList);
        }

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

        BigDecimal closeQty = request.getQuantity();
        if (closeQty.compareTo(position.getQuantity()) > 0) {
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

    private FuturesOrderResponse executeMarketClose(Long userId, FuturesPosition position, BigDecimal closeQty) {
        BigDecimal currentPrice = getPrice(position.getSymbol());
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, closeQty);
        BigDecimal closeValue = currentPrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);

            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", currentPrice, pnl);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

            positionIndexService.unregisterAll(position);
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(closeQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);

            int affected = positionMapper.atomicPartialClose(position.getId(), closeQty, marginReturn);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        }

        userMapper.atomicUpdateBalance(userId, returnAmount);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(currentPrice);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("FILLED");
        orderMapper.insert(order);
        if (isFullClose) {
            tradeAttributionService.recordExit(position, pnl, "UNKNOWN");
        }

        log.info("futures市价平仓 userId={} posId={} qty={} price={} pnl={} return={}",
                userId, position.getId(), closeQty, currentPrice, pnl, returnAmount);

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

        if (!order.getOrderSide().startsWith("CLOSE")) {
            userMapper.atomicUnfreezeBalance(userId, order.getFrozenAmount());
        }

        order.setStatus("CANCELLED");
        log.info("futures取消限价单 userId={} orderId={}", userId, orderId);
        return buildOrderResponse(order);
    }

    // ==================== 追加保证金 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMargin(Long userId, FuturesAddMarginRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        int affected = userMapper.atomicUpdateBalance(userId, amount.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        int affected2 = positionMapper.atomicAddMargin(request.getPositionId(), amount);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        BigDecimal newMargin = position.getMargin().add(amount);
        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), position.getEntryPrice(), newMargin, position.getQuantity());
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        log.info("futures追加保证金 userId={} posId={} amount={}", userId, request.getPositionId(), amount);
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

        int affected = userMapper.atomicUpdateBalance(userId, totalCost.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(price.multiply(addQty))
                .divide(newQty, 2, RoundingMode.HALF_UP);

        int affected2 = positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        BigDecimal newMargin = position.getMargin().add(addMargin);
        BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), newEntryPrice, newMargin, newQty);
        positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "INCREASE_LONG" : "INCREASE_SHORT");
        order.setOrderType("MARKET");
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
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        String orderSide = "LONG".equals(position.getSide()) ? "INCREASE_LONG" : "INCREASE_SHORT";
        boolean isTaker = isLimitTaker(orderSide, limitPrice, markPrice);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, isTaker);
        BigDecimal frozenAmount = addMargin.add(commission);

        int affected = userMapper.atomicFreezeBalance(userId, frozenAmount);
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
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

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal markPrice = getMarkPrice(pos.getSymbol());
                result.add(buildPositionDTO(pos, markPrice));
            } catch (Exception e) {
                log.warn("查询仓位价格失败 posId={}", pos.getId(), e);
            }
        }

        return result;
    }

    private FuturesPositionDTO buildPositionDTO(FuturesPosition pos, BigDecimal markPrice) {
        FuturesPositionDTO dto = new FuturesPositionDTO();
        dto.setId(pos.getId());
        dto.setUserId(pos.getUserId());
        dto.setSymbol(pos.getSymbol());
        dto.setSide(pos.getSide());
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

        BigDecimal maintenanceMargin = positionValue.multiply(tradingConfig.getFutures().getMaintenanceMarginRate());
        dto.setMaintenanceMargin(maintenanceMargin);

        BigDecimal liquidationPrice = positionIndexService.calcStaticLiqPrice(pos.getSide(), pos.getEntryPrice(), pos.getMargin(), pos.getQuantity());
        dto.setLiquidationPrice(liquidationPrice);

        BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
        BigDecimal fundingFeePerCycle = entryValue.multiply(tradingConfig.getFutures().getFundingRate());
        dto.setFundingFeePerCycle(fundingFeePerCycle);

        return dto;
    }

    // ==================== 查询订单 ====================

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
        Long aiUserId = resolveAiUserId();
        return orderMapper.selectList(wrapper).stream()
                .map(order -> {
                    FuturesOrderResponse resp = FuturesHelper.buildOrderResponse(order);
                    if (aiUserId != null && aiUserId.equals(order.getUserId())) {
                        resp.setIsAiTrader(true);
                    }
                    return resp;
                }).toList();
    }

    private Long resolveAiUserId() {
        try {
            var scheduler = SpringUtils.getBean(AiTradingScheduler.class);
            Long id = scheduler.getAiUserId();
            return (id != null && id != 0) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部工具 ====================

    private BigDecimal getPrice(String symbol) {
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price != null) return price;
        price = cacheService.getMarkPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    private BigDecimal getMarkPrice(String symbol) {
        BigDecimal mp = cacheService.getMarkPrice(symbol);
        if (mp != null) return mp;
        return getPrice(symbol);
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
