package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;
import static com.mawai.wiibsim.service.impl.FuturesHelper.markPrice;
import static com.mawai.wiibsim.service.impl.FuturesHelper.removeFromLimitZSet;
import static com.mawai.wiibsim.service.impl.FuturesHelper.toEpochMs;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossLiquidationServiceImpl implements CrossLiquidationService {

    private final CrossMarginService crossMarginService;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final RedisLockUtil redisLockUtil;
    private final TradeNotificationService tradeNotificationService;
    private final CrossBandRegistry bandRegistry;

    @Override
    public void onPriceTick(String symbol, BigDecimal markPrice) {
        double price = markPrice.doubleValue();
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            long userId = Long.parseLong(uid);
            // 带内免检，无带/带作废/出带才精查，（推导见 CrossBandRegistry）。
            if (!bandRegistry.shouldCheck(userId, symbol, price)) continue;
            Thread.startVirtualThread(() -> checkUser(userId, symbol, markPrice));
        }
    }

    @Override
    public void recoverGap(String symbol, List<KlineBar> markBars) {
        if (markBars.isEmpty()) return;
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            long userId = Long.parseLong(uid);
            long since = latestCrossOpenAt(userId, symbol);
            if (since == 0L) continue;   // 该 symbol 上没全仓仓位
            BigDecimal[] range = KlineBar.lowHighAfter(markBars, since);
            if (range == null) continue; // 开仓晚于整段行情
            // 插针藏在区间两端：低端抓多头重的账户、高端抓空头重的（equity 对单 symbol 价格线性，端点即最坏）
            for (BigDecimal pin : List.of(range[0], range[1])) {
                if (!bandRegistry.shouldCheck(userId, symbol, pin.doubleValue())) continue;
                Thread.startVirtualThread(() -> checkUser(userId, symbol, pin));
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
        checkUser(userId, null, null);
    }

    @Override
    public void checkUser(Long userId, String pinSymbol, BigDecimal pinPrice) {
        // 用户级锁：同一账户的检查/爆仓串行。有界等待而非抢不到即弃——
        // 插针触发排在前一个精查后面时必须等到它，静默丢弃 = 漏针
        String lockKey = "futures:cross:liq:" + userId;
        String lockValue = redisLockUtil.tryLockWithWait(lockKey, 30, 30_000);
        if (lockValue == null) {
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
                SpringUtils.getAopProxy(this).liquidateAll(userId, pinSymbol, pinPrice);
                return;
            }
            rebuildBand(userId, epoch, account);
        } catch (Exception e) {
            log.error("全仓健康检查失败 userId={}", userId, e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
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
     * 全组爆：所有全仓仓位按 mark 价强平，盈亏净额一次结算进余额（允许为负）。
     * 结算后余额 &lt; 0 = 穿仓 → 立即破产（游戏钱包也保不住，这是用户要自己控制的风险点）。
     * <p>pinSymbol 的结算价钉在 pinPrice：插针触发的爆仓按触发那一刻的价格结算，
     * 缓存价回落不影响——判定与结算同一口径，否则会出现"按50判爆、按100结算"的分裂。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    protected void liquidateAll(Long userId, String pinSymbol, BigDecimal pinPrice) {
        var positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));
        if (positions.isEmpty()) return;

        BigDecimal settle = BigDecimal.ZERO;
        int closed = 0;
        for (FuturesPosition pos : positions) {
            BigDecimal price = pos.getSymbol().equals(pinSymbol)
                    ? pinPrice : markPrice(cacheService, pos.getSymbol());
            BigDecimal pnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), price, pos.getQuantity());
            BigDecimal closeValue = price.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

            // CAS抢平仓权：并发手动平仓赢了就跳过本仓，不重复结算
            if (positionMapper.casClosePosition(pos.getId(), "LIQUIDATED", price, pnl) == 0) continue;
            positionIndexService.unregisterAll(pos); // 全仓无LIQ索引，清的是SL/TP残留

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
            order.setRealizedPnl(pnl);
            order.setStatus("LIQUIDATED");
            orderMapper.insert(order);

            settle = settle.add(pnl).subtract(commission);
            closed++;
        }
        if (closed == 0) return;

        int cancelled = cancelCrossOpenOrders(userId);

        log.warn("全仓爆仓 userId={} 平仓数={} 撤单数={} 结算={}", userId, closed, cancelled, settle);
        // 合并成一条：每仓一条会把信封刷满。closed 只统计 CAS 抢到的仓位，并发手动平仓的那些不算进来
        tradeNotificationService.crossLiquidation(userId, closed, settle);
        // 占用制下保证金没离开过余额，结算只记盈亏净额；全平后已无全仓仓位，扣穿由 settle 触发破产
        crossMarginService.settle(userId, settle);
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
