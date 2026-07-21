package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.Notification;
import com.mawai.wiibsim.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 交易事件通知：强平 / 止损 / 止盈 / 全仓爆仓。
 * <p>
 * 都是系统触发的，没有 actor 也不指向评论，用户点了不跳转——所以信息必须在通知里写全。
 * <p>
 * 调用点一律放在幂等 CAS（casClosePosition / atomicPartialClose）判定成功之后：
 * 补偿扫描、WS 重连补漏、启动补触发都可能重复调用平仓流程，CAS 挡住了重复执行，
 * 通知跟在它后面就天然不会重复发，不需要另做去重。
 */
@Service
@RequiredArgsConstructor
public class TradeNotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationPushService pushService;

    /** 逐仓保证金不足被强平：整个仓位一次没了 */
    public void liquidation(FuturesPosition position, BigDecimal price, BigDecimal pnl) {
        insert(position.getUserId(), Notification.TYPE_LIQUIDATION,
                position.getSymbol(), position.getSide(), position.getQuantity(), price, pnl);
    }

    /**
     * 止损/止盈触发。
     *
     * @param closedQty 这一次平掉的量。保护单支持部分平仓，传 position.getQuantity()
     *                  会把"平了 0.2"写成"平了 0.5"
     */
    public void protectiveClose(FuturesPosition position, BigDecimal closedQty, BigDecimal price,
                                BigDecimal pnl, boolean isStopLoss) {
        insert(position.getUserId(),
                isStopLoss ? Notification.TYPE_STOP_LOSS : Notification.TYPE_TAKE_PROFIT,
                position.getSymbol(), position.getSide(), closedQty, price, pnl);
    }

    /**
     * 全仓爆仓：一次爆掉该用户全部全仓仓位，合并成一条通知（每仓一条会把信封刷满）。
     * <p>
     * 跨多个币种，symbol/side/price 都没有单一取值故留空；
     * closedCount 借 quantity 落库——在 type=6 语境下"数量"就是"爆掉的仓位数"。
     */
    public void crossLiquidation(long userId, int closedCount, BigDecimal settle) {
        insert(userId, Notification.TYPE_CROSS_LIQUIDATION,
                null, null, BigDecimal.valueOf(closedCount), null, settle);
    }

    private void insert(long userId, int type, String symbol, String side,
                        BigDecimal quantity, BigDecimal price, BigDecimal pnl) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setSymbol(symbol);
        n.setSide(side);
        n.setQuantity(quantity);
        n.setPrice(price);
        n.setPnl(pnl);
        n.setIsRead(false);
        notificationMapper.insert(n);
        // 落库归落库，推送等事务提交后再发：回滚了还推的话对方角标加了、点开却是空的
        pushService.pushUnreadAfterCommit(userId);
    }
}
