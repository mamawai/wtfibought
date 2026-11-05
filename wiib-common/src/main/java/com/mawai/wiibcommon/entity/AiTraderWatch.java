package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型给自己布的盯盘条件，一条一行：到了就把它叫醒。
 * 类型：TOUCH_ABOVE / TOUCH_BELOW 标记价触及价位，CLOSE_ABOVE / CLOSE_BELOW 某周期收盘站上 / 跌破价位，AT_TIME 到某时刻。
 * set_watch 一次整组替换一个币的条件：旧的标 REPLACED，新的 ARMED；命中标 FIRED 并记下时刻与价。
 * 只有 ARMED 的会被盯；FIRED / REPLACED 留着给时间线和复盘看。
 */
@Data
@TableName("ai_trader_watch")
public class AiTraderWatch {

    public static final String STATUS_ARMED = "ARMED";
    public static final String STATUS_FIRED = "FIRED";
    public static final String STATUS_REPLACED = "REPLACED";

    public static final String TOUCH_ABOVE = "TOUCH_ABOVE";
    public static final String TOUCH_BELOW = "TOUCH_BELOW";
    public static final String CLOSE_ABOVE = "CLOSE_ABOVE";
    public static final String CLOSE_BELOW = "CLOSE_BELOW";
    public static final String AT_TIME = "AT_TIME";
    public static final List<String> KINDS = List.of(TOUCH_ABOVE, TOUCH_BELOW, CLOSE_ABOVE, CLOSE_BELOW, AT_TIME);

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    private String symbol;

    private String kind;

    /** 价位；AT_TIME 为空 */
    private BigDecimal level;

    /** CLOSE_* 的周期 5m/15m/1h/4h/1d；别的为空 */
    private String period;

    /** AT_TIME 的时刻 ms；别的为空 */
    private Long atTime;

    /** 模型给下一轮自己的备注：这条命中意味着什么 */
    private String note;

    /** ARMED / FIRED / REPLACED */
    private String status;

    private Long firedAt;

    private BigDecimal firedPrice;

    /** 模型得知这次命中的那轮唤醒时刻 ms（当作触发叫醒它，或在开场白里补报给它）；还没哪轮告诉过它则为空 */
    private Long wokeAt;

    /** 布下它的那轮唤醒时刻 ms */
    private Long createdWakeTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
