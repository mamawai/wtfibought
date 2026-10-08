package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.model.MarginRepayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.mawai.wiibcommon.enums.LedgerBizType.MARGIN_INTEREST_ACCRUE;
import static com.mawai.wiibcommon.enums.LedgerBizType.MARGIN_LOAN;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarginAccountServiceImpl implements MarginAccountService {

    private final UserMapper userMapper;
    private final TradingConfig tradingConfig;
    private final LedgerTx ledgerTx;

    @Override
    public int normalizeLeverageMultiple(Integer leverageMultiple) {
        if (leverageMultiple == null || leverageMultiple <= 1) {
            return 1;
        }
        return leverageMultiple;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Ledger(MARGIN_LOAN)
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

        BigDecimal afterPrincipal = userMapper.atomicAddMarginLoanPrincipal(userId, principalDelta);
        if (afterPrincipal == null) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
        userMapper.ensureMarginInterestLastDate(userId, LocalDate.now());
    }

    // 刻意不标 @Ledger：这是"现金流入先还贷再入余额"的公共出口，语义由调用方给
    // （SPOT_SETTLE/BSTOCK_SETTLE），标在这里会盖掉调用方的方法级语义
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

        var r = userMapper.atomicApplyCashInflow(userId, paidInterest, paidPrincipal, creditedToBalance);
        if (r == null) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
        // 本金已还清：清掉计息起算点。留着的话下次借款时 COALESCE 会保留旧值，
        // 把还清后这段没欠钱的空档天数一并算成利息。
        //
        // 别"顺手优化"成 r.marginLoanPrincipal()：上面是 selectByIdForUpdate 持着行锁读的，
        // 锁内没人能改这行，"读到的本金 − 还掉的本金"与 RETURNING 回来的本金恒等，换了不多对一分。
        // 但 MarginInterestAnchorTest 的 mock 是参数无关的固定返回值（本金恒为 0），换成 r 会让
        // 4 条用例全走进"已还清"分支、断言 never() 的那 2 条直接红；要救就得把 mock 改成
        // thenAnswer 按入参重算——那等于在 mock 里重新实现一遍这条 SQL，日后必跟真 SQL 漂移。
        if (principal.subtract(paidPrincipal).compareTo(BigDecimal.ZERO) <= 0) {
            userMapper.clearMarginInterestLastDate(userId);
        }

        log.info("现金流入自动还款 userId={} amount={} paidInterest={} paidPrincipal={} creditBalance={} 余息={} 余本={} 余额={} reason={}",
                userId, amount, paidInterest, paidPrincipal, creditedToBalance,
                r.marginInterestAccrued(), r.marginLoanPrincipal(), r.balance(), reason);

        return new MarginRepayResult(paidInterest, paidPrincipal, creditedToBalance);
    }

    @Override
    public void accrueDailyInterest(LocalDate today) {
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
                ledgerTx.run(MARGIN_INTEREST_ACCRUE, () -> accrueUserInterest(user.getId(), today));
            } catch (Exception e) {
                log.error("计息失败 userId={}", user.getId(), e);
            }
        }
    }

    // 计息是逐用户一个事务，一笔一账
    void accrueUserInterest(Long userId, LocalDate today) {
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

        BigDecimal afterInterest = userMapper.atomicAccrueInterest(userId, interestDelta, today);
        if (afterInterest == null) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }

        log.info("杠杆计息 userId={} principal={} days={} rate={} interestDelta={} 累计利息={}",
                userId, principal, days, dailyRate, interestDelta, afterInterest);
    }
}
