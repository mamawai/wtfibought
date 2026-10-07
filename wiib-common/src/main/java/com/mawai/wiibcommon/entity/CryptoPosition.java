package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("crypto_position")
public class CryptoPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String symbol;

    private BigDecimal quantity;

    private BigDecimal frozenQuantity;

    private BigDecimal avgCost;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public BigDecimal getTotalQuantity() {
        BigDecimal frozen = frozenQuantity != null ? frozenQuantity : BigDecimal.ZERO;
        return quantity.add(frozen);
    }
}
