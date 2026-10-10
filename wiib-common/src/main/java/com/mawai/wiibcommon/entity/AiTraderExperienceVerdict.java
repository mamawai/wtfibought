package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次复盘对一条经验的结论：支持 / 反对 / 未测试，附一句证据。复审的对照表和历史最长连败从这里算。
 */
@Data
@TableName("ai_trader_experience_verdict")
public class AiTraderExperienceVerdict {

    public static final String SUPPORT = "SUPPORT";
    public static final String OPPOSE = "OPPOSE";
    public static final String UNTESTED = "UNTESTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long experienceId;

    /** 给出这条结论的 ai_trader_decision REVIEW 行 */
    private Long reviewId;

    /** SUPPORT / OPPOSE / UNTESTED */
    private String verdict;

    /** 涉及的 sim 仓位 id，逗号分隔 */
    private String positionIds;

    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
