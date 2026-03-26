package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("blackjack_convert_log")
public class BlackjackConvertLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long amount;

    private Long chipsBefore;

    private Long chipsAfter;

    private BigDecimal balanceBefore;

    private BigDecimal balanceAfter;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
