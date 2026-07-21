package com.mawai.wiibcommon.dto;

import lombok.Data;

@Data
public class ConvertResultDTO {

    /** 转出后剩余的 Blackjack 积分。 */
    private long chips;

    /** 转出到账后的用户资产余额（主账户）。 */
    private double balance;

    /** 转出完成后当日累计已转出积分。 */
    private long todayConverted;

    /** 转出完成后仍可转出的积分（= 剩余积分 - 保底值），前端不再自己算。 */
    private long convertable;
}
