package com.mawai.wiibcommon.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class FuturesStopLoss {
    private String id;
    private BigDecimal price;
    private BigDecimal quantity;
    /** 档位创建时间（毫秒），空窗补漏按它过滤；旧数据为 null，退回仓位创建时间 */
    private Long createdAt;

    public FuturesStopLoss(String id, BigDecimal price, BigDecimal quantity) {
        this.id = id;
        this.price = price;
        this.quantity = quantity;
    }
}
