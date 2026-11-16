package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.dto.FuturesAdjustLeverageRequest;
import com.mawai.wiibcommon.dto.FuturesReduceMarginRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import com.mawai.wiibsim.util.FairLockRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_ADD_MARGIN;
import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_LEVERAGE_RELEASE;
import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_REDUCE_MARGIN;
import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;
import static com.mawai.wiibsim.service.impl.FuturesHelper.normalizeLeverage;
import static com.mawai.wiibsim.service.impl.FuturesHelper.requireOpen;
import static com.mawai.wiibsim.service.impl.FuturesHelper.validateLeverage;
import static com.mawai.wiibsim.util.FairLockRegistry.held;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesMarginServiceImpl implements FuturesMarginService {

    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final TradingConfig tradingConfig;
    private final FairLockRegistry lockRegistry;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;
    private final LedgerTx ledgerTx;
    private final FuturesPrices prices;

    // ==================== 追加保证金 ====================

    @Override
    public void addMargin(Long userId, FuturesAddMarginRequest request) {
        try (var _ = held(lockRegistry.tryLockAsUser("futures:pos:" + request.getPositionId()))) {
            ledgerTx.run(FUTURES_ADD_MARGIN, () -> doAddMargin(userId, request));
        }
    }

    void doAddMargin(Long userId, FuturesAddMarginRequest request) {
        FuturesPosition position = requireOpen(positionMapper.selectById(request.getPositionId()), userId);
        LedgerCtx.symbol(position.getSymbol());
        if (position.isCross()) throw new BizException(ErrorCode.FUTURES_CROSS_MARGIN_ADJUST);

        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // 往逐仓仓位里划钱 = 吃余额钱包额度，全仓占用的部分不能拿来给逐仓补血
        crossMarginService.assertCanAfford(userId, amount);
        BigDecimal after = userMapper.atomicUpdateBalance(userId, amount.negate());
        if (after == null) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

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
        try (var _ = held(lockRegistry.tryLockAsUser("futures:pos:" + request.getPositionId()))) {
            ledgerTx.run(FUTURES_REDUCE_MARGIN, () -> doReduceMargin(userId, request));
        }
    }

    void doReduceMargin(Long userId, FuturesReduceMarginRequest request) {
        FuturesPosition position = requireOpen(positionMapper.selectById(request.getPositionId()), userId);
        LedgerCtx.symbol(position.getSymbol());
        if (position.isCross()) throw new BizException(ErrorCode.FUTURES_CROSS_MARGIN_ADJUST);

        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        BigDecimal newMargin = position.getMargin().subtract(amount);
        if (newMargin.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.FUTURES_MARGIN_TOO_LOW);
        }

        BigDecimal markPrice = prices.mark(position.getSymbol());
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

        BigDecimal credited = userMapper.atomicUpdateBalance(userId, amount);
        if (credited == null) throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);

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
        try (var _ = held(lockRegistry.tryLockAsUser("futures:sym:" + userId + ":" + request.getSymbol()))) {
            List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                    .eq(FuturesPosition::getUserId, userId)
                    .eq(FuturesPosition::getSymbol, request.getSymbol())
                    .eq(FuturesPosition::getStatus, "OPEN")
                    .orderByAsc(FuturesPosition::getId));
            if (positions.isEmpty()) throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);

            // 仓位锁张数不定，拿到一张记一张，最后倒着放
            List<Lock> posLocks = new ArrayList<>();
            try {
                for (FuturesPosition p : positions) {
                    Lock posLock = lockRegistry.tryLockAsUser("futures:pos:" + p.getId());
                    if (posLock == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
                    posLocks.add(posLock);
                }
                ledgerTx.run(FUTURES_LEVERAGE_RELEASE, () -> doAdjustLeverage(userId, request));
            } finally {
                for (int i = posLocks.size() - 1; i >= 0; i--) {
                    posLocks.get(i).unlock();
                }
            }
        }
    }

    // 本方法唯一的资金动作就是逐仓调高杠杆释放多余保证金（全仓只改占用数字，钱不动），一个类型盖得住。
    void doAdjustLeverage(Long userId, FuturesAdjustLeverageRequest request) {
        LedgerCtx.symbol(request.getSymbol());
        // 锁内重查：拿锁前仓位可能已被平掉/强平
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getSymbol, request.getSymbol())
                .eq(FuturesPosition::getStatus, "OPEN")
                .orderByAsc(FuturesPosition::getId));
        if (positions.isEmpty()) throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);

        int newLeverage = normalizeLeverage(request.getLeverage(), tradingConfig.getFutures().getMaxLeverage());
        if (positions.stream().allMatch(p -> p.getLeverage() == newLeverage)) return;

        BigDecimal markPrice = prices.mark(request.getSymbol());

        // 先全量校验再落库：任何一张不过整体拒绝（事务兜底，但不做半程无用功）
        for (FuturesPosition p : positions) {
            validateLeverage(p.getSymbol(), newLeverage,
                    bracketRegistry.getEffectiveMaxLeverage(p.getSymbol(), markPrice.multiply(p.getQuantity())));
            // 逐仓只能调高：调低=要补钱，走追加保证金即可，不重复做一套
            if (!p.isCross() && newLeverage < p.getLeverage()) throw new BizException(ErrorCode.FUTURES_LEVERAGE_ONLY_UP);
        }

        // 全仓占用增量合并过闸：多空一起调低时两张都多占，可用余额一次性校验（正负互抵后为净增量才拦）
        BigDecimal crossDelta = BigDecimal.ZERO;
        for (FuturesPosition p : positions) {
            if (p.isCross()) crossDelta = crossDelta.add(targetMargin(p, newLeverage).subtract(p.getMargin()));
        }
        if (crossDelta.signum() > 0) crossMarginService.assertCanAfford(userId, crossDelta);

        boolean crossAdjusted = false;
        for (FuturesPosition p : positions) {
            if (p.isCross()) {
                adjustCrossLeverage(p, newLeverage);
                crossAdjusted = true;
            } else {
                adjustIsolatedLeverage(userId, p, newLeverage, markPrice);
            }
        }
        // 全仓占用变了：强平安全带的流出下界 (U−mm)/2 失真，汇入 refreshUserIndex 作废——
        // 这是占用变动唯一不经开平仓/成交/settle 的口子，其余路径都已就近调它
        if (crossAdjusted) crossMarginService.refreshUserIndex(userId);

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
}
