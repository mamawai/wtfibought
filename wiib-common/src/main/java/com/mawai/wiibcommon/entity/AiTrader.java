package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI Trader：用户 BYOK 自主交易代理（每用户 1 个，独立 sim 子账户，公开竞技场）。
 * api_key_enc 是 AES-GCM 密文，只在构建 ChatModel 的瞬间解密，绝不进日志。
 */
@Data
@TableName("ai_trader")
public class AiTrader {

    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_LIQUIDATED = "LIQUIDATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** PAUSED / RUNNING / LIQUIDATED */
    private String status;

    /** 暂停原因（连败自动暂停/爆仓终局）；手动恢复时清空。ALWAYS：清空=写 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pausedReason;

    /** 交易币种白名单子集，逗号分隔，如 BTCUSDT,ETHUSDT */
    private String symbols;

    /** 唤醒K线级别：5m/15m/1h/4h（1d 已下线） */
    private String intervalCode;

    /** 主人的交易指令（方法/风格/纪律的唯一来源），追加在平台系统提示词之后；每次唤醒现读现拼，改完下一根K线生效 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customPrompt;

    /**
     * 复盘笔记：由 reviewer 每日复盘整理写入（限长文本），每次唤醒注入提示词。
     * trader 侧只读只注入——本字段即记忆学习的接口。ALWAYS：清空笔记=写 null
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String memory;

    /**
     * 学习笔记：learning agent 向同侪学习后整理写入（≤2000字覆盖写），每次唤醒与复盘笔记并列注入。
     * 与 memory 分开存——来源分开模型才分得清"自己的教训"与"从别人学的"。ALWAYS：清空=写 null
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String learningNotes;

    /**
     * 主人留言：trader 动作面板写入，随提示词注入，每注入一次 {@link #ownerNoteRounds} 减 1，
     * 减到 0 连同本列一起清空。与 memory 的分工：memory 是复盘沉淀的长期笔记，
     * 这里是主人阶段性交代的一句话。
     * ALWAYS：清空=写 null
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String ownerNote;

    /**
     * 留言剩余注入轮次，0=无待读留言。1 即"念一次就清"，上限 24。
     * 列是 NOT NULL DEFAULT 0，注入处按整数直接用；取到 0/null 时按 1 轮兜底，
     * 保证有正文就一定会被念到、且只念一次。
     */
    private Integer ownerNoteRounds;

    /** 每日复盘开关：reviewer 日线边界复盘并整理 memory；关掉只停复盘，已有笔记照常注入 */
    private Boolean reviewEnabled;

    /** 同侪学习开关：learning agent 在全体复盘完成后向同侪学习并整理 learningNotes；关掉只停学习，已有笔记照常注入 */
    private Boolean learningEnabled;

    /** 波动哨兵警报开关（仅 1h/4h 档生效） */
    private Boolean alertEnabled;

    /** 警报灵敏度系数 ≥1.0 只能调高：生效阈值 = 每币基准 × 本系数 */
    private BigDecimal alertThresholdMult;

    /**
     * 唤醒时段（北京时间）"HH:mm-HH:mm"，5 分钟粒度、两端含、可跨午夜；null=全天。
     * 只管交易类唤醒（例行/警报），手动唤醒不拦；日线交接的复盘/学习不看它——夜里交易了就该复盘夜里的交易。
     * 解析与判断见 WakeWindow。改回全天=写 null（updateConfig 列级 set）
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String wakeWindow;

    // BYOK 四件套（协议/URL/模型/key）已迁到 user_llm_endpoint：trader 按 UserLlmBinding.TRADER 绑定选端点，
    // 没绑定就用该用户的默认端点，见 LlmEndpointService.resolve

    /** 当前局 sim 子账户 userId（每局一个独立子账户，重置开新局） */
    private Long simUserId;

    /** 局数：爆仓/手动重置后 +1 开新局，历史决策与战绩留档 */
    private Integer roundNo;

    /** 连续唤醒失败计数：成功清零，≥5 自动 PAUSED */
    private Integer consecutiveFailures;

    /** 杠杆区间：模型必须从 [min,max] 里选，越界护栏拒。不截断——悄悄改值会让模型的止损计算失真 */
    private Integer leverageMin;
    private Integer leverageMax;

    /** 单笔保证金占权益%区间；只约束开新仓，加仓量由模型自己斟酌 */
    private BigDecimal marginPctMin;
    private BigDecimal marginPctMax;

    /** 允许同时持有多个仓位；false=全账户至多一仓（挂单一并计数） */
    private Boolean allowMultiPosition;

    /** 允许同币多空双开；仅在 allowMultiPosition=true 时有意义 */
    private Boolean allowHedge;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
