package com.mawai.wiibcommon.dto;

import lombok.Data;

/** 持仓调杠杆请求 */
@Data
public class FuturesAdjustLeverageRequest {

    private Long positionId;

    private Integer leverage;
}
