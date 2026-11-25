package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 经验名下的一笔单子：复盘模型给仓位编号，钱和平仓日由代码按仓位记。同一条经验同一笔单子只记一次。
 */
@Data
@TableName("ai_trader_experience_trade")
public class AiTraderExperienceTrade {

    /** 名下：假设、规律碰到它说的情况的单子（机会型、执行型是照做了，限制型是还是做了） */
    public static final String MARK_UNDER = "UNDER";
    /** 执行缺口：碰到了，照做了 */
    public static final String MARK_KEPT = "KEPT";
    /** 执行缺口：碰到了，没照做 */
    public static final String MARK_BROKEN = "BROKEN";
    /** 开仓引用了它，情况却对不上：只记次数不记钱 */
    public static final String MARK_MISCITED = "MISCITED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long experienceId;

    private Long positionId;

    /** 记下它的那篇复盘（ai_trader_decision 的 REVIEW 行） */
    private Long reviewId;

    /** UNDER / KEPT / BROKEN / MISCITED */
    private String mark;

    /** 这笔的净盈亏，扣了手续费和资金费 */
    private BigDecimal net;

    /** 平仓的新加坡日期 */
    private LocalDate closedDay;

    /** 开这笔仓的那一轮实际跑的时段（ms），守、破按它分轮；认不出是哪一轮开的、老行为空 */
    private Long openRoundFrom;
    private Long openRoundTo;

    /** 记下时条目所在的段 */
    private Integer segmentNo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
