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
import com.mawai.wiibsim.config.TradeFilterRegistry;
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
    private final TradeFilterRegistry tradeFilterRegistry;

    // ==================== 开仓 ====================

    @Override
    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        validateOpenRequest(request);
        // 币种锁串行化同币开仓/调杠杆：合并判定期间不允许另一笔开仓或调杠杆插队
        String symLockKey = "futures:sym:" + userId + ":" + request.getSymbol();
        String symLockValue = redisLockUtil.tryLock(symLockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (symLockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            // 同向已有仓位=加仓合并，还要压住该仓位与平仓/SLTP结算互斥（锁序 sym→pos，与调杠杆一致防死锁）
            FuturesPosition sameSide = openPositionsOf(userId, request.getSymbol()).stream()
                    .filter(p -> p.getSide().equals(request.getSide())).findFirst().orElse(null);
            if (sameSide == null) {
                return SpringUtils.getAopProxy(this).doOpenPosition(userId, request);
            }
            String posLockKey = "futures:pos:" + sameSide.getId();
            String posLockValue = redisLockUtil.tryLock(posLockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
            if (posLockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
            try {
                return SpringUtils.getAopProxy(this).doOpenPosition(userId, request);
            } finally {
                redisLockUtil.unlock(posLockKey, posLockValue);
            }
        } finally {
            redisLockUtil.unlock(symLockKey, symLockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FuturesOrderResponse doOpenPosition(Long userId, FuturesOpenRequest request) {
        getAndValidateUser(userId);

        int leverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());
        String marginMode = normalizeMarginMode(request.getMarginMode());

        // 币种级一致性（对齐Binance）：杠杆/保证金模式是币种级设置，与该币现有任一仓位冲突直接拒；
        // 锁内重查，外层只做锁发现
        List<FuturesPosition> existing = openPositionsOf(userId, request.getSymbol());
        validateSymbolConsistency(existing, marginMode, leverage);
        FuturesPosition sameSide = existing.stream()
                .filter(p -> p.getSide().equals(request.getSide())).findFirst().orElse(null);

        if ("MARKET".equals(request.getOrderType())) {
            BigDecimal price = getPrice(request.getSymbol());
            // 交易过滤器（对齐Binance exchangeInfo）：步长对齐 + 名义额≥minNotional，加仓单同样受限
            tradeFilterRegistry.validateFuturesOrder(request.getSymbol(), request.getQuantity(), price);
            // 同向已有仓位：Binance 无独立"加仓"概念，同向下单即并入现有仓位
            if (sameSide != null) return executeMarketMerge(userId, sameSide, request, price);
            // BTC/ETH 等已配置档位的 symbol 按 Binance 档位二次校验杠杆（按 notional 取档）
            validateLeverageByBracket(request.getSymbol(), leverage, price.multiply(request.getQuantity()));
            return executeMarketOpen(userId, request, price, leverage);
        } else {
            BigDecimal markPrice = getMarkPrice(request.getSymbol());
            tradingConfig.validateLimitPrice(request.getLimitPrice(), markPrice);
            tradeFilterRegistry.validateFuturesOrder(request.getSymbol(), request.getQuantity(), request.getLimitPrice());
            // 限价同向加仓：挂单按合并后总持仓过档（成交时并入由结算侧处理）
            BigDecimal bracketQty = sameSide != null ? sameSide.getQuantity().add(request.getQuantity()) : request.getQuantity();
            validateLeverageByBracket(request.getSymbol(), leverage, request.getLimitPrice().multiply(bracketQty));
            return createLimitOpenOrder(userId, request, leverage, markPrice);
        }
    }

    private List<FuturesPosition> openPositionsOf(Long userId, String symbol) {
        return positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, symbol)
                .eq(FuturesPosition::getStatus, "OPEN"));
    }

    /** 币种级一致性：杠杆与保证金模式对该币所有仓位统一（对齐Binance的symbol级设置） */
    private static void validateSymbolConsistency(List<FuturesPosition> existing, String marginMode, int leverage) {
        boolean reqCross = FuturesPosition.CROSS.equals(marginMode);
        for (FuturesPosition p : existing) {
            // isCross 判定兜住存量 marginMode=null 的老数据（视为逐仓）
            if (p.isCross() != reqCross) throw new BizException(ErrorCode.FUTURES_MARGIN_MODE_CONFLICT);
            if (p.getLeverage() != leverage) {
                throw new BizException(ErrorCode.FUTURES_LEVERAGE_MISMATCH.getCode(),
                        "该币现有仓位杠杆 " + p.getLeverage() + "x，请求 " + leverage + "x 不一致，请先调整杠杆");
            }
        }
    }

    /**
     * 同向市价单并入现有仓位（Binance 语义：同向下单即合并，均价按数量加权）。
     * 随单 SL/TP 以成交价为参照校验后追加到仓位现有档位（合计条数≤4、总量≤合并后持仓）。
     */
    private FuturesOrderResponse executeMarketMerge(Long userId, FuturesPosition position, FuturesOpenRequest request, BigDecimal price) {
        BigDecimal addQty = request.getQuantity();
        int leverage = position.getLeverage();

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        // 合并后 notional 跨档校验：按当前价×总持仓评估市值（对齐 Binance 实操），超档直接拒
        validateLeverageByBracket(position.getSymbol(), leverage, price.multiply(newQty));

        // 随单SL/TP先构建校验，不过关不动钱；null=本单未带，不改库
        List<FuturesStopLoss> addSl = buildStopLosses(request.getStopLosses(), position.getSide(), price, newQty);
        List<FuturesTakeProfit> addTp = buildTakeProfits(request.getTakeProfits(), position.getSide(), price, newQty);
        List<FuturesStopLoss> mergedSl = mergeSlList(position.getStopLosses(), addSl, newQty);
        List<FuturesTakeProfit> mergedTp = mergeTpList(position.getTakeProfits(), addTp, newQty);

        BigDecimal addValue = price.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, true);
        BigDecimal totalCost = addMargin.add(commission);

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

        if (mergedSl != null) positionMapper.updateStopLosses(position.getId(), mergedSl);
        if (mergedTp != null) positionMapper.updateTakeProfits(position.getId(), mergedTp);

        // 索引同步：新增SL/TP档位注册触发索引（存量档位已在册）；逐仓重算静态强平价
        if (addSl != null) positionIndexService.registerStopLosses(position.getId(), position.getSymbol(), position.getSide(), addSl);
        if (addTp != null) positionIndexService.registerTakeProfits(position.getId(), position.getSymbol(), position.getSide(), addTp);
        if (!position.isCross()) {
            BigDecimal newMargin = position.getMargin().add(addMargin);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                    newEntryPrice, newMargin, newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
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

        log.info("futures市价开仓并入 userId={} posId={} addQty={} price={} addMargin={} 新均价={}",
                userId, position.getId(), addQty, price, addMargin, newEntryPrice);

        return buildOrderResponse(order);
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
        // reduce-only 豁免最小名义额；部分平仓查步长，全量平仓豁免（存量尘埃仓能平干净）
        tradeFilterRegistry.validateFuturesClose(position.getSymbol(), closeQty, position.getQuantity());

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
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        }
        // 币种级操作（对齐Binance）：多空共用杠杆，一次调整作用于该币全部仓位。
        // 先拿币种锁挡住并发开仓，再逐张拿仓位锁（按id升序防死锁）与平仓/结算互斥
        String symLockKey = "futures:sym:" + userId + ":" + request.getSymbol();
        String symLockValue = redisLockUtil.tryLock(symLockKey, tradingConfig.getFutures().getLockTimeoutSeconds());
        if (symLockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                    .eq(FuturesPosition::getUserId, userId)
                    .eq(FuturesPosition::getSymbol, request.getSymbol())
                    .eq(FuturesPosition::getStatus, "OPEN")
                    .orderByAsc(FuturesPosition::getId));
            if (positions.isEmpty()) throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);

            List<String[]> posLocks = new ArrayList<>();
            try {
                for (FuturesPosition p : positions) {
                    String key = "futures:pos:" + p.getId();
                    String value = redisLockUtil.tryLock(key, tradingConfig.getFutures().getLockTimeoutSeconds());
                    if (value == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
                    posLocks.add(new String[]{key, value});
                }
                SpringUtils.getAopProxy(this).doAdjustLeverage(userId, request);
            } finally {
                for (int i = posLocks.size() - 1; i >= 0; i--) {
                    redisLockUtil.unlock(posLocks.get(i)[0], posLocks.get(i)[1]);
                }
            }
        } finally {
            redisLockUtil.unlock(symLockKey, symLockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doAdjustLeverage(Long userId, FuturesAdjustLeverageRequest request) {
        // 锁内重查：拿锁前仓位可能已被平掉/强平
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, request.getSymbol())
                .eq(FuturesPosition::getStatus, "OPEN")
                .orderByAsc(FuturesPosition::getId));
        if (positions.isEmpty()) throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);

        int newLeverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());
        if (positions.stream().allMatch(p -> p.getLeverage() == newLeverage)) return;

        BigDecimal markPrice = getMarkPrice(request.getSymbol());

        // 先全量校验再落库：任何一张不过整体拒绝（事务兜底，但不做半程无用功）
        for (FuturesPosition p : positions) {
            validateLeverageByBracket(p.getSymbol(), newLeverage, markPrice.multiply(p.getQuantity()));
            // 逐仓只能调高：调低=要补钱，走追加保证金即可，不重复做一套
            if (!p.isCross() && newLeverage < p.getLeverage()) throw new BizException(ErrorCode.FUTURES_LEVERAGE_ONLY_UP);
        }

        // 全仓占用增量合并过闸：多空一起调低时两张都多占，可用余额一次性校验（正负互抵后为净增量才拦）
        BigDecimal crossDelta = BigDecimal.ZERO;
        for (FuturesPosition p : positions) {
            if (p.isCross()) crossDelta = crossDelta.add(targetMargin(p, newLeverage).subtract(p.getMargin()));
        }
        if (crossDelta.signum() > 0) crossMarginService.assertCanAfford(userId, crossDelta);

        for (FuturesPosition p : positions) {
            if (p.isCross()) {
                adjustCrossLeverage(p, newLeverage);
            } else {
                adjustIsolatedLeverage(userId, p, newLeverage, markPrice);
            }
        }

        log.info("futures调杠杆 userId={} symbol={} →{}x 仓位数={}", userId, request.getSymbol(), newLeverage, positions.size());
    }

    /** 新目标保证金 = 开仓名义额/新杠杆（与开仓口径一致）。举例：0.1 BTC@60000 用 10x 占 600，
     *  调到 5x 目标变 1200 要多占 600；调到 20x 目标 300 释放 300 */
    private static BigDecimal targetMargin(FuturesPosition position, int newLeverage) {
        return position.getEntryPrice().multiply(position.getQuantity())
                .divide(BigDecimal.valueOf(newLeverage), 2, RoundingMode.CEILING);
    }

    private void adjustCrossLeverage(FuturesPosition position, int newLeverage) {
        // 全仓占用制：只改占用数字，钱不动（可用额度已在上层按币种合并校验）
        int affected = positionMapper.updateLeverageAndMargin(position.getId(), newLeverage, targetMargin(position, newLeverage));
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
    }

    private void adjustIsolatedLeverage(Long userId, FuturesPosition position, int newLeverage, BigDecimal markPrice) {
        BigDecimal newMargin = targetMargin(position, newLeverage);
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
            case "OPEN_LONG", "CLOSE_SHORT" -> true;
            case "OPEN_SHORT", "CLOSE_LONG" -> false;
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
