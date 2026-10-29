package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CryptoPositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 量化子账户建号：同名的是机器人号就复用，是真人账户就拒绝——不能把真人的号交给 AI Trader 下单 */
class UserServiceQuantAccountTest {

    private UserMapper userMapper;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new UserServiceImpl(mock(CryptoPositionService.class), mock(FuturesPositionMapper.class),
                mock(AssetValuationService.class), mock(UserLedgerMapper.class), mock(MessageCatalog.class));
        // ServiceImpl 的 baseMapper 由容器注入，单测手动塞
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
    }

    @Test
    void 同名机器人号直接复用() {
        User robot = new User();
        robot.setId(9L);
        robot.setLinuxDoId("internal:ai_trader_7_r2");
        when(userMapper.selectOne(any())).thenReturn(robot);

        assertThat(service.ensureQuantAccount("ai_trader_7_r2", new BigDecimal("10000"))).isSameAs(robot);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void 同名的是真人账户就拒绝() {
        User human = new User();
        human.setId(10L);
        human.setGoogleId("google-sub-1");
        when(userMapper.selectOne(any())).thenReturn(human);

        assertThrows(BizException.class, () -> service.ensureQuantAccount("ai_trader_7_r2", new BigDecimal("10000")));
        verify(userMapper, never()).insert(any(User.class));
    }
}
