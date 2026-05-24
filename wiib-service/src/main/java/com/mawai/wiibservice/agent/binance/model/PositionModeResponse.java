package com.mawai.wiibservice.agent.binance.model;

import lombok.Data;

/**
 * GET /fapi/v1/positionSide/dual 响应：{"dualSidePosition": true|false}。
 *   true  = 双向持仓模式（同时持多空）
 *   false = 单向持仓模式（多空对冲，positionSide 必须 BOTH）
 */
@Data
public class PositionModeResponse {
    private Boolean dualSidePosition;
}
