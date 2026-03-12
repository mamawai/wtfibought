package com.mawai.wiibcommon.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FuturesStopLoss {
    private String id;
    private BigDecimal price;
    private BigDecimal quantity;
}
