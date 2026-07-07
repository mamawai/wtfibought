package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 深研判产物（P2b）：Bull∥Bear→Judge 辩论子图输出。
 * 研判是叙事产物非交易信号：情景分布 + 失效条件 + 无方向态，不产任何可下单的方向数字。
 */
@Data
@TableName(value = "quant_deep_analysis", autoResultMap = true)
public class QuantDeepAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private Long closeTime;

    /** 触发源：cron_1h / sentinel / manual / chat（P4 对话触发） */
    private String triggerSource;

    /** 锚定的数值快照 id（研判基于该时点的数据） */
    private Long snapshotId;

    /** 研判叙事（人话后果） */
    private String narrative;

    /** 情景分布：{"bullPct":40,"rangePct":35,"bearPct":25}，和为 100 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String scenariosJson;

    /** 无方向态：信号矛盾时大方承认看不清 */
    private Boolean noDirection;

    /** 反事实失效条件（可证伪） */
    private String invalidation;

    private String bullArgument;

    private String bearArgument;

    private String judgeReasoning;

    /** 新闻上下文（LLM 浓缩，进辩论 prompt 的素材） */
    private String newsContext;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
