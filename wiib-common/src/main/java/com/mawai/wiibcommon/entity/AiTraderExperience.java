package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经验账本的一条：复盘沉淀的执行缺口 / 假设 / 规律 / 发现。名下的单子记在 ai_trader_experience_trade，
 * 转正、降级、闲置退役由代码按账判；退役不删，只是不再注入 trader。渲染文本写回 ai_trader.memory。
 */
@Data
@TableName("ai_trader_experience")
public class AiTraderExperience {

    /** 自己写下要做、或主人指令里写了数字的硬线，碰到时没做到 */
    public static final String KIND_GAP = "GAP";
    /** 名下单子还不够的经验 */
    public static final String KIND_HYPOTHESIS = "HYPOTHESIS";
    /** 名下单子的账够了，代码转正 */
    public static final String KIND_RULE = "RULE";
    /** 指令本身在花钱，只给主人看，不注入 */
    public static final String KIND_FINDING = "FINDING";

    public static final String SLOT_ENTRY = "ENTRY";
    // 这三种是持仓途中怎么处理：新增不收，只在旧数据里
    public static final String SLOT_FAIL_DEF = "FAIL_DEF";
    public static final String SLOT_FAIL_HANDLE = "FAIL_HANDLE";
    public static final String SLOT_PROFIT_HANDLE = "PROFIT_HANDLE";
    public static final String SLOT_RISK = "RISK";
    public static final String SLOT_EXEC = "EXEC";

    public static final String DIR_OPPORTUNITY = "OPPORTUNITY";
    public static final String DIR_RESTRICTION = "RESTRICTION";
    public static final String DIR_EXECUTION = "EXECUTION";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETIRED = "RETIRED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    /** 所属单子类型（TradeGuard.PLAY_TYPES），null=通用 */
    private String playType;

    /** GAP / HYPOTHESIS / RULE / FINDING */
    private String kind;

    /** ENTRY / FAIL_DEF / FAIL_HANDLE / PROFIT_HANDLE / RISK / EXEC */
    private String slot;

    /** OPPORTUNITY / RESTRICTION / EXECUTION */
    private String direction;

    private String text;

    private String evidence;

    /** trader 结论里作为开仓依据引用的次数 */
    private Integer citedOpen;

    /** trader 结论里在观望轮引用的次数 */
    private Integer citedHold;

    /** ACTIVE / RETIRED */
    private String status;

    /** 当前段，从 0 起：转正、降级各加一 */
    private Integer segmentNo;

    /** 连续几篇复盘名下没有新单子 */
    private Integer idleReviews;

    /** 主人钉住：不自动降级、不自动退役，复盘删不掉 */
    private Boolean pinned;

    /** 来源条目：复盘新增时写的「来源 #编号」，或开新局带入前的那一条 */
    private Long sourceId;

    private Long createdReviewId;

    private Long retiredReviewId;

    private String retireReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
