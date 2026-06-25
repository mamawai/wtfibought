package com.mawai.wiibquant.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * POST /fapi/v1/leverage 调整杠杆响应。
 */
@Data
public class SetLeverageResponse {

    private String symbol;
    /** 调整后的杠杆倍数 */
    private Integer leverage;
    /** 当前杠杆下允许的最大名义价值 */
    private BigDecimal maxNotionalValue;
}
