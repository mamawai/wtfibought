package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderProtectionRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;

import java.util.List;

/** 合约开仓、平仓、撤单。改保证金和杠杆见 FuturesMarginService，查询见 FuturesQueryService */
public interface FuturesTradingService {

    FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request);

    FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request);

    /** 一键全平：市价平掉该用户全部 OPEN 仓位，逐仓循环，单仓失败不阻断其余。 */
    CloseAllResult closeAllPositions(Long userId);

    /** 一键全平结果：成功笔数 + 失败明细（"symbol#posId: 原因"）。 */
    record CloseAllResult(int closedCount, List<String> failures) {}

    /** 反手：市价全平该仓位，紧接着反向开等量新仓（同杠杆、同保证金模式）。原仓的止损止盈不带过来。 */
    ReverseResult reversePosition(Long userId, Long positionId);

    /**
     * 反手结果：closed 是平掉那笔（带 realizedPnl / 数量 / CLOSE_LONG|CLOSE_SHORT），
     * opened 是反向开的那笔。反向开仓失败时 opened=null、openError 带原因——此时用户是空仓状态，
     * 前端必须把这个半成功说清楚。
     */
    record ReverseResult(FuturesOrderResponse closed, FuturesOrderResponse opened, String openError) {}

    FuturesOrderResponse cancelOrder(Long userId, Long orderId);

    void setOrderProtection(Long userId, Long orderId, FuturesOrderProtectionRequest request);
}
