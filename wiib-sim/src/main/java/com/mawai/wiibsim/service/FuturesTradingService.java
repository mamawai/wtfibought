package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;

import java.util.List;

public interface FuturesTradingService {

    FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request);

    FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request);

    FuturesOrderResponse cancelOrder(Long userId, Long orderId);

    void addMargin(Long userId, FuturesAddMarginRequest request);

    void reduceMargin(Long userId, FuturesReduceMarginRequest request);

    FuturesOrderResponse increasePosition(Long userId, FuturesIncreaseRequest request);

    List<FuturesPositionDTO> getUserPositions(Long userId, String symbol);

    /** 已平/强平仓位历史（internal 策略账户监控用）：静态字段快照，不算实时浮盈。 */
    List<FuturesPositionDTO> getClosedPositions(Long userId, String symbol, int limit);

    /** 单笔订单查询（internal 执行轮询用）；订单不存在或不属于该用户抛 ORDER_NOT_FOUND。 */
    FuturesOrderResponse getOrder(Long userId, Long orderId);

    /** 该用户 PENDING 挂单列表（internal 重启对账用）；symbol 为空查全部。 */
    List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol);

    IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    List<FuturesOrderResponse> getLatestOrders();
}
