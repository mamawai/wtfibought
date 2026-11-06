package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.BookFillPricer;
import com.mawai.wiibsim.service.CrossLiquidationService;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.stream.IntStream;

import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;
import static com.mawai.wiibsim.service.impl.FuturesHelper.cancelCloseOrders;
import static com.mawai.wiibsim.service.impl.FuturesHelper.markPrice;
import static com.mawai.wiibsim.service.impl.FuturesHelper.removeFromLimitZSet;
import static com.mawai.wiibsim.service.impl.FuturesHelper.toEpochMs;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossLiquidationServiceImpl implements CrossLiquidationService {

    /** 全仓用户级锁：同一账户的检查/爆仓/全仓止损串行 */
    private static final String LIQ_LOCK_PREFIX = "futures:cross:liq:";

    private final CrossMarginService crossMarginService;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final FairLockRegistry lockRegistry;
    private final TradeNotificationService tradeNotificationService;
    private final CrossBandRegistry bandRegistry;
    private final FuturesRiskService futuresRiskService;
    private final TransactionTemplate transactionTemplate;
    private final TradeFilterRegistry filterRegistry;
    private final UserMapper userMapper;
    private final BankruptcyService bankruptcyService;
    private final BookFillPricer fillPricer;

    @Override
    public void onPriceTick(String symbol, BigDecimal markPrice) {
        double price = markPrice.doubleValue();
        long now = System.currentTimeMillis();
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            long userId = Long.parseLong(uid);
            // 带内免检，无带/带作废/出带才精查，（推导见 CrossBandRegistry）。
            if (!bandRegistry.shouldCheck(userId, symbol, price)) continue;
            Thread.startVirtualThread(() -> checkUser(userId, symbol, markPrice, now));
        }
    }

    @Override
    public void recoverGap(String symbol, List<KlineBar> markBars) {
        if (markBars.isEmpty()) return;
        // 钉价是空窗里的价，时刻记成空窗第一根 K 线开盘：爆仓按钉价成交，不扫现在的盘口
        long pinAt = markBars.getFirst().openTime();
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            long userId = Long.parseLong(uid);
            long since = latestCrossOpenAt(userId, symbol);
            if (since == 0L) continue;   // 该 symbol 上没全仓仓位
            BigDecimal[] range = KlineBar.lowHighAfter(markBars, since);
            if (range == null) continue; // 开仓晚于整段行情
            // 插针藏在区间两端：低端抓多头重的账户、高端抓空头重的（equity 对单 symbol 价格线性，端点即最坏）
            for (BigDecimal pin : List.of(range[0], range[1])) {
                if (!bandRegistry.shouldCheck(userId, symbol, pin.doubleValue())) continue;
                Thread.startVirtualThread(() -> checkUser(userId, symbol, pin, pinAt));
            }
        }
    }

    /** 该用户在该 symbol 上全仓持仓的最晚开仓时间；没仓位返回 0 */
    private long latestCrossOpenAt(long userId, String symbol) {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, symbol)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));
        long max = 0L;
        for (FuturesPosition p : positions) max = Math.max(max, toEpochMs(p.getCreatedAt()));
        return max;
    }

    @Override
    public void checkUser(Long userId) {
        checkUser(userId, null, null, System.currentTimeMillis());
    }

    @Override
    public void checkUser(Long userId, String pinSymbol, BigDecimal pinPrice, long pinAt) {
        // 用户级锁：同一账户的检查/爆仓串行。有界等待而非抢不到即弃——
        // 插针触发排在前一个精查后面时必须等到它，静默丢弃 = 漏针
        Lock lock = lockRegistry.tryLockAsSystem(LIQ_LOCK_PREFIX + userId);
        if (lock == null) {
            log.error("全仓精查获锁超时 userId={} pin={}@{}，交由兜底轮询重查", userId, pinSymbol, pinPrice);
            return;
        }
        try {
            // 锁后复检：排队期间前一个精查可能已重建带，钉价在新带内 = 已被数学证明安全，省一次快照
            if (pinSymbol != null && !bandRegistry.shouldCheck(userId, pinSymbol, pinPrice.doubleValue())) return;

            long epoch = bandRegistry.epoch(userId); // 先捕纪元再读快照：期间资金变动会让回填天然失效
            var account = crossMarginService.snapshot(userId, pinSymbol, pinPrice);
            if (account.positions().isEmpty()) {
                // 仓位已被别的路径清掉（如破产清算），顺手把索引和带的残留擦干净
                crossMarginService.refreshUserIndex(userId);
                bandRegistry.remove(userId);
                return;
            }
            if (account.liquidatable()) {
                liquidateWithPositionLocks(userId, pinSymbol, pinPrice, pinAt, account.positions());
                return;
            }
            rebuildBand(userId, epoch, account);
        } catch (Exception e) {
            log.error("全仓健康检查失败 userId={}", userId, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void triggerStopLoss(FuturesPosition position, Collection<String> slIds, BigDecimal price, long priceAt) {
        Long userId = position.getUserId();
        Lock lock = lockRegistry.tryLockAsSystem(LIQ_LOCK_PREFIX + userId);
        if (lock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            // 止损看成交价、爆仓看标记价：按现在的标记价已经够爆，就是爆仓先到，爆了止损作废
            var account = crossMarginService.snapshot(userId);
            if (account.liquidatable() && liquidateWithPositionLocks(userId, null, null,
                    System.currentTimeMillis(), account.positions())) return;
            futuresRiskService.batchTriggerStopLoss(position.getId(), slIds, price, priceAt);
        } finally {
            lock.unlock();
        }
    }

    /** 精查末尾回填安全带；缓冲耗尽（半宽0）则清带 = 退化为每 tick 必查，方向 fail-safe */
    private void rebuildBand(Long userId, long epoch, CrossMarginService.CrossAccount account) {
        BigDecimal totalNotional = BigDecimal.ZERO;
        for (FuturesPosition pos : account.positions()) {
            totalNotional = totalNotional.add(
                    account.refPrices().get(pos.getSymbol()).multiply(pos.getQuantity()));
        }
        BigDecimal halfWidth = CrossBandRegistry.halfWidth(account.equity(),
                account.usedMargin(), account.maintenanceMargin(), totalNotional);
        if (halfWidth.signum() > 0) {
            bandRegistry.put(userId, epoch, CrossBandRegistry.ranges(account.refPrices(), halfWidth));
        } else {
            bandRegistry.remove(userId);
        }
    }

    @Override
    public void sweepAll() {
        for (String uid : crossMarginService.allCrossUsers()) {
            try {
                checkUser(Long.parseLong(uid));
            } catch (Exception e) {
                log.error("全仓兜底巡检失败 uid={}", uid, e);
            }
        }
    }

    /**
     * 按 id 升序拿齐这些全仓仓位的锁再爆，仓位锁在 liquidateAll 事务提交之后才放。
     * 锁序：cross:liq 用户锁 → 仓位锁。某个仓位锁等不到就不爆，抛 ORDER_PROCESSING。
     * 返回账户是否被爆掉。
     */
    private boolean liquidateWithPositionLocks(Long userId, String pinSymbol, BigDecimal pinPrice, long pinAt,
                                               List<FuturesPosition> positions) {
        List<Long> ids = positions.stream().map(FuturesPosition::getId).sorted().toList();
        List<Lock> held = new ArrayList<>();
        try {
            for (Long id : ids) {
                Lock lock = lockRegistry.tryLockAsSystem("futures:pos:" + id);
                if (lock == null) {
                    log.warn("全仓爆仓等仓位锁超时 userId={} posId={}，本次不爆，交由后续巡检重查", userId, id);
                    throw new BizException(ErrorCode.ORDER_PROCESSING);
                }
                held.add(lock);
            }
            return Boolean.TRUE.equals(transactionTemplate.execute(_ -> liquidateAll(userId, pinSymbol, pinPrice, pinAt, ids)));
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        }
    }

    /**
     * 全组爆：所有全仓仓位强平，盈亏净额减强平清算费一次结算进余额（允许为负）。
     * 结算后余额 &lt; 0 = 穿仓 → 立即破产（游戏钱包也保不住，这是用户要自己控制的风险点）。
     * <p>判爆看标记价（pinSymbol 钉在 pinPrice），成交按各仓自己的盘口扫，没盘口、或钉价是旧价（pinAt 在 1 秒前）
     * 才用判爆那个价——跟币安一样，触发看标记价、平仓是吃盘口的市价单。</p>
     * <p>positionIds 是调用方已经锁住的全仓仓位。返回有没有平掉仓位。</p>
     */
    boolean liquidateAll(Long userId, String pinSymbol, BigDecimal pinPrice, long pinAt, List<Long> positionIds) {
        // 先锁 user 行再读快照，锁到事务提交：下面算清算费上限用的就是快照里的余额
        userMapper.selectByIdForUpdate(userId);
        // 锁内按触发价重判
        var account = crossMarginService.snapshot(userId, pinSymbol, pinPrice);
        if (!account.liquidatable()) return false;

        long now = System.currentTimeMillis();
        BigDecimal settle = BigDecimal.ZERO;
        BigDecimal feeTotal = BigDecimal.ZERO;
        List<FuturesOrder> orders = new ArrayList<>();
        for (Long id : positionIds) {
            // 锁内重读，按当前持仓数量结算
            FuturesPosition pos = positionMapper.selectById(id);
            if (pos == null || !"OPEN".equals(pos.getStatus())) continue;
            boolean pinned = pos.getSymbol().equals(pinSymbol);
            BigDecimal judgePrice = pinned ? pinPrice : markPrice(cacheService, pos.getSymbol());
            BigDecimal price = fillPricer.futures(pos.getSymbol(), "SHORT".equals(pos.getSide()), pos.getQuantity(),
                    judgePrice, pinned ? pinAt : now);
            BigDecimal pnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), price, pos.getQuantity());
            BigDecimal closeValue = price.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

            // CAS兜底：不拿仓位锁的路径（破产清算）先关了就跳过本仓，不重复结算
            if (positionMapper.casClosePosition(pos.getId(), "LIQUIDATED", price, pnl) == 0) continue;
            positionIndexService.unregisterAll(pos); // 全仓无LIQ索引，清的是SL/TP残留
            cancelCloseOrders(pos.getId(), orderMapper, cacheService);

            FuturesOrder order = new FuturesOrder();
            order.setUserId(userId);
            order.setPositionId(pos.getId());
            order.setSymbol(pos.getSymbol());
            order.setOrderSide("LONG".equals(pos.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
            order.setOrderType("MARKET");
            order.setMarginMode(FuturesPosition.CROSS);
            order.setQuantity(pos.getQuantity());
            order.setLeverage(pos.getLeverage());
            order.setFilledPrice(price);
            order.setFilledAmount(closeValue);
            order.setCommission(commission);
            order.setLiquidationFee(filterRegistry.liquidationFee(pos.getSymbol(), closeValue));
            order.setRealizedPnl(pnl);
            order.setStatus("LIQUIDATED");
            orders.add(order);

            settle = settle.add(pnl).subtract(commission);
            feeTotal = feeTotal.add(order.getLiquidationFee());
        }
        if (orders.isEmpty()) return false;

        // 清算费最多扣到余额为 0；已经亏穿的账户一分不收
        BigDecimal room = account.balance().add(settle).max(BigDecimal.ZERO);
        if (feeTotal.compareTo(room) > 0) capFees(orders, feeTotal, room);
        for (FuturesOrder order : orders) {
            orderMapper.insert(order);
            settle = settle.subtract(order.getLiquidationFee());
        }
        int closed = orders.size();

        int cancelled = cancelCrossOpenOrders(userId);

        log.warn("全仓爆仓 userId={} 平仓数={} 撤单数={} 结算={}", userId, closed, cancelled, settle);
        // 合并成一条：每仓一条会把信封刷满。closed 只统计 CAS 抢到的仓位，并发手动平仓的那些不算进来
        tradeNotificationService.crossLiquidation(userId, closed, settle);
        // 占用制下保证金没离开过余额，结算只记盈亏净额；全平后已无全仓仓位，扣穿由 settle 触发破产
        crossMarginService.settle(userId, settle);
        // 没扣穿但交易侧什么都不剩了（清算费正好扣到 0）也破产；爆仓提交了再判，判破产出错不能把爆仓一起回滚
        AfterCommit.run(() -> bankruptcyService.bankruptIfNothingLeft(userId));
        return true;
    }

    /**
     * 清算费合计超过 room 时压到正好等于 room：各仓先按比例向下取整，取整剩的零头从清算费大的仓补起，
     * 每仓补到自己原本的清算费为止
     */
    private static void capFees(List<FuturesOrder> orders, BigDecimal feeTotal, BigDecimal room) {
        List<BigDecimal> nominal = orders.stream().map(FuturesOrder::getLiquidationFee).toList();
        BigDecimal rest = room;
        for (FuturesOrder o : orders) {
            o.setLiquidationFee(o.getLiquidationFee().multiply(room).divide(feeTotal, 2, RoundingMode.DOWN));
            rest = rest.subtract(o.getLiquidationFee());
        }
        List<Integer> byFee = IntStream.range(0, orders.size()).boxed()
                .sorted(Comparator.comparing(nominal::get).reversed()).toList();
        for (int i : byFee) {
            if (rest.signum() == 0) break;
            FuturesOrder o = orders.get(i);
            BigDecimal add = nominal.get(i).subtract(o.getLiquidationFee()).min(rest);
            o.setLiquidationFee(o.getLiquidationFee().add(add));
            rest = rest.subtract(add);
        }
    }

    /**
     * 爆仓后撤掉该用户残留的全仓开仓挂单。
     */
    private int cancelCrossOpenOrders(Long userId) {
        List<FuturesOrder> pendings = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getUserId, userId)
                .eq(FuturesOrder::getMarginMode, FuturesPosition.CROSS)
                .in(FuturesOrder::getStatus, "PENDING", "TRIGGERED")
                .notLikeRight(FuturesOrder::getOrderSide, "CLOSE"));

        int cancelled = 0;
        for (FuturesOrder order : pendings) {
            // 逐单CAS不批量UPDATE：TRIGGERED单可能正被doProcessTriggeredOrder抢去转PROCESSING，抢输了就别动
            if (orderMapper.casUpdateStatus(order.getId(), order.getStatus(), "CANCELLED") == 0) continue;
            // TRIGGERED单的索引在触发时(CAS落定后)已摘掉，这里ZREM返0无害；PENDING单靠这句摘干净。
            // 注意这句跑在 liquidateAll 的事务里，摘在提交之前：后面 settle 抛了就回滚，
            // 单退回PENDING而索引已经没了，这段悬空由每小时的 reconcileLimitOrderIndex 补回来
            removeFromLimitZSet(order, cacheService);
            cancelled++;
        }
        return cancelled;
    }
}
