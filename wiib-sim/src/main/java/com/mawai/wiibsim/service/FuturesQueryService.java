package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;

import java.util.List;

/** 合约仓位和订单的查询，只读 */
public interface FuturesQueryService {

    /** 该用户 OPEN 仓位，带实时价、浮盈亏、强平价；symbol 为空查全部。 */
    List<FuturesPositionDTO> getUserPositions(Long userId, String symbol);

    /** 已平/强平仓位历史（internal：AI Trader 战绩与策略账户监控用）：静态字段快照，不算实时浮盈；closedPnl 是订单流水聚合出的净盈亏。 */
    List<FuturesPositionDTO> getClosedPositions(Long userId, String symbol, int limit);

    /** 单笔订单查询（internal 执行轮询用）；订单不存在或不属于该用户抛 ORDER_NOT_FOUND。 */
    FuturesOrderResponse getOrder(Long userId, Long orderId);

    /** 该用户 PENDING 挂单列表（internal 重启对账用）；symbol 为空查全部。 */
    List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol);

    /** 这个订单组的全部订单，什么状态都算 */
    List<FuturesOrderResponse> getGroupOrders(Long userId, String groupId);

    IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    /** 全站最近成交的 20 笔，不带用户 */
    List<FuturesOrderResponse> getLatestOrders();
}
