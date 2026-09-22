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
 * Jev 预测员每回合每检查点一行：发出的 state、Jev 的回答（后劲；空仓还有决定）、三个概率、盘口、动作、注单；结算后回填结果与盈亏。
 * 买由 Jev 的决定题拍板，卖由代码按公平价定；记分看三列：纯数学 p_model、后劲修正 p_jev、市场隐含 p_mkt，Brier 在 SQL 里现算。
 */
@Data
@TableName("jev_prediction_decision")
public class JevPredictionDecision {

    public static final String ACTION_BUY_UP = "BUY_UP";
    public static final String ACTION_BUY_DOWN = "BUY_DOWN";
    public static final String ACTION_STAY_OUT = "STAY_OUT";
    public static final String ACTION_HOLD = "HOLD";
    public static final String ACTION_SELL = "SELL";
    public static final String ACTION_ERROR = "ERROR";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 窗口起点(秒)，与 prediction_round.window_start 同 */
    private Long windowStart;

    /** 检查点：开盘后第几秒，如 T150；空仓问买不买，持仓只问后劲 */
    private String checkpoint;

    /** 决策时刻(ms) */
    private Long decidedAt;

    /** 发给 Jev 的 state 原文 */
    private String stateJson;

    /** Jev 的回答原样：后劲题，空仓还有决定题 */
    private String answersJson;

    /** 纯数学的上涨概率：领先 / 剩余时间 / 波动 出的 Φ(z)，末分钟含锁定；也是"划不划算"的公平价 */
    private BigDecimal pModel;

    /** 数学概率按 Jev 后劲判断修正后的上涨概率，只记分，看后劲判断有没有信息量 */
    private BigDecimal pJev;

    /** 市场隐含上涨概率 up_mid/(up_mid+down_mid) */
    private BigDecimal pMkt;

    /** 纯数学的 z，正 = 偏 UP */
    private BigDecimal leadSigma;

    /** Jev 后劲：还在推减在回吐，−1 … +1 */
    private BigDecimal momentum;

    /** Jev 决定题概率最高的选项：BUY_UP / BUY_DOWN / WAIT；持仓行没问决定，为空 */
    private String jevChoice;

    /** 它的概率 */
    private BigDecimal jevChoiceP;

    /** 盘口距上次推送的毫秒数；空=没记录 */
    private Integer bookAgeMs;

    private BigDecimal upAsk;
    private BigDecimal downAsk;
    private BigDecimal upBid;
    private BigDecimal downBid;

    /** 买入方向那一侧的绝对优势 p − 卖价 − 手续费；没买也记 */
    private BigDecimal edge;

    /** BUY_UP/BUY_DOWN/STAY_OUT/HOLD/SELL/ERROR */
    private String action;

    /**
     * 为什么这么做，"代码 + 细节"：BUY / WAIT / UNSURE / NO_QUOTE / ASK_RANGE / NOT_CHEAP / NO_BALANCE /
     * HOLD / SELL / NO_BID / STALE_BOOK / STALE_WHILE_ASKING；页面按首个词出中文提示。异常看 error
     */
    private String reason;

    /** 本行开的注单（BUY_*）或操作的注单（SELL/HOLD） */
    private Long betId;

    /** 本金 cost，不含手续费 */
    private BigDecimal stake;

    private BigDecimal shares;

    private BigDecimal avgPrice;

    /** 回合结果 UP/DOWN/VOID，结算后回填 */
    private String outcome;

    /** 本行开的注单最终盈亏 = payout − cost − 买入手续费；只在 BUY_* 行上有 */
    private BigDecimal pnl;

    /** 实际作答的 Jev 版本号 */
    private String model;

    private Integer inputTokens;

    private Integer latencyMs;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
