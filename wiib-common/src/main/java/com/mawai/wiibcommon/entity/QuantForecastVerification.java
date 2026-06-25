package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quant_forecast_verification")
public class QuantForecastVerification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String symbol;

    private String horizon;

    private String predictedDirection;

    private BigDecimal predictedConfidence;

    private BigDecimal actualPriceAtForecast;

    private BigDecimal actualPriceAfter;

    private Integer actualChangeBps;

    // 路径验证：最大有利/不利偏移，判断TP/SL是否先触达
    private Integer maxFavorableBps;
    private Integer maxAdverseBps;
    private Boolean tp1HitFirst;

    private Boolean predictionCorrect;

    // 综合评级：GOOD=方向+路径都对, LUCKY=终点对但中间触止损, BAD=方向错, FLAT=波动不足
    private String tradeQuality;

    private String resultSummary;

    private BigDecimal reversalSeverity;

    // ---- 纯方向口径：只看方向猜对没，不掺路径/盈亏 ----
    private Boolean directionHit;           // 预测涨跌 == 实际涨跌

    // ---- vol-state 对账：3档 tercile，与给LLM的5档 riskTier 是两套尺子，各司其职 ----
    private String predictedVolState;       // 预测波动档 LOW/MID/HIGH
    private String actualVolState;          // 实际波动档
    private Boolean volStateHit;            // 档命中；null=历史不足，不判
    private Integer actualAbsMoveBps;       // 实际 |horizon对数收益| × 10000

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
