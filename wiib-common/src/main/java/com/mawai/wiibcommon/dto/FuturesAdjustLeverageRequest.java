package com.mawai.wiibcommon.dto;

import lombok.Data;

/** 调杠杆请求：币种级操作（对齐Binance），多空共用杠杆，一次调整作用于该币全部仓位 */
@Data
public class FuturesAdjustLeverageRequest {

    private String symbol;

    private Integer leverage;
}
