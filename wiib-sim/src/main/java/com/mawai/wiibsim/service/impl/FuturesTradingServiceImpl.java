package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.ClosedPositionCleanup;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.LimitOrderIndex;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.mawai.wiibcommon.enums.LedgerBizType.*;
import static com.mawai.wiibsim.service.impl.FuturesHelper.*;
import static com.mawai.wiibsim.util.FairLockRegistry.held;

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
    private final FairLockRegistry lockRegistry;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;
    private final TradeFilterRegistry tradeFilterRegistry;
    /** 反手的半成功要把开仓失败的原因成文带回前端，绕开了全局处理器，只能自己查词表 */
    private final MessageCatalog messages;
    private final LedgerTx ledgerTx;
    private final FuturesRiskService riskService;
    private final BookFillPricer fillPricer;
    private final FuturesPrices prices;
    private final LimitOrderIndex limitIndex;
    private final ClosedPositionCleanup closedPositionCleanup;

    // ==================== 开仓 ====================

    @Override
    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        validateOpenRequest(request);
        // 币种锁串行化同币开仓/调杠杆：合并判定期间不允许另一笔开仓或调杠杆插队
        try (var _ = held(lockRegistry.tryLockAsUser("futures:sym:" + userId + ":" + request.getSymbol()))) {
            // 同向已有仓位=加仓合并，还要压住该仓位与平仓/SLTP结算互斥（锁序 sym→pos，与调杠杆一致防死锁）
            FuturesPosition sameSide = openPositionsOf(userId, request.getSymbol()).stream()
                    .filter(p -> p.getSide().equals(request.getSide())).findFirst().orElse(null);
            if (sameSide == null) {
                return openLocked(userId, request);
            }
            try (var _ = held(lockRegistry.tryLockAsUser("futures:pos:" + sameSide.getId()))) {
                return openLocked(userId, request);
            }
        }
    }

    // 锁内开仓。每笔的精确类型由三条开仓执行方法里的 LedgerCtx.mark 给，FUTURES_OPEN_MARGIN 只是默认
    private FuturesOrderResponse openLocked(Long userId, FuturesOpenRequest request) {
        return ledgerTx.call(FUTURES_OPEN_MARGIN, () -> doOpenPosition(userId, request));
    }

    FuturesOrderResponse doOpenPosition(Long userId, FuturesOpenRequest request) {
        // 挂一次，本方法内所有流水都带上币种（symbol 挂在方法级 frame 上）
        LedgerCtx.symbol(request.getSymbol());
        getAndValidateUser(userId);

        int leverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());
        String marginMode = normalizeMarginMode(request.getMarginMode());

        // 币种级一致性（对齐Binance）：杠杆/保证金模式是币种级设置，与该币现有任一仓位冲突直接拒；
        // 锁内重查，外层只做锁发现
        List<FuturesPosition> existing = openPositionsOf(userId, request.getSymbol());
        validateSymbolConsistency(existing, marginMode, leverage);
        FuturesPosition sameSide = existing.stream()
                .filter(p -> p.getSide().equals(request.getSide())).findFirst().orElse(null);

        boolean buy = "LONG".equals(request.getSide());
        BigDecimal limitPrice = null;
        BigDecimal price;
        if ("MARKET".equals(request.getOrderType())) {
            // 按盘口扫出成交价；没盘口退回最新价，且只认 feed 30 秒内写过的价：断线时不按停住的价开仓
            price = fillPricer.futures(request.getSymbol(), buy, request.getQuantity(),
                    prices.fresh(request.getSymbol()));
        } else {
            limitPrice = request.getLimitPrice();
            tradingConfig.validateLimitPrice(limitPrice, prices.mark(request.getSymbol()));
            price = takePrice(request.getSymbol(), buy, request.getQuantity(), limitPrice);
            if (price == null) {
                tradeFilterRegistry.validateFuturesOrder(request.getSymbol(), request.getQuantity(), limitPrice);
                // 限价同向加仓：挂单按合并后总持仓过档（成交时并入由结算侧处理）
                BigDecimal bracketQty = sameSide != null ? sameSide.getQuantity().add(request.getQuantity()) : request.getQuantity();
                validateLeverageByBracket(request.getSymbol(), leverage, limitPrice.multiply(bracketQty));
                return createLimitOpenOrder(userId, request, leverage);
            }
        }

        // 当场成交：市价单，和挂上就能成交的限价单
        // 交易过滤器（对齐Binance exchangeInfo）：步长对齐 + 名义额≥minNotional，加仓单同样受限
        tradeFilterRegistry.validateFuturesOrder(request.getSymbol(), request.getQuantity(), price);
        // 同向已有仓位：Binance 无独立"加仓"概念，同向下单即并入现有仓位
        if (sameSide != null) return mergeNow(userId, sameSide, request, price, limitPrice);
        // BTC/ETH 等已配置档位的 symbol 按 Binance 档位二次校验杠杆（按 notional 取档）
        validateLeverageByBracket(request.getSymbol(), leverage, price.multiply(request.getQuantity()));
        return openNow(userId, request, price, leverage, limitPrice);
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
     * 当场成交的同向单并入现有仓位（Binance 语义：同向下单即合并，均价按数量加权），limitPrice 为空是市价单。
     * 随单 SL/TP 以成交价为参照、按本单数量校验后并入仓位现有档位（旧档先摊到加仓前的持仓量，同价合并，合计条数≤4）。
     */
    private FuturesOrderResponse mergeNow(Long userId, FuturesPosition position, FuturesOpenRequest request,
                                          BigDecimal price, BigDecimal limitPrice) {
        BigDecimal addQty = request.getQuantity();
        int leverage = position.getLeverage();

        BigDecimal oldQty = position.getQuantity();
        BigDecimal newQty = oldQty.add(addQty);
        // 合并后 notional 跨档校验：按当前价×总持仓评估市值（对齐 Binance 实操），超档直接拒
        validateLeverageByBracket(position.getSymbol(), leverage, price.multiply(newQty));

        // 随单SL/TP先构建校验，不过关不动钱；null=本单未带，不改库
        List<FuturesStopLoss> addSl = buildStopLosses(request.getStopLosses(), position.getSide(), price, addQty);
        List<FuturesTakeProfit> addTp = buildTakeProfits(request.getTakeProfits(), position.getSide(), price, addQty);
        List<FuturesStopLoss> mergedSl = mergeSlList(position.getStopLosses(), addSl, oldQty, position.getSide());
        List<FuturesTakeProfit> mergedTp = mergeTpList(position.getTakeProfits(), addTp, oldQty, position.getSide());

        BigDecimal addValue = price.multiply(addQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal addMargin = addValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(addValue, false, true);
        BigDecimal totalCost = addMargin.add(commission);

        // 两条分支各一笔、语义不同：全仓只实扣手续费；逐仓 totalCost = 保证金+手续费 一条 SQL 走
        if (position.isCross()) {
            crossMarginService.assertCanAfford(userId, totalCost);
            LedgerCtx.mark(FUTURES_OPEN_FEE, "POSITION", position.getId());
            userMapper.atomicSettleBalance(userId, commission.negate());
        } else {
            // 逐仓加仓要真划钱：先过全仓可用额度（占用的钱不能拿来开逐仓），再由条件更新兜住现金够不够
            crossMarginService.assertCanAfford(userId, totalCost);
            LedgerCtx.mark(FUTURES_OPEN_MARGIN, "POSITION", position.getId());
            BigDecimal after = userMapper.atomicUpdateBalance(userId, totalCost.negate());
            if (after == null) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        BigDecimal newEntryPrice = position.getEntryPrice().multiply(oldQty)
                .add(price.multiply(addQty))
                .divide(newQty, PRICE_SCALE, RoundingMode.HALF_UP);

        int affected2 = positionMapper.atomicIncreasePosition(position.getId(), newEntryPrice, addQty, addMargin);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        if (mergedSl != null) positionMapper.updateStopLosses(position.getId(), mergedSl);
        if (mergedTp != null) positionMapper.updateTakeProfits(position.getId(), mergedTp);

        syncProtectionIndex(position, mergedSl, mergedTp, positionIndexService);
        if (!position.isCross()) {
            BigDecimal newMargin = position.getMargin().add(addMargin);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                    newEntryPrice, newMargin, newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }
        // 全仓加仓不改 symbol 集合、Redis 索引本不用刷，刷的是强平安全带作废（bump 在 refreshUserIndex 内）：
        // 数量与占用变大 → 旧带过宽会把足以爆掉新账户的插针当带内免检放走
        if (position.isCross()) crossMarginService.refreshUserIndex(userId);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
        order.setOrderType(orderType(limitPrice));
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(addQty);
        order.setGroupId(request.getGroupId());
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(price);
        order.setFilledAmount(addValue);
        order.setMarginAmount(addMargin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        log.info("futures{}开仓并入 userId={} posId={} addQty={} price={} addMargin={} 新均价={}",
                limitPrice == null ? "市价" : "限价当场", userId, position.getId(), addQty, price, addMargin, newEntryPrice);

        return buildOrderResponse(order);
    }

    /** 按 notional 取档校验 leverage 不超过该档最大值；未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。 */
    private void validateLeverageByBracket(String symbol, int leverage, BigDecimal notional) {
        validateLeverage(symbol, leverage, bracketRegistry.getEffectiveMaxLeverage(symbol, notional));
    }

    /** 当场成交的开仓，limitPrice 为空是市价单 */
    private FuturesOrderResponse openNow(Long userId, FuturesOpenRequest request, BigDecimal price, int leverage,
                                         BigDecimal limitPrice) {
        String marginMode = normalizeMarginMode(request.getMarginMode());
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, true);
        BigDecimal totalCost = margin.add(commission);

        // 两条分支各一笔（新开仓的 refId 给不了：仓位这会儿还没 insert，id 要到下面才有）
        if (FuturesPosition.CROSS.equals(marginMode)) {
            // 全仓占用制：钱不动，只校验可用额度（含浮盈亏，浮盈开仓天然成立）；手续费实扣
            crossMarginService.assertCanAfford(userId, totalCost);
            LedgerCtx.mark(FUTURES_OPEN_FEE);
            userMapper.atomicSettleBalance(userId, commission.negate());
        } else {
            // 逐仓划扣制：把保证金从余额钱包划走。两道闸语义不同，都得过——
            // available 管"这钱是不是被全仓占着"，balance 管"钱包里有没有这么多现金"（浮盈进得了前者进不了后者）
            crossMarginService.assertCanAfford(userId, totalCost);
            User user = userService.getById(userId);
            // 允许0.05 USDT的价格滑点容差，避免前后端价格时间差导致误报余额不足
            BigDecimal tolerance = tradingConfig.getFutures().getBalanceTolerance();
            if (user.getBalance().add(tolerance).compareTo(totalCost) < 0) {
                throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
            }
            LedgerCtx.mark(FUTURES_OPEN_MARGIN);
            BigDecimal after = userMapper.atomicUpdateBalance(userId, totalCost.negate());
            if (after == null) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
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
        order.setGroupId(request.getGroupId());
        order.setOrderType(orderType(limitPrice));
        order.setMarginMode(marginMode);
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(price);
        order.setFilledAmount(positionValue);
        order.setMarginAmount(margin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        positionIndexService.registerPositionIndex(position);
        if (position.isCross()) crossMarginService.refreshUserIndex(userId);

        log.info("futures{}开仓 userId={} {} side={} mode={} qty={} price={} leverage={} margin={}",
                limitPrice == null ? "市价" : "限价当场", userId, request.getSymbol(), request.getSide(), marginMode,
                quantity, price, leverage, margin);

        return buildOrderResponse(order);
    }

    /** 挂着等别人来吃的限价开仓单，按挂单费率收 */
    private FuturesOrderResponse createLimitOpenOrder(Long userId, FuturesOpenRequest request, int leverage) {
        String marginMode = normalizeMarginMode(request.getMarginMode());
        BigDecimal limitPrice = request.getLimitPrice();
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = limitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        String orderSide = "LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT";
        BigDecimal commission = tradingConfig.calculateFuturesCommission(positionValue, false, false);
        BigDecimal frozenAmount = margin.add(commission);

        if (FuturesPosition.CROSS.equals(marginMode)) {
            // 全仓挂单不物理冻结：frozenAmount 只作"挂单占用"记账，入库后自动进可用余额扣减项
            crossMarginService.assertCanAfford(userId, frozenAmount);
        } else {
            // 逐仓挂单是物理冻结（钱真从余额划到冻结），同样先过全仓可用额度
            crossMarginService.assertCanAfford(userId, frozenAmount);
            // 覆盖 doOpenPosition 的方法级默认：挂单冻结不是开仓保证金。一条 SQL 两个钱包两行账，一次 mark 全覆盖
            LedgerCtx.mark(FUTURES_LIMIT_FREEZE);
            var frozen = userMapper.atomicFreezeBalance(userId, frozenAmount);
            if (frozen == null) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setSymbol(request.getSymbol());
        order.setOrderSide(orderSide);
        order.setOrderType("LIMIT");
        order.setMarginMode(marginMode);
        order.setQuantity(quantity);
        order.setGroupId(request.getGroupId());
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFrozenAmount(frozenAmount);
        order.setCommission(commission);
        order.setStatus("PENDING");

        String side = request.getSide();
        order.setStopLosses(buildStopLosses(request.getStopLosses(), side, limitPrice, quantity));
        order.setTakeProfits(buildTakeProfits(request.getTakeProfits(), side, limitPrice, quantity));

        orderMapper.insert(order);

        limitIndex.add(order);

        log.info("futures限价开仓单 userId={} {} side={} qty={} limit={} frozen={}",
                userId, request.getSymbol(), request.getSide(), quantity, limitPrice, frozenAmount);

        return buildOrderResponse(order);
    }

    // ==================== 平仓 ====================

    @Override
    public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
        try (var _ = held(lockRegistry.tryLockAsUser("futures:pos:" + request.getPositionId()))) {
            return ledgerTx.call(FUTURES_CLOSE_SETTLE, () -> doClosePosition(userId, request));
        }
    }

    // 本方法唯一的资金动作就是逐仓平仓返还（全仓走 crossMarginService.settle，自带 CROSS_SETTLE），
    // 一个类型盖得住，不需要逐笔 mark。
    FuturesOrderResponse doClosePosition(Long userId, FuturesCloseRequest request) {
        FuturesPosition position = requireOpen(positionMapper.selectById(request.getPositionId()), userId);
        LedgerCtx.symbol(position.getSymbol());

        // quantity 空=全平：锁内取实时持仓量，消除"查列表→加锁"窗口期数量变化（如SL/TP部分成交）的误差
        BigDecimal closeQty = request.getQuantity() != null ? request.getQuantity() : position.getQuantity();
        // ≤0 必须拦：负数会让 atomicPartialClose 的 quantity-(-x) 反向加仓、盈亏符号翻转
        if (closeQty.signum() <= 0 || closeQty.compareTo(position.getQuantity()) > 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }
        // reduce-only 豁免最小名义额；部分平仓查步长，全量平仓豁免（存量尘埃仓能平干净）
        tradeFilterRegistry.validateFuturesClose(position.getSymbol(), closeQty, position.getQuantity());

        // 平多是卖、平空是买
        boolean buy = "SHORT".equals(position.getSide());
        BigDecimal limitPrice = null;
        BigDecimal price = null;
        if (!"MARKET".equals(request.getOrderType())) {
            limitPrice = request.getLimitPrice();
            tradingConfig.validateLimitPrice(limitPrice, prices.mark(position.getSymbol()));
            price = takePrice(position.getSymbol(), buy, closeQty, limitPrice);
            if (price == null) return createLimitCloseOrder(userId, position, closeQty, limitPrice);
        }

        // 当场成交：市价单，和挂上就能成交的限价单
        // 已经够强平、只是强平还没跑到：全仓整户等价格 tick 去爆，这里不让平；逐仓当场按强平处理（整仓）
        if (position.isCross()) {
            if (crossMarginService.snapshot(userId).liquidatable()) throw new BizException(ErrorCode.FUTURES_LIQUIDATING);
        } else {
            BigDecimal mark = cacheService.getMarkPrice(position.getSymbol());
            if (mark != null) {
                FuturesOrder liquidated = riskService.liquidateIfReached(position, mark);
                if (liquidated != null) return buildOrderResponse(liquidated);
            }
        }
        // 市价按盘口扫；没盘口退回最新价
        if (limitPrice == null) price = fillPricer.futures(position.getSymbol(), buy, closeQty, prices.latest(position.getSymbol()));
        return closeNow(userId, position, closeQty, price, limitPrice);
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

    /**
     * 反手：市价全平 → 立刻反向开等量新仓。
     * <p>
     * 不加 @Transactional/@Ledger：平仓与开仓各自复用 closePosition/openPosition 的独立锁+独立事务+
     * 独立记账（同 closeAllPositions 的组合方式），这层再套一层只会嵌套事务、重复记账。
     * 锁是<b>顺序</b>不是嵌套——平仓返回时 pos 锁已释放，开仓才去拿 sym 锁，不引入新的锁序。
     * <p>
     * 双向持仓下必须先平再开：同向下单会并入现有仓位，只开反向仓等于多空对冲同时挂着，不是反手。
     * 该币反方向本来就有仓的话，新开这笔按既定语义并入那个仓，不特殊处理。
     */
    @Override
    public ReverseResult reversePosition(Long userId, Long positionId) {
        FuturesCloseRequest closeReq = new FuturesCloseRequest();
        closeReq.setPositionId(positionId);
        closeReq.setOrderType("MARKET");   // quantity 不设=锁内取实时持仓量全平
        // 这步失败正常抛：什么都还没发生。属主与状态校验由 doClosePosition 在仓位锁内做
        FuturesOrderResponse closed = closePosition(userId, closeReq);
        // 平仓时已经到强平、按强平处理掉了：仓位是被强平的，不再反向开仓
        if ("LIQUIDATED".equals(closed.getStatus())) {
            return new ReverseResult(closed, null, new BizException(ErrorCode.FUTURES_LIQUIDATED).render(messages));
        }

        // 新仓参数一律取自平仓回执：那份是 doClosePosition 在仓位锁内读到的快照。
        // 自己在锁外先查一遍的话，这中间 adjustLeverage 跑完就会拿旧杠杆去开新仓——
        // 该币还有别的仓位就撞杠杆一致性校验变半成功，没有则静默把用户刚改的杠杆顶回去。
        // 数量同理，锁外那份可能已被 SL/TP 吃掉一部分
        FuturesOpenRequest openReq = new FuturesOpenRequest();
        openReq.setSymbol(closed.getSymbol());
        openReq.setSide("CLOSE_LONG".equals(closed.getOrderSide()) ? "SHORT" : "LONG");
        openReq.setMarginMode(closed.getMarginMode());
        openReq.setQuantity(closed.getQuantity());
        openReq.setLeverage(closed.getLeverage());
        openReq.setOrderType("MARKET");

        try {
            FuturesOrderResponse opened = openPosition(userId, openReq);
            log.info("futures反手 userId={} posId={} symbol={} {}→{} qty={} pnl={}", userId, positionId,
                    closed.getSymbol(), closed.getOrderSide(), openReq.getSide(),
                    closed.getQuantity(), closed.getRealizedPnl());
            return new ReverseResult(closed, opened, null);
        } catch (Exception e) {
            // 不回滚也不外抛：仓位是真平了、盈亏是真结算了，硬"回滚"等于凭空造一个仓位出来。
            // 抛出去前端只看到一句失败，用户不知道自己其实已经空仓——带着已平信息返回让前端说清楚
            log.warn("反手的反向开仓失败 userId={} posId={} symbol={} qty={}",
                    userId, positionId, closed.getSymbol(), closed.getQuantity(), e);
            // 业务异常的 getMessage() 是词表 key，直接带回前端就是一行 error.xxx
            String why = e instanceof BizException biz ? biz.render(messages) : e.getMessage();
            return new ReverseResult(closed, null, why);
        }
    }

    /** 当场成交的平仓，limitPrice 为空是市价单 */
    private FuturesOrderResponse closeNow(Long userId, FuturesPosition position, BigDecimal closeQty,
                                          BigDecimal price, BigDecimal limitPrice) {
        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        // 占用/划扣两种记账：全仓保证金没离开过余额，平仓只结盈亏净额（可为负，亏穿走破产）；
        // 逐仓把仓位口袋里的钱(保证金±盈亏-费)拿回来，亏损上限就是保证金，closeFill 已封顶
        BigDecimal marginPart = isFullClose ? position.getMargin()
                : position.getMargin().multiply(closeQty).divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
        CloseFill fill = closeFill(position, price, closeQty, marginPart, tradingConfig, true);
        BigDecimal closeValue = fill.value(closeQty);

        if (isFullClose) {
            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", fill.price(), fill.pnl());
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
            closedPositionCleanup.run(position);
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
            crossMarginService.settle(userId, fill.pnl().subtract(fill.commission()),
                    FUTURES_CLOSE_SETTLE, "POSITION", position.getId());
        } else {
            userMapper.atomicUpdateBalance(userId, fill.left(marginPart));
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType(orderType(limitPrice));
        order.setMarginMode(position.getMarginMode());
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(fill.price());
        order.setFilledAmount(closeValue);
        order.setCommission(fill.commission());
        order.setRealizedPnl(fill.pnl());
        order.setStatus("FILLED");
        orderMapper.insert(order);

        log.info("futures{}平仓 userId={} posId={} mode={} qty={} price={} pnl={}", limitPrice == null ? "市价" : "限价当场",
                userId, position.getId(), position.getMarginMode(), closeQty, fill.price(), fill.pnl());

        return buildOrderResponse(order);
    }

    /** 挂着等别人来吃的限价平仓单，按挂单费率收 */
    private FuturesOrderResponse createLimitCloseOrder(Long userId, FuturesPosition position,
                                                        BigDecimal closeQty, BigDecimal limitPrice) {
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

        String orderSide = "LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT";
        BigDecimal closeValue = limitPrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, false);

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
        orderMapper.insert(order);

        limitIndex.add(order);

        log.info("futures限价平仓单 userId={} posId={} qty={} limit={}",
                userId, position.getId(), closeQty, limitPrice);

        return buildOrderResponse(order);
    }

    // ==================== 取消限价单 ====================

    @Override
    public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
        FuturesOrder order = ledgerTx.call(FUTURES_LIMIT_UNFREEZE, () -> doCancelOrder(userId, orderId));
        // 索引跟着DB走：事务提交后才摘索引。搁事务里解冻一失败回滚，单子退回PENDING而索引已没了=悬空
        limitIndex.remove(order);
        return buildOrderResponse(order);
    }

    FuturesOrder doCancelOrder(Long userId, Long orderId) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        LedgerCtx.symbol(order.getSymbol());
        if (!"PENDING".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        int affected = orderMapper.casCancel(orderId, "PENDING", "USER");
        if (affected == 0) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        // 逐仓开/加仓单解冻资金；全仓单没冻结过钱，状态一改挂单占用自动消失
        if (!order.getOrderSide().startsWith("CLOSE") && !FuturesPosition.CROSS.equals(order.getMarginMode())) {
            userMapper.atomicUnfreezeBalance(userId, order.getFrozenAmount());
        }

        order.setStatus("CANCELLED");
        order.setCancelReason("USER");
        order.setUpdatedAt(LocalDateTime.now());
        log.info("futures取消限价单 userId={} orderId={}", userId, orderId);
        return order;
    }

    /** 改一张还挂着的开仓限价单带的止损止盈：哪个列表不传就不动哪个，传空列表是清掉；单子已触发、成交或撤掉就拒 */
    @Override
    public void setOrderProtection(Long userId, Long orderId, FuturesOrderProtectionRequest request) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        if (!"PENDING".equals(order.getStatus()) || !"LIMIT".equals(order.getOrderType())
                || !List.of("OPEN_LONG", "OPEN_SHORT").contains(order.getOrderSide())) {
            throw new BizException(ErrorCode.ORDER_NOT_PENDING);
        }
        String side = order.getOrderSide().substring(5);
        List<FuturesStopLoss> sls = request.getStopLosses() == null ? null : request.getStopLosses().isEmpty()
                ? List.of() : buildStopLosses(request.getStopLosses(), side, order.getLimitPrice(), order.getQuantity());
        List<FuturesTakeProfit> tps = request.getTakeProfits() == null ? null : request.getTakeProfits().isEmpty()
                ? List.of() : buildTakeProfits(request.getTakeProfits(), side, order.getLimitPrice(), order.getQuantity());
        if (orderMapper.updatePendingProtection(userId, orderId, sls, tps) == 0) {
            throw new BizException(ErrorCode.ORDER_NOT_PENDING);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 限价单挂上就能成交的成交价：对面盘口在挂单价以内的量够整笔吃掉就按盘口扫，没盘口看最新价够不够得着挂单价。
     * 够不着返回 null，挂着等别人来吃。feed 30 秒没写过价就拒单
     */
    private BigDecimal takePrice(String symbol, boolean buy, BigDecimal qty, BigDecimal limitPrice) {
        return fillPricer.futuresWithinLimit(symbol, buy, qty, limitPrice, prices.fresh(symbol));
    }

    private static String orderType(BigDecimal limitPrice) {
        return limitPrice == null ? "MARKET" : "LIMIT";
    }

    private void getAndValidateUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (Boolean.TRUE.equals(user.getIsBankrupt())) throw new BizException(ErrorCode.USER_BANKRUPT);
    }
}
