package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户资金流水账本。一条 SQL 影响几个钱包字段就有几条本记录 */
@Data
@TableName("user_ledger")
public class UserLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LedgerWallet wallet;

    private LedgerBizType bizType;

    /** 变动额，有符号，正入负出 */
    private BigDecimal delta;

    /** 该钱包变动后余额，取自同条 UPDATE 的 RETURNING */
    private BigDecimal balanceAfter;

    /** delta 中含的手续费；仅费与本金同条 SQL 时填。不参与求和校验 */
    private BigDecimal fee;

    /** 关联对象类型：FUTURES_ORDER/CRYPTO_ORDER/POSITION/PREDICTION_BET */
    private String refType;

    private Long refId;

    private String symbol;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 账单页直接显示的中文，取自 {@link LedgerBizType#getLabel()}。
     * 平铺成兄弟字段，这么写为了枚举名和 label 同时给前端（筛选参数吃枚举名）。
     * 无对应列，MyBatis-Plus 不会凭空多出一列（同 {@link User#getTotalBalance()}）。
     */
    public String getBizTypeLabel() {
        return bizType == null ? null : bizType.getLabel();
    }
}
