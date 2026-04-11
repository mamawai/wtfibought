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

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
