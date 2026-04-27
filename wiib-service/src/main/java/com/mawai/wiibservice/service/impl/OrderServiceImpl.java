package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.dto.OrderResponse;
import com.mawai.wiibcommon.dto.OrderRequest;
import com.mawai.wiibcommon.entity.Order;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.OrderSide;
import com.mawai.wiibcommon.enums.OrderStatus;
import com.mawai.wiibcommon.enums.OrderType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.OrderMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.MarginAccountService;
import com.mawai.wiibservice.service.OrderService;
import com.mawai.wiibservice.service.PositionService;
import com.mawai.wiibservice.service.SettlementService;
import com.mawai.wiibservice.service.StockCacheService;
import com.mawai.wiibservice.service.StockService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.service.BuffService;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * 核心交易逻辑，支持市价单和限价单
 *
 * <p>
 * 锁与事务顺序：获取锁 → 开启事务 → 执行操作 → 提交事务 → 释放锁
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final UserService userService;
    private final StockService stockService;
    private final StockCacheService stockCacheService;
    private final PositionService positionService;
    private final SettlementService settlementService;
    private final CacheService cacheService;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final MarginAccountService marginAccountService;
    private final BuffService buffService;

    private static final int TRIGGERED_ORDER_BATCH_SIZE = 200;

    @Override
    @RateLimiter(type = RateLimiterType.BUY, permitsPerSecond = 0.5, bucketCapacity = 5)
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse buy(Long userId, OrderRequest request) {
        // 交易时段校验
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        validateRequest(request);
        Stock stock = getAndValidateStock(request.getStockId());
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        // 从Redis获取实时价格
        BigDecimal price = cacheService.getCurrentPrice(stock.getId());
        if (price == null) {
            // 非交易时段用开盘价（AI预生成）
            price = stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose();
        }

        int leverageMultiple = marginAccountService.normalizeLeverageMultiple(request.getLeverageMultiple());

        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            // 市价单：立即成交，包含手续费
            BigDecimal amount = price.multiply(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal commission = tradingConfig.calculateCommission(amount);

            // 应用折扣Buff
            BigDecimal discountRate = null;
            if (request.getUseBuffId() != null) {
                if (leverageMultiple > 1) {
                    throw new BizException(ErrorCode.DISCOUNT_NO_LEVERAGE);
                }
                discountRate = buffService.getDiscountRate(userId, request.getUseBuffId());
                if (discountRate != null) {
                    amount = amount.multiply(discountRate).setScale(2, RoundingMode.HALF_UP);
                    commission = tradingConfig.calculateCommission(amount);
                }
            }

            // 杠杆小于等1
            if (leverageMultiple <= 1) {
                BigDecimal totalCost = amount.add(commission);
                if (user.getBalance().compareTo(totalCost) < 0) {
                    throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
                }
                BigDecimal discountPercent = discountRate != null ? discountRate.multiply(BigDecimal.valueOf(100)) : null;
                OrderResponse response = executeMarketBuy(userId, stock, request.getQuantity(), price, amount, commission, discountPercent);
                if (discountRate != null) {
                    buffService.markUsed(request.getUseBuffId());
                }
                return response;
            }

            if (!tradingConfig.getMargin().isEnabled()) {
                throw new BizException(ErrorCode.LEVERAGE_MULTIPLE_INVALID);
            }
            if (leverageMultiple > tradingConfig.getMargin().getMaxLeverage()) {
                throw new BizException(ErrorCode.LEVERAGE_MULTIPLE_INVALID);
            }

            BigDecimal margin = amount.divide(BigDecimal.valueOf(leverageMultiple), 2, RoundingMode.CEILING);
            BigDecimal borrowed = amount.subtract(margin);
            BigDecimal cashNeed = margin.add(commission);

            if (user.getBalance().compareTo(cashNeed) < 0) {
                throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            }
            return executeMarketBuyWithLeverage(userId, stock, request.getQuantity(), price, amount, commission,
                    margin, borrowed);
        }

        if (leverageMultiple > 1) {
            throw new BizException(ErrorCode.LEVERAGE_ONLY_FOR_MARKET_BUY);
        }

        // 限价单：冻结资金（含预估手续费），进入订单池
        tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
        BigDecimal freezeAmount = request.getLimitPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
        BigDecimal estimatedCommission = tradingConfig.calculateCommission(freezeAmount);
        BigDecimal totalFreeze = freezeAmount.add(estimatedCommission);

        if (user.getBalance().compareTo(totalFreeze) < 0) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        return createLimitBuyOrder(userId, stock, request, totalFreeze);
    }

    @Override
    @RateLimiter(type = RateLimiterType.SELL, permitsPerSecond = 0.5, bucketCapacity = 5)
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse sell(Long userId, OrderRequest request) {
        // 交易时段校验
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        validateRequest(request);
        Stock stock = getAndValidateStock(request.getStockId());
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        var position = positionService.findByUserAndStock(userId, stock.getId());
        if (position == null || position.getQuantity() < request.getQuantity()) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }

        // 从Redis获取实时价格
        BigDecimal price = cacheService.getCurrentPrice(stock.getId());
        if (price == null) {
            // 非交易时段用开盘价（AI预生成）
            price = stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose();
        }

        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            BigDecimal amount = price.multiply(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal commission = tradingConfig.calculateCommission(amount);
            return executeMarketSell(userId, stock, request.getQuantity(), price, commission);
        } else {
            tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
            return createLimitSellOrder(userId, stock, request);
        }
    }

    @Override
    public OrderResponse cancel(Long userId, Long orderId) {
        String lockKey = "order:execute:" + orderId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) {
            throw new BizException(ErrorCode.ORDER_PROCESSING);
        }

        try {
            return SpringUtils.getAopProxy(this).doCancelOrder(userId, orderId);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 执行取消订单的核心逻辑（锁内执行）
     */
    @Transactional(rollbackFor = Exception.class)
    protected OrderResponse doCancelOrder(Long userId, Long orderId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        Order order = baseMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        Stock stock = stockService.getById(order.getStockId());

        // CAS更新订单状态（防止分布式锁失效时的并发问题，作为最后一道安全防线）
        int affected = baseMapper.casUpdateStatus(orderId, OrderStatus.PENDING.getCode(),
                OrderStatus.CANCELLED.getCode());
        if (affected == 0) {
            // 状态已被其他操作改变（可能已成交或已取消）
            Order freshOrder = baseMapper.selectById(orderId);
            log.warn("取消订单{}失败，当前状态为{}", orderId, freshOrder.getStatus());
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        // 状态更新成功后，安全地回退冻结的资金或股票
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            userService.unfreezeBalance(userId, frozenAmount);
            log.info("取消买入订单{}，解冻资金{}", orderId, frozenAmount);
        } else {
            positionService.unfreezePosition(userId, order.getStockId(), order.getQuantity());
            log.info("取消卖出订单{}，解冻股票{}股", orderId, order.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED.getCode());
        return buildOrderResponse(order, stock);
    }

    @Override
    public IPage<OrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize) {
        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreatedAt);

        baseMapper.selectPage(page, wrapper);

        return page.convert(this::buildOrderResponseFromCache);
    }

    @Override
    public List<OrderResponse> getLatestOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.FILLED.getCode())
                .orderByDesc(Order::getCreatedAt)
                .last("LIMIT 20");
        return baseMapper.selectList(wrapper).stream()
                .map(this::buildOrderResponseFromCache).toList();
    }

    /** 从Redis缓存构建OrderResponse（只需code和name） */
    private OrderResponse buildOrderResponseFromCache(Order order) {
        Map<String, String> stockStatic = stockCacheService.getStockStatic(order.getStockId());
        String stockCode = stockStatic != null ? stockStatic.get("code") : "";
        String stockName = stockStatic != null ? stockStatic.get("name") : "";

        OrderResponse resp = new OrderResponse();
        resp.setOrderId(order.getId());
        resp.setStockCode(stockCode);
        resp.setStockName(stockName);
        return getOrderResponse(order, resp);
    }

    /**
     * 触发限价单（定时任务调用）
     * 检测价格并标记触发状态，不执行成交
     */
    @Override
    public void triggerLimitOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .eq(Order::getOrderType, OrderType.LIMIT.getCode());

        List<Order> pendingOrders = baseMapper.selectList(wrapper);
        if (pendingOrders.isEmpty())
            return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        int timeoutSeconds = tradingConfig.getLimitOrderProcessing().getOrderTimeoutSeconds();

        Semaphore semaphore = new Semaphore(maxConcurrency);

        log.info("开始检测{}个限价单触发条件，最大并发数={}", pendingOrders.size(), maxConcurrency);
        long startTime = System.currentTimeMillis();
        int triggeredCount = 0;
        int failCount = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (Order order : pendingOrders) {
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            log.warn("获取信号量超时，跳过订单{}", order.getId());
                            return false;
                        }

                        try {
                            return checkAndMarkTriggered(order);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("检测订单{}被中断", order.getId());
                        return false;
                    }
                });
                futures.add(future);
            }

            for (Future<Boolean> future : futures) {
                try {
                    long remainingTime = (timeoutSeconds * 1000L) - (System.currentTimeMillis() - startTime);
                    if (remainingTime > 0) {
                        Boolean triggered = future.get(remainingTime, TimeUnit.MILLISECONDS);
                        if (Boolean.TRUE.equals(triggered)) {
                            triggeredCount++;
                        } else {
                            failCount++;
                        }
                    } else {
                        failCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("订单检测超时");
                    failCount++;
                } catch (Exception e) {
                    log.info("订单检测异常: {}", e.getMessage());
                    failCount++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("限价单触发检测完成，共{}个订单，触发{}个，失败{}个，耗时{}ms",
                pendingOrders.size(), triggeredCount, failCount, elapsed);
    }

    /**
     * 检测并标记限价单触发（虚拟线程内执行）
     * @return true=已触发，false=未触发或失败
     */
    private boolean checkAndMarkTriggered(Order order) {
        try {
            Long stockId = order.getStockId();
            BigDecimal currentPrice = cacheService.getCurrentPrice(stockId);
            if (currentPrice == null) {
                // 缓存没有实时价格，从静态缓存取prevClose
                Map<String, String> stockStatic = stockCacheService.getStockStatic(stockId);
                if (stockStatic != null && stockStatic.get("prevClose") != null) {
                    currentPrice = new BigDecimal(stockStatic.get("prevClose"));
                } else {
                    // 极端情况：Redis完全没数据才查DB
                    Stock stock = stockService.getById(stockId);
                    currentPrice = stock.getPrevClose();
                }
            }

            boolean shouldTrigger;
            if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
                shouldTrigger = currentPrice.compareTo(order.getLimitPrice()) <= 0;
            } else {
                shouldTrigger = currentPrice.compareTo(order.getLimitPrice()) >= 0;
            }

            if (shouldTrigger) {
                String lockKey = "order:execute:" + order.getId();
                String lockValue = redisLockUtil.tryLock(lockKey, 30);
                if (lockValue != null) {
                    try {
                        SpringUtils.getAopProxy(this).markOrderTriggered(order.getId(), currentPrice);
                        return true;
                    } finally {
                        redisLockUtil.unlock(lockKey, lockValue);
                    }
                } else {
                    log.info("限价单{}正在被其他实例处理", order.getId());
                }
            }
            return false;
        } catch (Exception e) {
            log.error("检测限价单触发失败 orderId={}", order.getId(), e);
            return false;
        }
    }

    /**
     * 标记订单为已触发（事务内，调用前需获取分布式锁）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = baseMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) {
            log.info("限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
        }
    }

    /**
     * 执行已触发的限价单（定时任务调用）
     * 批量处理TRIGGERED订单
     */
    @Override
    public void executeTriggeredOrders() {
        long startTime = System.currentTimeMillis();
        long lastId = 0L;
        int scannedCount = 0;
        int successCount = 0;
        int skippedCount = 0;
        int failCount = 0;

        // 1) 小批次扫描 TRIGGERED（控内存）
        for (;;) {
            List<Order> batch = baseMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getStatus, OrderStatus.TRIGGERED.getCode())
                    .eq(Order::getOrderType, OrderType.LIMIT.getCode())
                    .gt(Order::getId, lastId)
                    .orderByAsc(Order::getId)
                    .last("LIMIT " + TRIGGERED_ORDER_BATCH_SIZE));

            if (batch.isEmpty()) {
                break;
            }

            scannedCount += batch.size();
            lastId = batch.getLast().getId();

            // 2) 预加载 Stock（避免循环 N 次查库）
            Set<Long> stockIds = batch.stream()
                    .map(Order::getStockId)
                    .collect(Collectors.toSet());
            Map<Long, Stock> stockMap = stockService.listByIds(stockIds).stream()
                    .collect(Collectors.toMap(Stock::getId, s -> s));

            // 3) 逐单处理：单单事务 + CAS 抢占
            for (Order order : batch) {
                Stock stock = stockMap.get(order.getStockId());
                if (stock == null) {
                    log.warn("触发订单{}关联Stock不存在 stockId={}", order.getId(), order.getStockId());
                    failCount++;
                    continue;
                }

                try {
                    boolean processed = SpringUtils.getAopProxy(this).processTriggeredOrder(order, stock);
                    if (processed) {
                        successCount++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    log.error("执行触发订单失败 orderId={}", order.getId(), e);
                    failCount++;
                }
            }
        }

        // 4) 汇总
        long elapsed = System.currentTimeMillis() - startTime;
        if (scannedCount > 0) {
            log.info("已触发订单执行完成，扫描{}个，成功{}个，跳过{}个，失败{}个，耗时{}ms",
                    scannedCount, successCount, skippedCount, failCount, elapsed);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected boolean processTriggeredOrder(Order order, Stock stock) {
        // 1) 校验状态
        if (order == null) {
            return false;
        }
        if (!OrderStatus.TRIGGERED.getCode().equals(order.getStatus())) {
            return false;
        }
        if (!OrderType.LIMIT.getCode().equals(order.getOrderType())) {
            return false;
        }

        User user = userService.getById(order.getUserId());
        if (user != null && Boolean.TRUE.equals(user.getIsBankrupt())) {
            int affected = baseMapper.casUpdateStatus(order.getId(), OrderStatus.TRIGGERED.getCode(),
                    OrderStatus.CANCELLED.getCode());
            if (affected > 0) {
                order.setStatus(OrderStatus.CANCELLED.getCode());
            }
            return false;
        }

        // 2) 计算成交信息
        BigDecimal executePrice = order.getTriggerPrice();
        if (executePrice == null) {
            log.warn("触发订单{}缺少triggerPrice", order.getId());
            return false;
        }

        BigDecimal amount = executePrice.multiply(BigDecimal.valueOf(order.getQuantity()));
        BigDecimal commission = tradingConfig.calculateCommission(amount);

        // 3) CAS 改为 FILLED
        int affected = baseMapper.casUpdateToFilled(order.getId(), executePrice, amount, commission);
        if (affected == 0) {
            return false;
        }

        order.setFilledPrice(executePrice);
        order.setFilledAmount(amount);
        order.setCommission(commission);
        order.setStatus(OrderStatus.FILLED.getCode());

        // 4) 落资金/持仓/结算（失败抛异常回滚本单）
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            if (frozenAmount == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "订单冻结金额为空");
            }
            BigDecimal actualCost = amount.add(commission);
            BigDecimal refund = frozenAmount.subtract(actualCost);

            userService.deductFrozenBalance(order.getUserId(), frozenAmount);
            if (refund.compareTo(BigDecimal.ZERO) > 0) {
                userService.updateBalance(order.getUserId(), refund);
            }
            positionService.addPosition(order.getUserId(), order.getStockId(), order.getQuantity(), executePrice, BigDecimal.ZERO);
        } else {
            positionService.deductFrozenPosition(order.getUserId(), order.getStockId(), order.getQuantity());

            BigDecimal netAmount = amount.subtract(commission);
            Settlement settlement = new Settlement();
            settlement.setUserId(order.getUserId());
            settlement.setOrderId(order.getId());
            settlement.setAmount(netAmount);
            settlement.setSettleTime(LocalDateTime.now().plusDays(1));
            settlement.setStatus("PENDING");
            if (!settlementService.save(settlement)) {
                throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "创建结算记录失败");
            }
        }

        return true;
    }

    /**
     * 过期限价单处理（定时任务调用）
     * 使用虚拟线程并发处理 + 信号量限流
     */
    @Override
    public void expireLimitOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .eq(Order::getOrderType, OrderType.LIMIT.getCode())
                .lt(Order::getExpireAt, LocalDateTime.now());

        List<Order> expiredOrders = baseMapper.selectList(wrapper);
        if (expiredOrders.isEmpty())
            return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        int timeoutSeconds = tradingConfig.getLimitOrderProcessing().getOrderTimeoutSeconds();

        Semaphore semaphore = new Semaphore(maxConcurrency);

        log.info("开始并发处理{}个过期订单，最大并发数={}", expiredOrders.size(), maxConcurrency);
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (Order order : expiredOrders) {
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        // 信号量超时时间短一些，避免长时间等待
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            log.warn("获取信号量超时，跳过过期订单{}", order.getId());
                            return false;
                        }

                        try {
                            processExpiredOrder(order);
                            return true;
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("处理过期订单{}被中断", order.getId());
                        return false;
                    }
                });
                futures.add(future);
            }

            // 等待所有任务完成
            for (Future<Boolean> future : futures) {
                try {
                    long remainingTime = (timeoutSeconds * 1000L) - (System.currentTimeMillis() - startTime);
                    if (remainingTime > 0) {
                        Boolean success = future.get(remainingTime, TimeUnit.MILLISECONDS);
                        if (Boolean.TRUE.equals(success)) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } else {
                        failCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("过期订单处理超时");
                    failCount++;
                } catch (Exception e) {
                    log.info("过期订单处理异常: {}", e.getMessage());
                    failCount++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("过期订单处理完成，共{}个订单，成功{}个，失败{}个，耗时{}ms",
                expiredOrders.size(), successCount, failCount, elapsed);
    }

    /**
     * 处理单个过期订单（虚拟线程内执行）
     */
    private void processExpiredOrder(Order order) {
        // 获取分布式锁，确保取消、过期、成交操作互斥
        String lockKey = "order:execute:" + order.getId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) {
            log.info("限价单{}正在被其他操作处理，跳过过期检查", order.getId());
            return;
        }

        try {
            // 通过AOP代理调用，确保事务生效
            SpringUtils.getAopProxy(this).doExpireOrder(order);
        } catch (Exception e) {
            log.error("处理过期订单失败 orderId={}", order.getId(), e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 执行订单过期的核心逻辑（锁内执行）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void doExpireOrder(Order order) {
        // CAS更新订单状态（防止分布式锁失效时的并发问题）
        int affected = baseMapper.casUpdateStatus(order.getId(), OrderStatus.PENDING.getCode(),
                OrderStatus.EXPIRED.getCode());
        if (affected == 0) {
            // 状态已被其他操作改变（可能已成交或已取消）
            log.info("订单{}状态已变更，跳过过期处理", order.getId());
            return;
        }

        // 状态更新成功后，安全地回退冻结的资金或股票
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            userService.unfreezeBalance(order.getUserId(), frozenAmount);
            log.info("限价买单过期 orderId={} 解冻资金{}", order.getId(), frozenAmount);
        } else {
            positionService.unfreezePosition(order.getUserId(), order.getStockId(), order.getQuantity());
            log.info("限价卖单过期 orderId={} 解冻股票{}股", order.getId(), order.getQuantity());
        }
    }

    /** 校验请求（时间戳） */
    private void validateRequest(OrderRequest request) {
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BizException(ErrorCode.TRADE_QUANTITY_INVALID);
        }
    }

    private Stock getAndValidateStock(Long stockId) {
        Stock stock = stockService.findById(stockId);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    /** 执行市价买入（含手续费） */
    private OrderResponse executeMarketBuy(Long userId, Stock stock, int quantity, BigDecimal price,
            BigDecimal amount, BigDecimal commission, BigDecimal discountPercent) {
        BigDecimal totalCost = amount.add(commission);

        userService.updateBalance(userId, totalCost.negate());
        BigDecimal discount = discountPercent != null ? price.multiply(BigDecimal.valueOf(quantity)).subtract(amount) : BigDecimal.ZERO;
        positionService.addPosition(userId, stock.getId(), quantity, price, discount);

        Order order = createOrder(userId, stock.getId(), OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), null);
        order.setDiscountPercent(discountPercent);
        baseMapper.insert(order);

        log.info("市价买入成交 userId={} stock={} qty={} price={} amount={} commission={}",
                userId, stock.getCode(), quantity, price, amount, commission);

        return buildOrderResponse(order, stock);
    }

    private OrderResponse executeMarketBuyWithLeverage(Long userId, Stock stock, int quantity, BigDecimal price,
            BigDecimal amount, BigDecimal commission, BigDecimal margin, BigDecimal borrowed) {
        BigDecimal cashNeed = margin.add(commission);

        userService.updateBalance(userId, cashNeed.negate());
        marginAccountService.addLoanPrincipal(userId, borrowed);
        positionService.addPosition(userId, stock.getId(), quantity, price, BigDecimal.ZERO);

        Order order = createOrder(userId, stock.getId(), OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), null);
        baseMapper.insert(order);

        log.info("市价杠杆买入成交 userId={} stock={} qty={} price={} amount={} margin={} borrowed={} commission={}",
                userId, stock.getCode(), quantity, price, amount, margin, borrowed, commission);

        return buildOrderResponse(order, stock);
    }

    /** 执行市价卖出（资金T+1到账） */
    private OrderResponse executeMarketSell(Long userId, Stock stock, int quantity, BigDecimal price,
            BigDecimal commission) {
        BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal netAmount = amount.subtract(commission);

        positionService.reducePosition(userId, stock.getId(), quantity);

        Order order = createOrder(userId, stock.getId(), OrderSide.SELL.getCode(), OrderType.MARKET.getCode(),
                quantity, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), null);
        baseMapper.insert(order);

        // T+1结算
        settlementService.createSettlement(userId, order.getId(), netAmount);

        log.info("市价卖出成交 userId={} stock={} qty={} price={} amount={} commission={} (T+1到账)",
                userId, stock.getCode(), quantity, price, amount, commission);

        return buildOrderResponse(order, stock);
    }

    /** 创建限价买单（冻结资金，使用freezeBalance） */
    private OrderResponse createLimitBuyOrder(Long userId, Stock stock, OrderRequest request, BigDecimal freezeAmount) {
        userService.freezeBalance(userId, freezeAmount);

        int expireHours = tradingConfig.getLimitOrderMaxHours();
        LocalDateTime expireAt = LocalDateTime.now().plusHours(expireHours);

        Order order = createOrder(userId, stock.getId(), OrderSide.BUY.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), request.getLimitPrice(), null, null, null, freezeAmount,
                OrderStatus.PENDING.getCode(), expireAt);
        baseMapper.insert(order);

        log.info("限价买单创建 userId={} stock={} qty={} limitPrice={} frozen={} expireAt={}",
                userId, stock.getCode(), request.getQuantity(), request.getLimitPrice(), freezeAmount, expireAt);
        return buildOrderResponse(order, stock);
    }

    /** 创建限价卖单（冻结持仓，使用freezePosition） */
    private OrderResponse createLimitSellOrder(Long userId, Stock stock, OrderRequest request) {
        positionService.freezePosition(userId, stock.getId(), request.getQuantity());

        int expireHours = tradingConfig.getLimitOrderMaxHours();
        LocalDateTime expireAt = LocalDateTime.now().plusHours(expireHours);

        Order order = createOrder(userId, stock.getId(), OrderSide.SELL.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), request.getLimitPrice(), null, null, null, null,
                OrderStatus.PENDING.getCode(), expireAt);
        baseMapper.insert(order);

        log.info("限价卖单创建 userId={} stock={} qty={} limitPrice={} expireAt={}",
                userId, stock.getCode(), request.getQuantity(), request.getLimitPrice(), expireAt);
        return buildOrderResponse(order, stock);
    }

    private Order createOrder(Long userId, Long stockId, String orderSide, String orderType,
            int quantity, BigDecimal limitPrice, BigDecimal filledPrice,
            BigDecimal filledAmount, BigDecimal commission, BigDecimal frozenAmount,
            String status, LocalDateTime expireAt) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStockId(stockId);
        order.setOrderSide(orderSide);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(filledPrice);
        order.setFilledAmount(filledAmount);
        order.setCommission(commission);
        order.setFrozenAmount(frozenAmount);
        order.setStatus(status);
        order.setExpireAt(expireAt);
        return order;
    }

    private OrderResponse buildOrderResponse(Order order, Stock stock) {
        OrderResponse resp = new OrderResponse();
        resp.setOrderId(order.getId());
        resp.setStockCode(stock.getCode());
        resp.setStockName(stock.getName());
        return getOrderResponse(order, resp);
    }

    private OrderResponse getOrderResponse(Order order, OrderResponse resp) {
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setQuantity(order.getQuantity());
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
