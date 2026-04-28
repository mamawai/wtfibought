package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PredictionRoundResponse {

    private Long id;
    private Long windowStart;
    private BigDecimal startPrice;
    private BigDecimal endPrice;
    private String outcome;
    /** Polymarket 实时 UP 价格 */
    private BigDecimal upPrice;
    /** Polymarket 实时 DOWN 价格 */
    private BigDecimal downPrice;
    private String status;
    private int remainingSeconds;
    /** 后端生成响应的时间，用于前端校正本机时钟 */
    private Long serverTimeMs;
    /** Gamma响应头Date校正后的Polymarket当前时间 */
    private Long officialNowTimeMs;
    /** Polymarket Gamma返回的官方回合开始时间 */
    private Long officialStartTimeMs;
    /** Polymarket Gamma返回的官方回合结束时间 */
    private Long officialEndTimeMs;
}
