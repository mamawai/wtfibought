package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 排行榜上 AI Trader 只留当前局的子账户 */
class RankingTraderRoundTest {

    private static User user(String username, String linuxDoId) {
        User u = new User();
        u.setUsername(username);
        u.setLinuxDoId(linuxDoId);
        return u;
    }

    private static User subAccount(String username) {
        return user(username, "internal:" + username);
    }

    /** 同一个 trader 留局号最大的，不同 trader 各留各的；真人不动，名字像子账户的真人也不动 */
    @Test
    void keepsLatestRoundPerTrader() {
        User alice = user("alice", "10086");
        User lookalike = user("ai_trader_t3_r1", "10087");
        User r2 = subAccount("ai_trader_t3_r2");
        User r10 = subAccount("ai_trader_t3_r10");
        User other = subAccount("ai_trader_t4_r1");

        assertThat(RankingService.currentRoundOnly(List.of(alice, lookalike, r2, r10, other)))
                .containsExactly(alice, lookalike, r10, other);
    }
}
