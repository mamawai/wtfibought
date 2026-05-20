package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesReduceMarginRequest {
    private Long positionId;
    private BigDecimal amount;
}
