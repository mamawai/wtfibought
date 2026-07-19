package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;
import static com.mawai.wiibsim.service.impl.FuturesHelper.markPrice;

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

    @Override
    public void onPriceTick(String symbol) {
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            Thread.startVirtualThread(() -> checkUser(Long.parseLong(uid)));
        }
    }

    @Override
    public void checkUser(Long userId) {
        // 用户级锁：同一账户的检查/爆仓串行，价格tick与兜底轮询撞车时后到者直接放弃（下轮总会再来）
        String lockKey = "futures:cross:liq:" + userId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            var account = crossMarginService.snapshot(userId);
            if (account.positions().isEmpty()) {
                // 仓位已被别的路径清掉（如破产清算），顺手把索引残留擦干净
                crossMarginService.refreshUserIndex(userId);
                return;
            }
            if (!account.liquidatable()) return;
            SpringUtils.getAopProxy(this).liquidateAll(userId);
        } catch (Exception e) {
            log.error("全仓健康检查失败 userId={}", userId, e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
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
     */
    @Transactional(rollbackFor = Exception.class)
    protected void liquidateAll(Long userId) {
        var positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));
        if (positions.isEmpty()) return;

        BigDecimal settle = BigDecimal.ZERO;
        int closed = 0;
        for (FuturesPosition pos : positions) {
            BigDecimal price = markPrice(cacheService, pos.getSymbol());
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

        log.warn("全仓爆仓 userId={} 平仓数={} 结算={}", userId, closed, settle);
        // 占用制下保证金没离开过余额，结算只记盈亏净额；全平后已无全仓仓位，扣穿由 settle 触发破产
        crossMarginService.settle(userId, settle);
    }
}
