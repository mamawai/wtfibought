package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
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
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.service.BuffService;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.enums.LedgerBizType.*;
import static com.mawai.wiibsim.service.impl.FuturesHelper.toEpochMs;

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
    private final CrossMarginService crossMarginService;
    private final StringRedisTemplate stringRedisTemplate;
    private final CacheService cacheService;
    private final BStockService bStockService;
    private final TradeFilterRegistry tradeFilterRegistry;

    private static final int TRIGGERED_ORDER_BATCH_SIZE = 200;
    private static final String LIMIT_BUY_ZSET_PREFIX = "crypto:limit:buy:";
    private static final String LIMIT_SELL_ZSET_PREFIX = "crypto:limit:sell:";

    @PostConstruct
    void initLimitOrderZSets() {
        rebuildLimitOrderZSets();
    }

    // ==================== 获取实时价格 ====================

    private BigDecimal getCryptoPrice(String symbol) {
        BigDecimal price = cacheService.getCryptoPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    // ==================== 买入 ====================

    /**
     * 三条买入分支的资金语义不同（现货 / 杠杆 / 限价冻结），而三个执行方法全是私有 + 同类自调用，
     * @Ledger 标它们是空操作。所以方法级只在这里标一次当默认（普通市价买入这条最常走），
     * 另两条分支各自在动钱之前 LedgerCtx.mark 覆盖成精确类型。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @Ledger(SPOT_BUY)
    public CryptoOrderResponse buy(Long userId, CryptoOrderRequest request) {
        validateRequest(request);
        User user = getAndValidateUser(userId);
        BigDecimal price = getCryptoPrice(request.getSymbol());
        // 交易过滤器（对齐Binance exchangeInfo）：步长对齐 + 名义额≥minNotional（限价按挂单价估）
        boolean isMarketBuy = OrderType.MARKET.getCode().equals(request.getOrderType());
        tradeFilterRegistry.validateSpotBuy(request.getSymbol(), request.getQuantity(),
                isMarketBuy ? price : request.getLimitPrice());
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
                // 现货买入=余额钱包流出，被全仓仓位占用的部分不能拿来买币
                crossMarginService.assertCanAfford(userId, totalCost);
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
            crossMarginService.assertCanAfford(userId, cashNeed);
            return executeMarketBuyWithLeverage(userId, request.getSymbol(), request.getQuantity(), price, amount, commission, margin, borrowed, leverageMultiple);
        }

        // 限价买单
        if (leverageMultiple > 1) throw new BizException(ErrorCode.LEVERAGE_ONLY_FOR_MARKET_BUY);
        tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
        BigDecimal freezeAmount = request.getLimitPrice().multiply(request.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimatedCommission = tradingConfig.calculateCryptoCommission(freezeAmount);
        BigDecimal totalFreeze = freezeAmount.add(estimatedCommission);
        if (user.getBalance().compareTo(totalFreeze) < 0) throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        crossMarginService.assertCanAfford(userId, totalFreeze);
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
        // 卖出=减持：豁免最小名义额；部分卖查步长，全量卖豁免（尘埃持仓能清干净）
        tradeFilterRegistry.validateSpotSell(request.getSymbol(), request.getQuantity(), position.getQuantity());

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
            CryptoOrder order = SpringUtils.getAopProxy(this).doCancelOrder(userId, orderId);
            // 索引跟着DB走：事务提交后才摘索引。搁事务里解冻一失败回滚，单子退回PENDING而索引已没了=悬空
            removeFromLimitZSet(order);
            return buildResponse(order);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    // 标这一层：protected 且经 getAopProxy 走代理调进来，AOP 拦得到（cancel() 只负责抢锁和摘索引）
    @Transactional(rollbackFor = Exception.class)
    @Ledger(SPOT_LIMIT_UNFREEZE)
    protected CryptoOrder doCancelOrder(Long userId, Long orderId) {
        getAndValidateUser(userId);
        CryptoOrder order = baseMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        int affected = baseMapper.casUpdateStatus(orderId, OrderStatus.PENDING.getCode(), OrderStatus.CANCELLED.getCode());
        if (affected == 0) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            userService.unfreezeBalance(userId, order.getFrozenAmount());
        } else {
            cryptoPositionService.unfreezePosition(userId, order.getSymbol(), order.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED.getCode());
        return order;
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
                quantity, 1, null, price, amount, commission, null, OrderStatus.FILLED.getCode());
        order.setDiscountPercent(discountPercent); // 折扣率
        baseMapper.insert(order);
        log.info("crypto市价买入 userId={} {} qty={} price={} amount={}", userId, symbol, quantity, price, amount);
        return buildResponse(order);
    }

    private CryptoOrderResponse executeMarketBuyWithLeverage(Long userId, String symbol, BigDecimal quantity,
                                                              BigDecimal price, BigDecimal amount, BigDecimal commission,
                                                              BigDecimal margin, BigDecimal borrowed, int leverage) {
        // 覆盖 buy() 的方法级默认 SPOT_BUY。紧跟着的 addLoanPrincipal 自带 @Ledger(MARGIN_LOAN)，
        // 借款那笔不会被这个 mark 带走（mark 已被上一句消费掉）
        LedgerCtx.mark(SPOT_BUY_LEVERAGE);
        userService.updateBalance(userId, margin.add(commission).negate());
        marginAccountService.addLoanPrincipal(userId, borrowed);
        cryptoPositionService.addPosition(userId, symbol, quantity, price, BigDecimal.ZERO);

        CryptoOrder order = buildOrder(userId, symbol, OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, leverage, null, price, amount, commission, null, OrderStatus.FILLED.getCode());
        baseMapper.insert(order);
        log.info("crypto杠杆买入 userId={} {} qty={} price={} leverage={} borrowed={}", userId, symbol, quantity, price, leverage, borrowed);
        return buildResponse(order);
    }

    // ==================== 市价卖出执行（瞬时到账） ====================

    private CryptoOrderResponse executeMarketSell(Long userId, String symbol, BigDecimal quantity,
                                                   BigDecimal price, BigDecimal amount, BigDecimal commission) {
        BigDecimal netAmount = amount.subtract(commission);
        cryptoPositionService.reducePosition(userId, symbol, quantity);

        CryptoOrder order = buildOrder(userId, symbol, OrderSide.SELL.getCode(), OrderType.MARKET.getCode(),
                quantity, 1, null, price, amount, commission, null, OrderStatus.FILLED.getCode());
        baseMapper.insert(order);

        boolean bStock = settleSellProceeds(userId, symbol, order.getId(), netAmount);
        log.info("{}市价卖出 userId={} {} qty={} price={} net={}",
                bStock ? "bStock" : "crypto", userId, symbol, quantity, price, netAmount);
        return buildResponse(order);
    }

    /**
     * 卖出所得同事务入账：先还保证金贷+息，剩下进余额。
     * <p>
     * applyCashInflow 是公共入账口、刻意不带语义，所以到账这笔的类型在这儿逐笔给
     * （现货 SPOT_SETTLE / B股 BSTOCK_SETTLE，账单上分得开）。
     * <p>
     * 【mark 必须跟着"真会发 SQL"的条件走】applyCashInflow 对 amount ≤ 0 是第一句就 return、
     * 一条 SQL 都不发，那样 mark 没人消费，会活到下一笔 atomic* 上错标到别人头上——
     * 限价单那条路是在批处理 for 循环里跑的，漏掉的 mark 会带着 A 单的 refId 安到 B 单（很可能是另一个用户）头上。
     * netAmount ≤ 0 是可达的：commission 无下限，尘埃仓全量卖出时 amount 可能舍入成 0.00。
     *
     * @return 是否 bStock，供调用方打日志区分币种与代币化实股（这里已经查过一次，别让调用方再查）
     */
    private boolean settleSellProceeds(Long userId, String symbol, Long orderId, BigDecimal netAmount) {
        boolean bStock = bStockService.isBStockSymbol(symbol);
        if (netAmount.signum() > 0) {
            LedgerCtx.mark(bStock ? BSTOCK_SETTLE : SPOT_SETTLE, "CRYPTO_ORDER", orderId);
        }
        marginAccountService.applyCashInflow(userId, netAmount, bStock ? "BSTOCK_SETTLE" : "CRYPTO_SETTLE");
        return bStock;
    }

    // ==================== 限价单创建 ====================

    private CryptoOrderResponse createLimitBuyOrder(Long userId, CryptoOrderRequest request, BigDecimal freezeAmount) {
        // 覆盖 buy() 的方法级默认：冻结不是买入。一条 SQL 两个钱包两行账，一次 mark 全覆盖
        LedgerCtx.mark(SPOT_LIMIT_FREEZE);
        userService.freezeBalance(userId, freezeAmount);

        CryptoOrder order = buildOrder(userId, request.getSymbol(), OrderSide.BUY.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), 1, request.getLimitPrice(), null, null, null, freezeAmount, OrderStatus.PENDING.getCode());
        baseMapper.insert(order);
        addToLimitZSet(order);
        log.info("crypto限价买单 userId={} {} qty={} limit={} frozen={}", userId, request.getSymbol(), request.getQuantity(), request.getLimitPrice(), freezeAmount);
        return buildResponse(order);
    }

    private CryptoOrderResponse createLimitSellOrder(Long userId, CryptoOrderRequest request) {
        cryptoPositionService.freezePosition(userId, request.getSymbol(), request.getQuantity());

        CryptoOrder order = buildOrder(userId, request.getSymbol(), OrderSide.SELL.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), 1, request.getLimitPrice(), null, null, null, null, OrderStatus.PENDING.getCode());
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

        int affected = baseMapper.casUpdateToFilled(order.getId(), executePrice, amount, commission);
        if (affected == 0) return false;

        // 本方法刻意没有方法级 @Ledger：成交扣冻结、退差额、卖出到账三种语义并存，表达不了。
        // 每笔在动钱之前逐笔 mark，漏标会落 UNKNOWN 并打 WARN——那正是我们要的可见性
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            BigDecimal actualCost = amount.add(commission);
            BigDecimal refund = frozenAmount.subtract(actualCost);
            LedgerCtx.mark(SPOT_LIMIT_DEDUCT, "CRYPTO_ORDER", order.getId());
            userService.deductFrozenBalance(order.getUserId(), frozenAmount);
            if (refund.compareTo(BigDecimal.ZERO) > 0) {
                LedgerCtx.mark(SPOT_LIMIT_REFUND, "CRYPTO_ORDER", order.getId());
                userService.updateBalance(order.getUserId(), refund);
            }
            cryptoPositionService.addPosition(order.getUserId(), order.getSymbol(), order.getQuantity(), executePrice, BigDecimal.ZERO);
        } else {
            cryptoPositionService.deductFrozenPosition(order.getUserId(), order.getSymbol(), order.getQuantity());
            settleSellProceeds(order.getUserId(), order.getSymbol(), order.getId(), amount.subtract(commission));
        }
        return true;
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

        // 只查不预删：DB是事实、索引跟着事实走。抢不到锁或CAS抛错时索引原地留着，下个tick照样捞得到
        if (buyHits != null) {
            for (String id : buyHits) triggerAndExecuteOrder(buyKey, Long.parseLong(id), price);
        }
        if (sellHits != null) {
            for (String id : sellHits) triggerAndExecuteOrder(sellKey, Long.parseLong(id), price);
        }
    }

    private void triggerAndExecuteOrder(String zsetKey, Long orderId, BigDecimal triggerPrice) {
        String lockKey = "crypto:order:execute:" + orderId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;   // 有人正在处理这单，索引不动，下个tick再来
        try {
            var proxy = SpringUtils.getAopProxy(this);
            proxy.markOrderTriggered(orderId, triggerPrice);
            // CAS正常返回才摘索引：没改到说明这单早不是PENDING，索引是过期项，一样该清
            stringRedisTemplate.opsForZSet().remove(zsetKey, orderId.toString());
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

    // ==================== 空窗补漏 ====================

    @Override
    public void recoverGap(String symbol, List<KlineBar> bars) {
        if (bars.isEmpty()) return;
        // 整段极值先粗筛一遍索引，逐单再按各自挂单时间算区间
        BigDecimal[] all = KlineBar.lowHighAfter(bars, 0);
        String buyKey = LIMIT_BUY_ZSET_PREFIX + symbol;
        String sellKey = LIMIT_SELL_ZSET_PREFIX + symbol;

        Set<ZSetOperations.TypedTuple<String>> buyHits = stringRedisTemplate.opsForZSet()
                .rangeByScoreWithScores(buyKey, all[0].doubleValue(), Double.MAX_VALUE);
        Set<ZSetOperations.TypedTuple<String>> sellHits = stringRedisTemplate.opsForZSet()
                .rangeByScoreWithScores(sellKey, 0, all[1].doubleValue());

        int count = recoverHits(buyKey, buyHits, bars, true) + recoverHits(sellKey, sellHits, bars, false);
        if (count > 0) log.info("crypto空窗补漏触发限价单 symbol={} 共{}个", symbol, count);
    }

    // 空窗里价格已穿过，按挂单价成交；摘索引同样交给 triggerAndExecuteOrder（CAS落定后才摘）
    private int recoverHits(String key, Set<ZSetOperations.TypedTuple<String>> hits,
                            List<KlineBar> bars, boolean buySide) {
        if (hits == null || hits.isEmpty()) return 0;
        int count = 0;
        for (var tuple : hits) {
            Long orderId = Long.parseLong(Objects.requireNonNull(tuple.getValue()));
            CryptoOrder order = baseMapper.selectById(orderId);
            if (order == null) {
                // DB 里没这单了，索引是过期项，摘掉
                stringRedisTemplate.opsForZSet().remove(key, orderId.toString());
                continue;
            }
            BigDecimal[] range = KlineBar.lowHighAfter(bars, toEpochMs(order.getCreatedAt()));
            if (range == null) continue;   // 挂单晚于整段行情
            BigDecimal limitPrice = order.getLimitPrice();
            // 买单看区间低点有没有穿到挂单价，卖单看高点
            boolean hit = buySide
                    ? range[0].compareTo(limitPrice) <= 0
                    : range[1].compareTo(limitPrice) >= 0;
            if (!hit) continue;
            triggerAndExecuteOrder(key, orderId, limitPrice);
            count++;
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

    /**
     * 周期对账：把DB里所有PENDING挂单补回索引。纯追加不删——ZADD同member只覆盖score，
     * 重复跑无害；Redis丢键、或触发/撤单路径中途出岔子掉出索引的挂单，靠这个捞回来接着盯价。
     */
    @Override
    public void reconcileLimitOrderIndex() {
        List<CryptoOrder> pendingOrders = pendingLimitOrders();
        if (pendingOrders.isEmpty()) return;
        for (CryptoOrder order : pendingOrders) {
            addToLimitZSet(order);
        }
        log.info("crypto限价单索引对账 挂单{}个", pendingOrders.size());
    }

    private List<CryptoOrder> pendingLimitOrders() {
        return baseMapper.selectList(new LambdaQueryWrapper<CryptoOrder>()
                .eq(CryptoOrder::getStatus, OrderStatus.PENDING.getCode())
                .eq(CryptoOrder::getOrderType, OrderType.LIMIT.getCode()));
    }

    private void rebuildLimitOrderZSets() {
        List<CryptoOrder> pendingOrders = pendingLimitOrders();
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
                                    BigDecimal frozenAmount, String status) {
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
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }
}
