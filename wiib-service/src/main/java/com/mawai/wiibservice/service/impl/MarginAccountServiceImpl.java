package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.MarginAccountService;
import com.mawai.wiibservice.service.model.MarginRepayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarginAccountServiceImpl implements MarginAccountService {

    private final UserMapper userMapper;
    private final TradingConfig tradingConfig;

    @Override
    public int normalizeLeverageMultiple(Integer leverageMultiple) {
        if (leverageMultiple == null || leverageMultiple <= 1) {
            return 1;
        }
        return leverageMultiple;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addLoanPrincipal(Long userId, BigDecimal principalDelta) {
        if (principalDelta == null || principalDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        int affected = userMapper.atomicAddMarginLoanPrincipal(userId, principalDelta);
        if (affected == 0) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
        userMapper.ensureMarginInterestLastDate(userId, LocalDate.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarginRepayResult applyCashInflow(Long userId, BigDecimal amount, String reason) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return new MarginRepayResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        BigDecimal interest = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;
        BigDecimal principal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;

        BigDecimal remaining = amount;

        BigDecimal paidInterest = remaining.min(interest);
        remaining = remaining.subtract(paidInterest);

        BigDecimal paidPrincipal = remaining.min(principal);
        remaining = remaining.subtract(paidPrincipal);

        BigDecimal creditedToBalance = remaining;

        int affected = userMapper.atomicApplyCashInflow(userId, paidInterest, paidPrincipal, creditedToBalance);
        if (affected == 0) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }

        log.info("现金流入自动还款 userId={} amount={} paidInterest={} paidPrincipal={} creditBalance={} reason={}",
                userId, amount, paidInterest, paidPrincipal, creditedToBalance, reason);

        return new MarginRepayResult(paidInterest, paidPrincipal, creditedToBalance);
    }

    @Override
    public void accrueDailyInterest(LocalDate today) {
        if (today == null) {
            return;
        }
        if (!tradingConfig.getMargin().isEnabled()) {
            return;
        }

        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, false)
                .gt(User::getMarginLoanPrincipal, BigDecimal.ZERO));
        if (users.isEmpty()) {
            return;
        }

        for (User user : users) {
            try {
                SpringUtils.getAopProxy(this).accrueUserInterest(user.getId(), today);
            } catch (Exception e) {
                log.error("计息失败 userId={}", user.getId(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void accrueUserInterest(Long userId, LocalDate today) {
        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            return;
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            return;
        }

        BigDecimal principal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        if (principal.compareTo(BigDecimal.ZERO) <= 0) {
            userMapper.ensureMarginInterestLastDate(userId, today);
            return;
        }

        LocalDate last = user.getMarginInterestLastDate();
        if (last == null) {
            userMapper.ensureMarginInterestLastDate(userId, today);
            return;
        }

        long days = ChronoUnit.DAYS.between(last, today);
        if (days < 0) {
            return;
        }
        if (days == 0) {
            days = 1;
        }

        BigDecimal dailyRate = tradingConfig.getMargin().getDailyInterestRate();
        BigDecimal interestDelta = principal
                .multiply(dailyRate)
                .multiply(BigDecimal.valueOf(days))
                .setScale(2, RoundingMode.HALF_UP);

        if (interestDelta.compareTo(BigDecimal.ZERO) <= 0) {
            userMapper.ensureMarginInterestLastDate(userId, today);
            return;
        }

        int affected = userMapper.atomicAccrueInterest(userId, interestDelta, today);
        if (affected == 0) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }

        log.info("杠杆计息 userId={} principal={} days={} rate={} interestDelta={}",
                userId, principal, days, dailyRate, interestDelta);
    }
}
