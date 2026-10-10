package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经验账本的一条：复盘沉淀的执行缺口 / 假设 / 规律 / 发现。代码记支持反对次数、升级、举旗；
 * 退役不删，只是不再注入 trader。渲染文本写回 ai_trader.memory。
 */
@Data
@TableName("ai_trader_experience")
public class AiTraderExperience {

    /** 主人指令要求了却反复没做到 */
    public static final String KIND_GAP = "GAP";
    /** 样本还不够的经验 */
    public static final String KIND_HYPOTHESIS = "HYPOTHESIS";
    /** 假设被支持够多次升上来 */
    public static final String KIND_RULE = "RULE";
    /** 指令本身在花钱，只给主人看，不注入 */
    public static final String KIND_FINDING = "FINDING";

    public static final String SLOT_ENTRY = "ENTRY";
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

    private Integer supports;

    private Integer opposes;

    private Integer untested;

    /** trader 结论里作为开仓依据引用的次数 */
    private Integer citedOpen;

    /** trader 结论里在观望轮引用的次数 */
    private Integer citedHold;

    /** ACTIVE / RETIRED */
    private String status;

    /** 待复审 */
    private Boolean flagged;

    /** 上次复审之后积累的结论数 */
    private Integer verdictsSinceAudit;

    /** 主人钉住：不举旗不退役 */
    private Boolean pinned;

    /** 修订 / 拆分的来源条目 */
    private Long sourceId;

    private Long createdReviewId;

    private Long retiredReviewId;

    private String retireReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
