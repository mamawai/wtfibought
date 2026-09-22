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
    private String status;
    private int remainingSeconds;
    /** 后端生成响应的时间，用于前端校正本机时钟 */
    private Long serverTimeMs;
    /** Gamma响应头Date校正后的Polymarket当前时间 */
    private Long officialNowTimeMs;
    /** 官方回合开始时间：slug 里的时间戳 */
    private Long officialStartTimeMs;
    /** 官方回合结束时间：开始 + 5 分钟 */
    private Long officialEndTimeMs;
}
