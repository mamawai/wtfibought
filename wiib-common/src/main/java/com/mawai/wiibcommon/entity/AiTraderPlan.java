package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一个仓位一条：开仓那刻立的论点、依据、失效条件、入场/止损/目标价，外加后来每次改动的留痕。
 * 键 (trader, round, symbol, side)，活的至多一条；加仓＝新论点覆盖旧的。
 * <p>
 * 每次唤醒把活着的计划原样塞回提示词——没记忆的模型才看得见这仓当初为什么开、什么情况算论点错了、
 * 止损为什么在这个价格。invalidationCondition 是主动平仓的许可证：退出只有止损带走、止盈带走、
 * 失效条件触发、主人发话四条路，避免llm一看到回撤就主动平仓。
 * <p>
 * 价格字段是开仓时的快照永不改，当前生效的止损止盈单在 sim 仓位上；移止损、加仓、平仓、补立
 * 都往 revisionsJson 追加一条带理由的记录。平了归档不删，论点配结局喂复盘和论点战绩；
 * stale=主人说这笔不算数，退出统计。
 * <p>
 * 只有持仓和没成交的开仓挂单才有计划，空仓的币没有。漏立的用 write_plan 补，已有的改不了。
 */
@Data
@TableName("ai_trader_plan")
public class AiTraderPlan {

    public static final String STATUS_LIVE = "LIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    private String symbol;

    /** LONG / SHORT */
    private String side;

    /** 论点标签：BREAKOUT/PULLBACK/REVERSAL/... */
    private String playType;

    /** 开仓依据的数据引用 */
    private String signalsUsed;

    /** 失效条件：什么市场状况证明论点错了（市场条件而非盈亏数字），触发才允许主动平仓 */
    private String invalidationCondition;

    /** 开仓时点入场参考价快照（市价=当时现价，限价=限价） */
    private BigDecimal entryPrice;

    /** 原始止损快照；当前生效止损以 sim 仓位为准（可能已上移锁盈） */
    private BigDecimal stopLossPrice;

    private BigDecimal takeProfitPrice;

    /** 开仓所在唤醒边界(ms)，回注时计算已持有时长 */
    private Long openedWakeTime;

    /**
     * 修订历史追加式 JSON [{time,type,change,reason}]：加仓覆盖/移动止盈/移动止损/补立。
     * 修改必须留痕带理由（回注给下轮无记忆的模型看）；计划本体价格字段永远是原始快照，当前生效单在 sim 仓位上。
     */
    private String revisionsJson;

    /**
     * LIVE=仓位/挂单存活 CLOSED=已了结归档。归档不删：论点→结局的配对数据是
     * reviewer 复盘的原料（结局按 symbol/side/时间窗 join sim 已平仓位）。
     */
    private String status;

    /** 归档时刻(ms)：唤醒开头发现仓位已了结的那根边界，或重置时刻 */
    private Long closedWakeTime;

    /** 主人标记忽略：true=不进论点战绩统计与复盘教材；权益/排行榜/同侪视角照常。仅 CLOSED 可标，可逆 */
    private Boolean stale;

    /**
     * sim 仓位 id：市价开仓/加仓从下单响应落盘，限价单成交后由下次唤醒开头补上。
     * 计划↔仓位配对的精确键；NULL（历史行/未成交挂单）配对走 bestMatch 时间就近兜底。
     */
    private Long positionId;

    /**
     * 开仓那一刻的上下文快照 JSON：orderType / leverage / hourBj / stopPct / regime / atr1h / stopAtr /
     * eventMin / eventTitle / funding（见 EntryContext）。复盘按维度对照用；取不到的字段缺席，整体可为 null
     */
    private String entryCtxJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
