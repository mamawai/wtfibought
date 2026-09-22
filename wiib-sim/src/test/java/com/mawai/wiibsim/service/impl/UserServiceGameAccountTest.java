package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CryptoPositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 游戏机器人账户：交易钱包 0、初始资金进游戏钱包、建号赠送记在 GAME 钱包；已存在不重复入金 */
class UserServiceGameAccountTest {

    private UserMapper userMapper;
    private UserLedgerMapper ledgerMapper;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        ledgerMapper = mock(UserLedgerMapper.class);
        service = new UserServiceImpl(mock(CryptoPositionService.class), mock(FuturesPositionMapper.class),
                mock(AssetValuationService.class), ledgerMapper);
        // ServiceImpl 的 baseMapper 由容器注入，单测手动塞
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @Test
    void 新建时交易钱包为零初始资金进游戏钱包并补记GAME流水() {
        when(userMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            inv.getArgument(0, User.class).setId(42L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        User user = service.ensureGameAccount("jev-prediction", new BigDecimal("100"));

        assertThat(user.getId()).isEqualTo(42L);
        assertThat(user.getLinuxDoId()).isEqualTo("internal:jev-prediction");
        assertThat(user.getBalance()).isEqualByComparingTo("0");
        assertThat(user.getGameBalance()).isEqualByComparingTo("100");

        ArgumentCaptor<UserLedger> captor = ArgumentCaptor.forClass(UserLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        UserLedger entry = captor.getValue();
        assertThat(entry.getUserId()).isEqualTo(42L);
        assertThat(entry.getWallet()).isEqualTo(LedgerWallet.GAME);
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.INITIAL_GRANT);
        assertThat(entry.getDelta()).isEqualByComparingTo("100");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("100");
    }

    @Test
    void 已存在直接返回不重复入金() {
        User existing = new User();
        existing.setId(7L);
        existing.setGameBalance(new BigDecimal("63.5"));
        when(userMapper.selectOne(any())).thenReturn(existing);

        User user = service.ensureGameAccount("jev-prediction", new BigDecimal("100"));

        assertThat(user).isSameAs(existing);
        verify(userMapper, never()).insert(any(User.class));
        verify(ledgerMapper, never()).insert(any(UserLedger.class));
    }
}
