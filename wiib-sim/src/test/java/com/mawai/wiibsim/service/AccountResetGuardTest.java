package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重置防护回归：策略账户（quant-FIBO 这类是 user 表里的真实行，四策略各一个）
 * 永不可重置，否则一次误操作就把策略的实盘账本清了；用户名必须逐字匹配，防误点。
 */
class AccountResetGuardTest {

    @Test
    void rejectsStrategyAccount() {
        assertThrows(BizException.class,
                () -> AccountResetService.assertResettable("quant-FIBO", "quant-FIBO"));
        assertThrows(BizException.class,
                () -> AccountResetService.assertResettable("quant-TURTLE", "quant-TURTLE"));
    }

    @Test
    void rejectsUsernameMismatch() {
        assertThrows(BizException.class,
                () -> AccountResetService.assertResettable("alice", "alic"));
        assertThrows(BizException.class,
                () -> AccountResetService.assertResettable("alice", ""));
        assertThrows(BizException.class,
                () -> AccountResetService.assertResettable("alice", null));
    }

    @Test
    void acceptsExactMatch() {
        assertDoesNotThrow(() -> AccountResetService.assertResettable("alice", "alice"));
    }
}
