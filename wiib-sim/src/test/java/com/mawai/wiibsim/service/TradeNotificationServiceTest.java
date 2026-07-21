package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.Notification;
import com.mawai.wiibsim.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** 交易通知的字段填充。重点是各 type 只填自己那几列，以及全仓爆仓的 quantity 语义 */
class TradeNotificationServiceTest {

    private NotificationMapper mapper;
    private NotificationPushService push;
    private TradeNotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        push = mock(NotificationPushService.class);
        service = new TradeNotificationService(mapper, push);
    }

    private static FuturesPosition position() {
        FuturesPosition p = new FuturesPosition();
        p.setId(9L);
        p.setUserId(7L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.5"));
        return p;
    }

    private Notification captureInserted() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + " 实际 " + actual);
    }

    @Test
    void liquidationCarriesPositionDetail() {
        service.liquidation(position(), new BigDecimal("95240"), new BigDecimal("-1234.56"));

        Notification n = captureInserted();
        assertEquals(7L, n.getUserId());
        assertEquals(Notification.TYPE_LIQUIDATION, n.getType());
        assertEquals("BTCUSDT", n.getSymbol());
        assertEquals("LONG", n.getSide());
        assertNum("0.5", n.getQuantity());
        assertNum("95240", n.getPrice());
        assertNum("-1234.56", n.getPnl());
        assertFalse(n.getIsRead());
        // 系统触发，不该有 actor，也不指向任何评论
        assertNull(n.getActorId());
        assertNull(n.getCommentId());
        verify(push).pushUnreadAfterCommit(7L);
    }

    @Test
    void stopLossAndTakeProfitUseDistinctTypes() {
        service.protectiveClose(position(), new BigDecimal("0.2"), new BigDecimal("94000"), new BigDecimal("-100"), true);
        assertEquals(Notification.TYPE_STOP_LOSS, captureInserted().getType());

        reset(mapper);
        service.protectiveClose(position(), new BigDecimal("0.2"), new BigDecimal("96000"), new BigDecimal("100"), false);
        assertEquals(Notification.TYPE_TAKE_PROFIT, captureInserted().getType());
    }

    @Test
    void protectiveCloseRecordsClosedQtyNotWholePosition() {
        // 部分平仓：通知里要写这次平掉的 0.2，不能写成仓位总量 0.5
        service.protectiveClose(position(), new BigDecimal("0.2"), new BigDecimal("94000"), new BigDecimal("-100"), true);
        assertNum("0.2", captureInserted().getQuantity());
    }

    @Test
    void crossLiquidationMergesIntoOneWithCountInQuantity() {
        service.crossLiquidation(7L, 3, new BigDecimal("-5678.90"));

        Notification n = captureInserted();
        assertEquals(Notification.TYPE_CROSS_LIQUIDATION, n.getType());
        assertNum("3", n.getQuantity());        // quantity 在 type=6 下复用为"爆掉的仓位数"
        assertNum("-5678.90", n.getPnl());
        // 跨多个币种，这三列没有单一取值
        assertNull(n.getSymbol());
        assertNull(n.getSide());
        assertNull(n.getPrice());
        verify(push).pushUnreadAfterCommit(7L);
    }
}
