package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.mawai.wiibcommon.enums.LedgerBizType.FUTURES_LIMIT_UNFREEZE;

/**
 * 仓位全平之后的收尾：挂在这个仓位身上的东西一起清掉。
 * 手动平仓、限价平仓成交、止损止盈平完、逐仓强平、全仓强平都走这里，在平仓的事务里、仓位状态改成功之后调。
 */
@Component
@RequiredArgsConstructor
public class ClosedPositionCleanup {

    private final FuturesPositionIndexService positionIndexService;
    private final FuturesOrderMapper orderMapper;
    private final UserMapper userMapper;
    private final LimitOrderIndex limitIndex;

    public void run(FuturesPosition position) {
        positionIndexService.unregisterAll(position);
        cancelCloseOrders(position);
        cancelGroupedOpenOrders(position);
    }

    /**
     * 它名下还挂着的平仓限价单撤掉、摘索引（平仓单不冻结钱，不用退）。
     * 摘索引在事务提交之前，事务回滚了单子退回 PENDING 而索引已经没了，由每小时的 reconcileLimitOrderIndex 补回来
     */
    private void cancelCloseOrders(FuturesPosition position) {
        for (FuturesOrder order : orderMapper.cancelPendingCloseOrders(position.getId())) {
            limitIndex.remove(order);
        }
    }

    /**
     * 这个币这个方向带组标识、还没成交的开仓单（挂着的和已触发的）一起撤掉。
     * 逐仓单退冻结，全仓单只改状态。限价索引在事务提交后摘
     */
    private void cancelGroupedOpenOrders(FuturesPosition position) {
        List<FuturesOrder> cancelled = orderMapper.cancelGroupedOpenOrders(
                position.getUserId(), position.getSymbol(), "OPEN_" + position.getSide());
        for (FuturesOrder order : cancelled) {
            if (!FuturesPosition.CROSS.equals(order.getMarginMode())) {
                LedgerCtx.mark(FUTURES_LIMIT_UNFREEZE, "FUTURES_ORDER", order.getId());
                userMapper.atomicUnfreezeBalance(order.getUserId(), order.getFrozenAmount());
            }
        }
        if (!cancelled.isEmpty()) {
            AfterCommit.run(() -> cancelled.forEach(order -> limitIndex.remove(order)));
        }
    }
}
