package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import com.mawai.wiibservice.mapper.CryptoPositionMapper;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.OrderMapper;
import com.mawai.wiibservice.mapper.PositionMapper;
import com.mawai.wiibservice.mapper.SettlementMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.BankruptcyService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.CryptoPositionService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import com.mawai.wiibservice.service.PositionService;
import com.mawai.wiibservice.service.SettlementService;
import com.mawai.wiibservice.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankruptcyServiceImpl implements BankruptcyService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final PositionMapper positionMapper;
    private final SettlementMapper settlementMapper;
    private final PositionService positionService;
    private final CryptoPositionService cryptoPositionService;
    private final SettlementService settlementService;
    private final TradingConfig tradingConfig;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionMapper cryptoPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final FuturesPositionIndexService futuresPositionIndexService;

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
        if (today == null) {
            return;
        }

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

        BigDecimal marketValue = positionService.calculateTotalMarketValue(userId);

        // crypto持仓市值
        marketValue = marketValue.add(cryptoPositionService.calculateCryptoMarketValue(userId));

        // futures保证金
        BigDecimal futuresMargin = futuresPositionMapper.sumOpenMargin(userId);
        marketValue = marketValue.add(futuresMargin);

        BigDecimal pendingSettlement = settlementService.getPendingSettlements(userId).stream()
                .map(Settlement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        pendingSettlement = pendingSettlement.add(cryptoOrderMapper.sumSettlingAmount(userId));

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
        LocalDate resetDate = nextTradingDay(today);

        int affected = userMapper.markBankrupt(userId, resetDate, today);
        if (affected == 0) {
            return;
        }

        orderMapper.cancelOpenOrdersByUserId(userId);
        positionMapper.deleteByUserId(userId);
        cryptoOrderMapper.cancelOpenOrdersByUserId(userId);
        cryptoPositionMapper.deleteByUserId(userId);
        cleanupFuturesRedisIndexes(userId);
        futuresOrderMapper.cancelOpenOrdersByUserId(userId);
        futuresPositionMapper.closeOpenByUserId(userId, "LIQUIDATED");
        settlementMapper.deletePendingByUserId(userId);

        log.warn("用户爆仓 userId={} resetDate={}", userId, resetDate);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void resetUser(Long userId, LocalDate today) {
        int affected = userMapper.resetAfterBankruptcy(userId, initialBalance, today);
        if (affected == 0) {
            return;
        }

        orderMapper.cancelOpenOrdersByUserId(userId);
        positionMapper.deleteByUserId(userId);
        cryptoOrderMapper.cancelOpenOrdersByUserId(userId);
        cryptoPositionMapper.deleteByUserId(userId);
        cleanupFuturesRedisIndexes(userId);
        futuresOrderMapper.cancelOpenOrdersByUserId(userId);
        futuresPositionMapper.closeOpenByUserId(userId, "CLOSED");
        settlementMapper.deletePendingByUserId(userId);

        log.info("用户恢复初始资金 userId={} balance={}", userId, initialBalance);
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

    @Override
    public LocalDate nextTradingDay(LocalDate d) {
        if (d == null) {
            return null;
        }
        LocalDate next = d.plusDays(1);
        DayOfWeek dow = next.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) {
            next = next.plusDays(2);
        } else if (dow == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }
}
