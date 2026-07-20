package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 排行榜双钱包口径回归。锁死两点：
 * 1) 余额钱包必须含冻结——建表注释写明"冻结余额属余额钱包"，漏了会让挂着限价单的用户看起来凭空少一截钱
 * 2) 字段为 null 时按 0 处理——老用户可能有 null 列，直接 add 会 NPE 炸掉整次榜单刷新
 */
class RankingWalletSplitTest {

    private static User user(String balance, String frozen, String game) {
        User u = new User();
        u.setBalance(new BigDecimal(balance));
        u.setFrozenBalance(new BigDecimal(frozen));
        u.setGameBalance(new BigDecimal(game));
        return u;
    }

    @Test
    void balanceWalletIncludesFrozen() {
        User u = user("8000", "1500", "500");

        assertEquals(0, new BigDecimal("9500.00").compareTo(RankingService.balanceWalletOf(u)));
        assertEquals(0, new BigDecimal("500.00").compareTo(RankingService.gameWalletOf(u)));
    }

    @Test
    void nullFieldsTreatedAsZero() {
        User u = new User();

        assertEquals(0, BigDecimal.ZERO.compareTo(RankingService.balanceWalletOf(u)));
        assertEquals(0, BigDecimal.ZERO.compareTo(RankingService.gameWalletOf(u)));
    }
}
