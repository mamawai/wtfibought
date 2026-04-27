package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.OrderSide;
import com.mawai.wiibcommon.enums.OrderStatus;
import com.mawai.wiibcommon.enums.OrderType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import com.mawai.wiibservice.service.BuffService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.CryptoOrderService;
import com.mawai.wiibservice.service.CryptoPositionService;
import com.mawai.wiibservice.service.MarginAccountService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoOrderServiceImpl extends ServiceImpl<CryptoOrderMapper, CryptoOrder> implements CryptoOrderService {

    private final UserService userService;
    private final CryptoPositionService cryptoPositionService;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final MarginAccountService marginAccountService;
    private final BuffService buffService;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheService cacheService;

    private static final String SETTLE_ZSET_KEY = "crypto:settle:pending";
    private static final long SETTLE_DELAY_MS = 5 * 60 * 1000L; // btc 到账时间 5 minutes
    private static final int TRIGGERED_ORDER_BATCH_SIZE = 200;
    private static final String LIMIT_BUY_ZSET_PREFIX = "crypto:limit:buy:";
    private static final String LIMIT_SELL_ZSET_PREFIX = "crypto:limit:sell:";

    private ScheduledExecutorService settleScheduler;
    private final AtomicReference<java.util.concurrent.ScheduledFuture<?>> nextSettleTask = new AtomicReference<>();

    @PostConstruct
    void initScheduler() {
        settleScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("crypto-settle-", 0).factory());
        scheduleFromEarliest();
        rebuildLimitOrderZSets();
    }

    @PreDestroy
    void destroyScheduler() {
        if (settleScheduler != null) settleScheduler.shutdownNow();
    }

    // ==================== 获取实时价格 ====================

    private BigDecimal getCryptoPrice(String symbol) {
        BigDecimal price = cacheService.getCryptoPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    // ==================== 买入 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CryptoOrderResponse buy(Long userId, CryptoOrderRequest request) {
        validateRequest(request);
        User user = getAndValidateUser(userId);
        BigDecimal price = getCryptoPrice(request.getSymbol());
        int leverageMultiple = marginAccountService.normalizeLeverageMultiple(request.getLeverageMultiple());

        // 市价
        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            BigDecimal amount = price.multiply(request.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = tradingConfig.calculateCryptoCommission(amount);

            BigDecimal discountRate = null;
            if (request.getUseBuffId() != null) {
                if (leverageMultiple > 1) throw new BizException(ErrorCode.DISCOUNT_NO_LEVERAGE);
                discountRate = buffService.getDiscountRate(userId, request.getUseBuffId());
                if (discountRate != null) {
                    amount = amount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
                    commission = tradingConfig.calculateCryptoCommission(amount);
                }
            }

            if (leverageMultiple <= 1) {
                BigDecimal totalCost = amount.add(commission);
                if (user.getBalance().compareTo(totalCost) < 0) throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
                BigDecimal discountPercent = discountRate != null ? discountRate.multiply(BigDecimal.valueOf(100)) : null;
                CryptoOrderResponse resp = executeMarketBuy(userId, request.getSymbol(), request.getQuantity(), price, amount, commission, discountPercent);
                if (discountRate != null) buffService.markUsed(request.getUseBuffId());
                return resp;
            }

            if (!tradingConfig.getMargin().isEnabled() || leverageMultiple > tradingConfig.getMargin().getMaxLeverage()) {
                throw new BizException(ErrorCode.LEVERAGE_MULTIPLE_INVALID);
            }
            BigDecimal margin = amount.divide(BigDecimal.valueOf(leverageMultiple), 2, RoundingMode.CEILING);
            BigDecimal borrowed = amount.subtract(margin);
            BigDecimal cashNeed = margin.add(commission);
            if (user.getBalance().compareTo(cashNeed) < 0) throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            return executeMarketBuyWithLeverage(userId, request.getSymbol(), request.getQuantity(), price, amount, commission, margin, borrowed, leverageMultiple);
        }

        // 限价买单
        if (leverageMultiple > 1) throw new BizException(ErrorCode.LEVERAGE_ONLY_FOR_MARKET_BUY);
        tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
        BigDecimal freezeAmount = request.getLimitPrice().multiply(request.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimatedCommission = tradingConfig.calculateCryptoCommission(freezeAmount);
        BigDecimal totalFreeze = freezeAmount.add(estimatedCommission);
        if (user.getBalance().compareTo(totalFreeze) < 0) throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        return createLimitBuyOrder(userId, request, totalFreeze);
    }

    // ==================== 卖出 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CryptoOrderResponse sell(Long userId, CryptoOrderRequest request) {
        validateRequest(request);
        getAndValidateUser(userId);

        CryptoPosition position = cryptoPositionService.findByUserAndSymbol(userId, request.getSymbol());
        if (position == null || position.getQuantity().compareTo(request.getQuantity()) < 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }

        BigDecimal price = getCryptoPrice(request.getSymbol());

        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            BigDecimal amount = price.multiply(request.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = tradingConfig.calculateCryptoCommission(amount);
            return executeMarketSell(userId, request.getSymbol(), request.getQuantity(), price, amount, commission);
        }

        tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
        return createLimitSellOrder(userId, request);
    }

    // ==================== 取消 ====================

    @Override
    public CryptoOrderResponse cancel(Long userId, Long orderId) {
        String lockKey = "crypto:order:execute:" + orderId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            return SpringUtils.getAopProxy(this).doCancelOrder(userId, orderId);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected CryptoOrderResponse doCancelOrder(Long userId, Long orderId) {
        getAndValidateUser(userId);
        CryptoOrder order = baseMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        int affected = baseMapper.casUpdateStatus(orderId, OrderStatus.PENDING.getCode(), OrderStatus.CANCELLED.getCode());
        if (affected == 0) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        removeFromLimitZSet(order);

        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            userService.unfreezeBalance(userId, order.getFrozenAmount());
        } else {
            cryptoPositionService.unfreezePosition(userId, order.getSymbol(), order.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED.getCode());
        return buildResponse(order);
    }

    // ==================== 查询 ====================

    @Override
    public IPage<CryptoOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol) {
        Page<CryptoOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<CryptoOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CryptoOrder::getUserId, userId);
        if (status != null && !status.isEmpty()) wrapper.eq(CryptoOrder::getStatus, status);
        wrapper.orderByDesc(CryptoOrder::getCreatedAt);
        wrapper.eq(CryptoOrder::getSymbol, symbol);
        baseMapper.selectPage(page, wrapper);
        return page.convert(this::buildResponse);
    }

    // ==================== 市价买入执行 ====================

    private CryptoOrderResponse executeMarketBuy(Long userId, String symbol, BigDecimal quantity,
                                                  BigDecimal price, BigDecimal amount, BigDecimal commission,
                                                  BigDecimal discountPercent) {
        userService.updateBalance(userId, amount.add(commission).negate());
        BigDecimal discount = BigDecimal.ZERO;
        if (discountPercent != null) {
            BigDecimal originalAmount = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            discount = originalAmount.subtract(amount);
        }
        cryptoPositionService.addPosition(userId, symbol, quantity, price, discount);

        CryptoOrder order = buildOrder(userId, symbol, OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, 1, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), null);
        order.setDiscountPercent(discountPercent); // 折扣率
        baseMapper.insert(order);
        log.info("crypto市价买入 userId={} {} qty={} price={} amount={}", userId, symbol, quantity, price, amount);
        return buildResponse(order);
    }

    private CryptoOrderResponse executeMarketBuyWithLeverage(Long userId, String symbol, BigDecimal quantity,
                                                              BigDecimal price, BigDecimal amount, BigDecimal commission,
                                                              BigDecimal margin, BigDecimal borrowed, int leverage) {
        userService.updateBalance(userId, margin.add(commission).negate());
        marginAccountService.addLoanPrincipal(userId, borrowed);
        cryptoPositionService.addPosition(userId, symbol, quantity, price, BigDecimal.ZERO);

        CryptoOrder order = buildOrder(userId, symbol, OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, leverage, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), null);
        baseMapper.insert(order);
        log.info("crypto杠杆买入 userId={} {} qty={} price={} leverage={} borrowed={}", userId, symbol, quantity, price, leverage, borrowed);
        return buildResponse(order);
    }

    // ==================== 市价卖出执行（5min延迟到账） ====================

    private CryptoOrderResponse executeMarketSell(Long userId, String symbol, BigDecimal quantity,
                                                   BigDecimal price, BigDecimal amount, BigDecimal commission) {
        BigDecimal netAmount = amount.subtract(commission);
        cryptoPositionService.reducePosition(userId, symbol, quantity);

        CryptoOrder order = buildOrder(userId, symbol, OrderSide.SELL.getCode(), OrderType.MARKET.getCode(),
                quantity, 1, null, price, amount, commission, null, OrderStatus.SETTLING.getCode(), null);
        baseMapper.insert(order);

        // 延迟5min到账，塞入Redis ZSet
        addSettlement(userId, order.getId(), netAmount);

        log.info("crypto市价卖出 userId={} {} qty={} price={} net={} (5min到账)", userId, symbol, quantity, price, netAmount);
        return buildResponse(order);
    }

    // ==================== 限价单创建 ====================

    private CryptoOrderResponse createLimitBuyOrder(Long userId, CryptoOrderRequest request, BigDecimal freezeAmount) {
        userService.freezeBalance(userId, freezeAmount);
        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        CryptoOrder order = buildOrder(userId, request.getSymbol(), OrderSide.BUY.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), 1, request.getLimitPrice(), null, null, null, freezeAmount, OrderStatus.PENDING.getCode(), expireAt);
        baseMapper.insert(order);
        addToLimitZSet(order);
        log.info("crypto限价买单 userId={} {} qty={} limit={} frozen={}", userId, request.getSymbol(), request.getQuantity(), request.getLimitPrice(), freezeAmount);
        return buildResponse(order);
    }

    private CryptoOrderResponse createLimitSellOrder(Long userId, CryptoOrderRequest request) {
        cryptoPositionService.freezePosition(userId, request.getSymbol(), request.getQuantity());
        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        CryptoOrder order = buildOrder(userId, request.getSymbol(), OrderSide.SELL.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), 1, request.getLimitPrice(), null, null, null, null, OrderStatus.PENDING.getCode(), expireAt);
        baseMapper.insert(order);
        addToLimitZSet(order);
        log.info("crypto限价卖单 userId={} {} qty={} limit={}", userId, request.getSymbol(), request.getQuantity(), request.getLimitPrice());
        return buildResponse(order);
    }

    // ==================== 触发限价单 ====================

    @Transactional(rollbackFor = Exception.class)
    protected void markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = baseMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) log.info("crypto限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
    }

    // ==================== 执行已触发的限价单 ====================

    @Override
    public void executeTriggeredOrders() {
        long lastId = 0L;
        int successCount = 0, failCount = 0;

        for (;;) {
            List<CryptoOrder> batch = baseMapper.selectList(new LambdaQueryWrapper<CryptoOrder>()
                    .eq(CryptoOrder::getStatus, OrderStatus.TRIGGERED.getCode())
                    .eq(CryptoOrder::getOrderType, OrderType.LIMIT.getCode())
                    .gt(CryptoOrder::getId, lastId)
                    .orderByAsc(CryptoOrder::getId)
                    .last("LIMIT " + TRIGGERED_ORDER_BATCH_SIZE));
            if (batch.isEmpty()) break;
            lastId = batch.getLast().getId();

            for (CryptoOrder order : batch) {
                try {
                    if (SpringUtils.getAopProxy(this).processTriggeredOrder(order)) successCount++;
                } catch (Exception e) {
                    log.error("crypto执行触发订单失败 orderId={}", order.getId(), e);
                    failCount++;
                }
            }
        }
        if (successCount > 0 || failCount > 0) {
            log.info("crypto已触发订单执行完成 成功{} 失败{}", successCount, failCount);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected boolean processTriggeredOrder(CryptoOrder order) {
        if (!OrderStatus.TRIGGERED.getCode().equals(order.getStatus())) return false;

        User user = userService.getById(order.getUserId());
        if (user != null && Boolean.TRUE.equals(user.getIsBankrupt())) {
            baseMapper.casUpdateStatus(order.getId(), OrderStatus.TRIGGERED.getCode(), OrderStatus.CANCELLED.getCode());
            return false;
        }

        BigDecimal executePrice = order.getTriggerPrice();
        if (executePrice == null) return false;

        BigDecimal amount = executePrice.multiply(order.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(amount);

        boolean isSell = OrderSide.SELL.getCode().equals(order.getOrderSide());
        int affected = isSell
                ? baseMapper.casUpdateToSettling(order.getId(), executePrice, amount, commission)
                : baseMapper.casUpdateToFilled(order.getId(), executePrice, amount, commission);
        if (affected == 0) return false;

        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            BigDecimal actualCost = amount.add(commission);
            BigDecimal refund = frozenAmount.subtract(actualCost);
            userService.deductFrozenBalance(order.getUserId(), frozenAmount);
            if (refund.compareTo(BigDecimal.ZERO) > 0) userService.updateBalance(order.getUserId(), refund);
            cryptoPositionService.addPosition(order.getUserId(), order.getSymbol(), order.getQuantity(), executePrice, BigDecimal.ZERO);
        } else {
            cryptoPositionService.deductFrozenPosition(order.getUserId(), order.getSymbol(), order.getQuantity());
            BigDecimal netAmount = amount.subtract(commission);
            addSettlement(order.getUserId(), order.getId(), netAmount);
        }
        return true;
    }

    // ==================== 过期限价单处理 ====================

    @Override
    public void expireLimitOrders() {
        List<CryptoOrder> expiredOrders = baseMapper.selectList(new LambdaQueryWrapper<CryptoOrder>()
                .eq(CryptoOrder::getStatus, OrderStatus.PENDING.getCode())
                .eq(CryptoOrder::getOrderType, OrderType.LIMIT.getCode())
                .lt(CryptoOrder::getExpireAt, LocalDateTime.now()));
        if (expiredOrders.isEmpty()) return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        Semaphore semaphore = new Semaphore(maxConcurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (CryptoOrder order : expiredOrders) {
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

    private void processExpiredOrder(CryptoOrder order) {
        String lockKey = "crypto:order:execute:" + order.getId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            SpringUtils.getAopProxy(this).doExpireOrder(order);
        } catch (Exception e) {
            log.error("crypto过期订单处理失败 orderId={}", order.getId(), e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doExpireOrder(CryptoOrder order) {
        int affected = baseMapper.casUpdateStatus(order.getId(), OrderStatus.PENDING.getCode(), OrderStatus.EXPIRED.getCode());
        if (affected == 0) return;

        removeFromLimitZSet(order);

        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            userService.unfreezeBalance(order.getUserId(), order.getFrozenAmount());
        } else {
            cryptoPositionService.unfreezePosition(order.getUserId(), order.getSymbol(), order.getQuantity());
        }
    }

    // ==================== WS事件驱动限价单 ====================

    @Override
    public void onPriceUpdate(String symbol, BigDecimal price) {
        String buyKey = LIMIT_BUY_ZSET_PREFIX + symbol;
        String sellKey = LIMIT_SELL_ZSET_PREFIX + symbol;

        // 买单：limitPrice >= currentPrice → 触发
        Set<String> buyHits = stringRedisTemplate.opsForZSet().rangeByScore(buyKey, price.doubleValue(), Double.MAX_VALUE);
        // 卖单：limitPrice <= currentPrice → 触发
        Set<String> sellHits = stringRedisTemplate.opsForZSet().rangeByScore(sellKey, 0, price.doubleValue());

        boolean noBuy = buyHits == null || buyHits.isEmpty();
        boolean noSell = sellHits == null || sellHits.isEmpty();
        if (noBuy && noSell) return;

        if (!noBuy) {
            stringRedisTemplate.opsForZSet().remove(buyKey, buyHits.toArray());
            for (String id : buyHits) triggerAndExecuteOrder(Long.parseLong(id), price);
        }
        if (!noSell) {
            stringRedisTemplate.opsForZSet().remove(sellKey, sellHits.toArray());
            for (String id : sellHits) triggerAndExecuteOrder(Long.parseLong(id), price);
        }
    }

    private void triggerAndExecuteOrder(Long orderId, BigDecimal triggerPrice) {
        String lockKey = "crypto:order:execute:" + orderId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            var proxy = SpringUtils.getAopProxy(this);
            proxy.markOrderTriggered(orderId, triggerPrice);
            CryptoOrder order = baseMapper.selectById(orderId);
            if (order != null && OrderStatus.TRIGGERED.getCode().equals(order.getStatus())) {
                proxy.processTriggeredOrder(order);
            }
        } catch (Exception e) {
            log.error("crypto限价单即时执行失败 orderId={}", orderId, e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    // ==================== 重启恢复限价单 ====================

    @Override
    public void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh) {
        String buyKey = LIMIT_BUY_ZSET_PREFIX + symbol;
        String sellKey = LIMIT_SELL_ZSET_PREFIX + symbol;

        Set<ZSetOperations.TypedTuple<String>> buyHits = stringRedisTemplate.opsForZSet()
                .rangeByScoreWithScores(buyKey, periodLow.doubleValue(), Double.MAX_VALUE);
        Set<ZSetOperations.TypedTuple<String>> sellHits = stringRedisTemplate.opsForZSet()
                .rangeByScoreWithScores(sellKey, 0, periodHigh.doubleValue());

        boolean noBuy = buyHits == null || buyHits.isEmpty();
        boolean noSell = sellHits == null || sellHits.isEmpty();
        if (noBuy && noSell) return;

        int count = 0;
        count = getCount(buyKey, buyHits, noBuy, count);
        count = getCount(sellKey, sellHits, noSell, count);
        if (count > 0) log.info("crypto恢复触发限价单 symbol={} low={} high={} 共{}个", symbol, periodLow, periodHigh, count);
    }

    private int getCount(String buyKey, Set<ZSetOperations.TypedTuple<String>> buyOrSellHits, boolean noBuyOrSell, int count) {
        if (!noBuyOrSell) {
            stringRedisTemplate.opsForZSet().remove(buyKey, buyOrSellHits.stream()
                    .map(ZSetOperations.TypedTuple::getValue).toArray());
            for (var tuple : buyOrSellHits) {
                triggerAndExecuteOrder(Long.parseLong(Objects.requireNonNull(tuple.getValue())),
                        BigDecimal.valueOf(Objects.requireNonNull(tuple.getScore())));
                count++;
            }
        }
        return count;
    }

    // ==================== ZSet索引管理 ====================

    private void addToLimitZSet(CryptoOrder order) {
        String key = OrderSide.BUY.getCode().equals(order.getOrderSide())
                ? LIMIT_BUY_ZSET_PREFIX + order.getSymbol()
                : LIMIT_SELL_ZSET_PREFIX + order.getSymbol();
        stringRedisTemplate.opsForZSet().add(key, order.getId().toString(), order.getLimitPrice().doubleValue());
    }

    private void removeFromLimitZSet(CryptoOrder order) {
        String key = OrderSide.BUY.getCode().equals(order.getOrderSide())
                ? LIMIT_BUY_ZSET_PREFIX + order.getSymbol()
                : LIMIT_SELL_ZSET_PREFIX + order.getSymbol();
        stringRedisTemplate.opsForZSet().remove(key, order.getId().toString());
    }

    private void rebuildLimitOrderZSets() {
        List<CryptoOrder> pendingOrders = baseMapper.selectList(new LambdaQueryWrapper<CryptoOrder>()
                .eq(CryptoOrder::getStatus, OrderStatus.PENDING.getCode())
                .eq(CryptoOrder::getOrderType, OrderType.LIMIT.getCode()));
        if (pendingOrders.isEmpty()) return;

        Set<String> symbols = pendingOrders.stream().map(CryptoOrder::getSymbol).collect(Collectors.toSet());
        for (String symbol : symbols) {
            stringRedisTemplate.delete(LIMIT_BUY_ZSET_PREFIX + symbol);
            stringRedisTemplate.delete(LIMIT_SELL_ZSET_PREFIX + symbol);
        }
        for (CryptoOrder order : pendingOrders) {
            addToLimitZSet(order);
        }
        log.info("重建crypto限价单ZSet索引 共{}个订单", pendingOrders.size());
    }

    // ==================== Redis ZSet 延迟结算 ====================

    private void addSettlement(Long userId, Long orderId, BigDecimal amount) {
        long settleAt = System.currentTimeMillis() + SETTLE_DELAY_MS;
        String member = userId + ":" + orderId + ":" + amount.toPlainString();

        // 检查ZSet是否为空，空则需要调度
        Long size = stringRedisTemplate.opsForZSet().zCard(SETTLE_ZSET_KEY);
        stringRedisTemplate.opsForZSet().add(SETTLE_ZSET_KEY, member, settleAt);

        if (size == null || size == 0) {
            scheduleNextSettle(SETTLE_DELAY_MS);
        }
    }

    private void scheduleNextSettle(long delayMs) {
        var prev = nextSettleTask.get();
        if (prev != null && !prev.isDone()) prev.cancel(false);

        var task = settleScheduler.schedule(() -> {
            try { processSettlements(); }
            catch (Exception e) { log.error("crypto结算处理异常", e); }
        }, delayMs, TimeUnit.MILLISECONDS);
        nextSettleTask.set(task);
    }

    @Override
    public void processSettlements() {
        long now = System.currentTimeMillis();
        Set<String> dueMembers = stringRedisTemplate.opsForZSet().rangeByScore(SETTLE_ZSET_KEY, 0, now);
        if (dueMembers == null || dueMembers.isEmpty()) {
            scheduleFromEarliest();
            return;
        }

        for (String member : dueMembers) {
            try {
                String[] parts = member.split(":", 3);
                Long userId = Long.parseLong(parts[0]);
                Long orderId = Long.parseLong(parts[1]);
                BigDecimal amount = new BigDecimal(parts[2]);
                SpringUtils.getAopProxy(this).doSettle(userId, orderId, amount);
                stringRedisTemplate.opsForZSet().remove(SETTLE_ZSET_KEY, member);
                log.info("crypto卖出到账 userId={} orderId={} amount={}", userId, orderId, amount);
            } catch (Exception e) {
                log.error("crypto结算单条处理失败 member={}", member, e);
            }
        }

        scheduleFromEarliest();
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doSettle(Long userId, Long orderId, BigDecimal amount) {
        marginAccountService.applyCashInflow(userId, amount, "CRYPTO_SETTLE");
        baseMapper.casUpdateStatus(orderId, OrderStatus.SETTLING.getCode(), OrderStatus.FILLED.getCode());
    }

    private void scheduleFromEarliest() {
        Set<ZSetOperations.TypedTuple<String>> earliest = stringRedisTemplate.opsForZSet().rangeWithScores(SETTLE_ZSET_KEY, 0, 0);
        if (earliest != null && !earliest.isEmpty()) {
            var first = earliest.iterator().next();
            long nextSettleAt = Objects.requireNonNull(first.getScore()).longValue();
            long delay = Math.max(nextSettleAt - System.currentTimeMillis(), 1000);
            scheduleNextSettle(delay);
        }
    }

    // ==================== 工具方法 ====================

    private void validateRequest(CryptoOrderRequest request) {
        if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.TRADE_QUANTITY_INVALID);
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        }
    }

    private User getAndValidateUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (Boolean.TRUE.equals(user.getIsBankrupt())) throw new BizException(ErrorCode.USER_BANKRUPT);
        return user;
    }

    private CryptoOrder buildOrder(Long userId, String symbol, String orderSide, String orderType,
                                    BigDecimal quantity, int leverage, BigDecimal limitPrice,
                                    BigDecimal filledPrice, BigDecimal filledAmount, BigDecimal commission,
                                    BigDecimal frozenAmount, String status, LocalDateTime expireAt) {
        CryptoOrder order = new CryptoOrder();
        order.setUserId(userId);
        order.setSymbol(symbol);
        order.setOrderSide(orderSide);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(filledPrice);
        order.setFilledAmount(filledAmount);
        order.setCommission(commission);
        order.setFrozenAmount(frozenAmount);
        order.setStatus(status);
        order.setExpireAt(expireAt);
        return order;
    }

    @Override
    public List<CryptoOrderResponse> getLatestOrders() {
        LambdaQueryWrapper<CryptoOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CryptoOrder::getStatus, OrderStatus.FILLED.getCode())
                .orderByDesc(CryptoOrder::getCreatedAt)
                .last("LIMIT 20");
        return baseMapper.selectList(wrapper).stream()
                .map(this::buildResponse).toList();
    }

    private CryptoOrderResponse buildResponse(CryptoOrder order) {
        CryptoOrderResponse resp = new CryptoOrderResponse();
        resp.setOrderId(order.getId());
        resp.setSymbol(order.getSymbol());
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setQuantity(order.getQuantity());
        resp.setLeverage(order.getLeverage());
        resp.setLimitPrice(order.getLimitPrice());
        resp.setFilledPrice(order.getFilledPrice());
        resp.setFilledAmount(order.getFilledAmount());
        resp.setCommission(order.getCommission());
        resp.setTriggerPrice(order.getTriggerPrice());
        resp.setTriggeredAt(order.getTriggeredAt());
        resp.setStatus(order.getStatus());
        resp.setDiscountPercent(order.getDiscountPercent());
        resp.setExpireAt(order.getExpireAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }
}
