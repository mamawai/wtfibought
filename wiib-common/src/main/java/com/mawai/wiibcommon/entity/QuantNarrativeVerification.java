package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 叙事对账行：一条深研判（Judge 三情景概率）到期后的对账结果。
 * 实际情景的判定界是研判时点前 90 天 |H12 收益| 的下三分位，对账时从 K 线现算，只用 closeTime 前的数据；
 * 评分是三分类 Brier（均匀瞎猜基线 2/3，越低越好）+ 最高概率情景命中。
 */
@Data
@TableName("quant_narrative_verification")
public class QuantNarrativeVerification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long analysisId;

    private String symbol;

    /** 研判锚点（analysis.closeTime；chat 轨为墙钟，对账时地板对齐到 bar 网格） */
    private Long closeTime;

    /** 对账窗，当前固定 H12（叙事写"未来6-24h"，取中值） */
    private String horizon;

    private Integer bullPct;

    private Integer rangePct;

    private Integer bearPct;

    /** Judge 当时是否自认看不清（此类行单独归组统计） */
    private Boolean noDirection;

    /** RANGE/方向 的判定界（bps）：研判时点前 90 天 |H12 收益| 的下三分位 */
    private Integer rangeCutBps;

    /** 到期实际对数收益（bps，带符号） */
    private Integer realizedReturnBps;

    /** BULL / RANGE / BEAR */
    private String actualScenario;

    /** 最高概率情景（并列最大偏 RANGE，不硬挤方向） */
    private String predictedScenario;

    private Boolean scenarioHit;

    private Double brier;

    /** VERIFIED / SKIPPED（不可对账：缺档界、情景损坏或K线缺口超宽限） */
    private String status;

    private LocalDateTime verifiedAt;
}
