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

    IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    List<FuturesOrderResponse> getLatestOrders();
}
