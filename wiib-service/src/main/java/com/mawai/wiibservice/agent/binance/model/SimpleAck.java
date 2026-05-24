package com.mawai.wiibservice.agent.binance.model;

import lombok.Data;

/**
 * Binance 简单确认响应：{"code":200,"msg":"success"}。
 * 用于变更类接口（marginType / positionSide / allOpenOrders 撤销）。
 * 注意：成功时 code=200，但失败时 HTTP 4xx + 业务 code 已被统一转成异常抛出，
 *      所以拿到这个对象就说明已经成功，code/msg 字段主要用于日志记录。
 */
@Data
public class SimpleAck {
    private Integer code;
    private String msg;
}
