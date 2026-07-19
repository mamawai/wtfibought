package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 钱包划转流水（余额钱包 ↔ 游戏钱包，审计用） */
@Data
@TableName("wallet_transfer")
public class WalletTransfer {

    public static final String TO_GAME = "TO_GAME";
    public static final String TO_BALANCE = "TO_BALANCE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 方向：TO_GAME=余额→游戏 TO_BALANCE=游戏→余额 */
    private String direction;

    /** 划转金额（恒为正，从转出方全额扣除） */
    private BigDecimal amount;

    /** 手续费（1%，到账 = amount − fee） */
    private BigDecimal fee;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
