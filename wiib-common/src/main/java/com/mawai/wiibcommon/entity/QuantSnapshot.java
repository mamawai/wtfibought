package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数值快照（每 5m 一行，零 LLM）：vol 三腿预测 + regime + 脆弱度 + 信号面板。
 * 是记分卡（P3）的预测点来源；vol_legs_json 内含 PIT 档界，验证侧禁止重算档界。
 */
@Data
@TableName(value = "quant_snapshot", autoResultMap = true)
public class QuantSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    /** 对齐的 5m bar closeTime（毫秒），与 symbol 组成幂等唯一键 */
    private Long closeTime;

    private BigDecimal lastPrice;

    /** 三腿：{"H6":{"sigmaBps","percentile","tier","volState","lowCut","highCut","regime","regimeConfidence"},...} */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String volLegsJson;

    /** H6 腿 regime 冗余列，便于 SQL 直查 */
    private String regime;

    private Double regimeConfidence;

    private Integer fragilityScore;

    private String fragilityLevel;

    private String fragilityDirection;

    private String fragilityHeadline;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String signalPanelJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String qualityFlagsJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
