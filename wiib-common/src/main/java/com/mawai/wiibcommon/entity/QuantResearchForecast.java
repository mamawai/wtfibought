package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * agent 三腿(vol/regime/direction)原始预测落库，每 cycle × horizon 一行。
 * 与 direction-centric 的 {@code quant_horizon_forecast} 并存：这里如实保留三腿契约，供事后对账。
 */
@Data
@TableName("quant_research_forecast")
public class QuantResearchForecast {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String horizon;                 // H6/H12/H24

    // ---- vol 腿 ----
    private Integer expectedMoveBps;        // 预期波动 bps
    private String volTier;                 // 波动状态档(VolatilityRiskTier)
    private BigDecimal trailingPercentile;  // vol 历史分位
    private BigDecimal riskBudgetHint;      // 风险预算 0-1

    // ---- regime 腿 ----
    private String regime;
    private BigDecimal regimeConfidence;

    // ---- direction 腿 ----
    private Integer directionSign;          // -1/0/1
    private BigDecimal directionConfidence;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
