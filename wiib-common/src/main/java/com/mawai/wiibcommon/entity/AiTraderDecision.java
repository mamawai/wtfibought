package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每次唤醒落一行，只增不改：这轮想了什么（reasoning）、调了什么工具做了什么（actionsJson）、
 * 当时权益多少。wake_time 就是那次唤醒的K线边界。
 * <p>
 * kind 两类：TRADE/ALERT/MANUAL 是唤醒（例行K线/波动警报/主人手动叫醒），有权益；
 * REVIEW 是复盘，reasoning 装的是复盘全文，没权益。
 * status：OK 正常、ERROR 失败、SKIPPED 上轮没跑完或信号迟到——跳过也照写，时间线上不藏。
 * <p>
 * 三处在用：竞技场的时间线与净值曲线；下一轮唤醒回注最近几行轨迹和上一轮结论；每日复盘拼素材。
 * reasoning 末尾的 [本轮结论] 块按 [SYMBOL] 一币一段，下游全靠这个形状切，模型没写就都退化。
 */
@Data
@TableName("ai_trader_decision")
public class AiTraderDecision {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String KIND_TRADE = "TRADE";
    public static final String KIND_ALERT = "ALERT";
    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_REVIEW = "REVIEW";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    /** 触发本次唤醒的K线边界时刻(ms)；ALERT 行是哨兵触发时刻，非边界 */
    private Long wakeTime;

    private String intervalCode;

    /**
     * TRADE=例行K线唤醒 ALERT=波动哨兵警报唤醒 MANUAL=主人手动唤醒（对话轨 wake_trader，回路与例行相同）
     * REVIEW=reviewer复盘（reasoning=复盘全文，无equity）
     */
    private String kind;

    /** OK / ERROR / SKIPPED（上一唤醒未完被跳过） */
    private String status;

    /** 唤醒时账户权益(USDT)，净值曲线数据源 */
    private BigDecimal equity;

    /** AI 最终回复全文——竞技场观赏核心 */
    private String reasoning;

    /** 本轮全部工具调用 JSON：[{tool,args,status,result/rejected/error}...]，交易动作带结构化论点标签 */
    private String actionsJson;

    private Integer toolCalls;

    /** 本轮模型调用次数：ReAct 是循环，一次唤醒会调很多次 */
    private Integer modelCalls;

    /** 本轮全部模型调用的 token 合计；null=上游端点没返回 usage，不是 0 */
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;

    private Integer latencyMs;

    private String error;

    /**
     * 仅 REVIEW 行：本期学习完的记忆快照存档（学习演进史，append-only）。
     * ai_trader.memory 是滚动覆盖的"生效版本"，历史版本只活在这一列。
     */
    private String memoryAfter;

    /** 唤醒过程轨迹 JSON（形状见 WakeTrace.toJson）；列表查询不背它，单独接口取。老行为空 */
    @TableField(select = false)
    private String traceJson;

    /** 时间线行有没有过程可看（trace_json 非空），decisions() 回填 */
    @TableField(exist = false)
    private Boolean hasTrace;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
