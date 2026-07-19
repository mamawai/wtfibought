package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 钱包划转请求 */
@Data
public class WalletTransferRequest {

    /** TO_GAME=余额→游戏 TO_BALANCE=游戏→余额 */
    private String direction;

    private BigDecimal amount;
}
