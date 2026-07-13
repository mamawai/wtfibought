package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibcommon.util.TradingDayUtil;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankruptcyServiceImpl implements BankruptcyService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final CryptoPositionService cryptoPositionService;
    private final TradingConfig tradingConfig;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionMapper cryptoPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final FuturesPositionIndexService futuresPositionIndexService;
    private final PredictionBetMapper predictionBetMapper;
    private final AssetValuationService assetValuationService;

    @Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

    @Override
    public void checkAndLiquidateAll() {
        if (!tradingConfig.getMargin().isEnabled()) {
            return;
        }

        List<User> users = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, false)
                .and(w -> w.gt(User::getMarginLoanPrincipal, BigDecimal.ZERO)
                        .or()
                        .gt(User::getMarginInterestAccrued, BigDecimal.ZERO)));
        if (users.isEmpty()) {
            return;
        }

        LocalDate today = LocalDate.now();
        for (User user : users) {
            try {
                if (shouldBankrupt(user.getId())) {
                    SpringUtils.getAopProxy(this).liquidateUser(user.getId(), today);
                }
            } catch (Exception e) {
                log.error("爆仓检查失败 userId={}", user.getId(), e);
            }
        }
    }

    @Override
    public void resetBankruptUsers(LocalDate today) {

        List<User> users = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, true)
                .le(User::getBankruptResetDate, today));
        if (users.isEmpty()) {
            return;
        }

        for (User user : users) {
            try {
                SpringUtils.getAopProxy(this).resetUser(user.getId(), today);
            } catch (Exception e) {
                log.error("破产恢复失败 userId={}", user.getId(), e);
            }
        }
    }

    private boolean shouldBankrupt(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            return false;
        }

        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal principal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal interest = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;

        // 现货持仓市值（crypto + bStock，均在 crypto_position）
        BigDecimal marketValue = cryptoPositionService.calculateCryptoMarketValue(userId);

        // 合约仓位 margin + 浮盈亏(统一口径见 AssetValuationService)；旧版只算保证金，深亏用户会被高估净资产、延迟破产
        List<FuturesPosition> futuresPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : futuresPositions) {
            BigDecimal markPrice = assetValuationService.resolveFuturesPrice(fp.getSymbol());
            marketValue = marketValue.add(AssetValuationService.futuresPositionValue(fp, markPrice));
        }

        // 预测按立刻卖出价估值, 无bid视为不可变现
        marketValue = marketValue.add(assetValuationService.predictionMarketValue(userId));
        // crypto待结算（老股 T+1 已退）
        BigDecimal pendingSettlement = cryptoOrderMapper.sumSettlingAmount(userId);

        BigDecimal netAssets = balance
                .add(frozen)
                .add(marketValue)
                .add(pendingSettlement)
                .subtract(principal)
                .subtract(interest);

        return netAssets.compareTo(BigDecimal.ZERO) <= 0;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void liquidateUser(Long userId, LocalDate today) {
        LocalDate resetDate = TradingDayUtil.nextTradingDay(today);

        int affected = userMapper.markBankrupt(userId, resetDate, today);
        if (affected == 0) {
            return;
        }

        cleanupUserHoldings(userId, "LIQUIDATED");
        log.warn("用户爆仓 userId={} resetDate={}", userId, resetDate);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void resetUser(Long userId, LocalDate today) {
        int affected = userMapper.resetAfterBankruptcy(userId, initialBalance, today);
        if (affected == 0) {
            return;
        }

        cleanupUserHoldings(userId, "CLOSED");
        log.info("用户恢复初始资金 userId={} balance={}", userId, initialBalance);
    }

    /** 爆仓清算/破产恢复共用的持仓清理序列；futuresCloseStatus 区分 LIQUIDATED/CLOSED。 */
    private void cleanupUserHoldings(Long userId, String futuresCloseStatus) {
        cryptoOrderMapper.cancelOpenOrdersByUserId(userId);
        cryptoPositionMapper.deleteByUserId(userId);
        cleanupFuturesRedisIndexes(userId);
        futuresOrderMapper.cancelOpenOrdersByUserId(userId);
        futuresPositionMapper.closeOpenByUserId(userId, futuresCloseStatus);
        // 预测: 取消活跃投注(爆仓不退款；恢复路径为防御性清残留)
        predictionBetMapper.cancelActiveByUserId(userId);
    }

    private void cleanupFuturesRedisIndexes(Long userId) {
        List<FuturesOrder> pendingOrders = futuresOrderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getUserId, userId)
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        for (FuturesOrder order : pendingOrders) {
            FuturesHelper.removeFromLimitZSet(order, cacheService);
        }

        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition position : openPositions) {
            futuresPositionIndexService.unregisterAll(position);
        }
    }

}
