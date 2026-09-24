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
 * Jev 预测员每回合每检查点一行：发出的 state、Jev 的回答（拍板 + 谁赢记分）、三个概率、盘口、动作、注单；结算后回填结果与盈亏。
 * 买卖都由 Jev 拍板（R3 起）；记分看三列：纯数学 p_model、Jev 的 p_jev、市场隐含 p_mkt，Brier 在 SQL 里现算。
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

    /** 第几局，见 jev_prediction_run */
    private Integer runNo;

    /** 窗口起点(秒)，与 prediction_round.window_start 同 */
    private Long windowStart;

    /** 检查点：开盘后第几秒，如 T150 */
    private String checkpoint;

    /** 决策时刻(ms) */
    private Long decidedAt;

    /** 发给 Jev 的 state 原文 */
    private String stateJson;

    /** Jev 的回答原样：UP、DOWN 谁赢两问记分，加空仓的入场题或持仓的离场题；R1 旧版是后劲题和决定题，R2 只有谁赢两问 */
    private String answersJson;

    /** 纯数学的上涨概率：领先 / 剩余时间 / 波动 出的 Φ(z)，末分钟含锁定；R3 起算好写进 state 给 Jev 比 */
    private BigDecimal pModel;

    /** Jev 的上涨概率：UP 会赢的概率和 1 − DOWN 会赢的概率取平均；R1 旧版是数学概率按后劲修正后的值 */
    private BigDecimal pJev;

    /** 市场隐含上涨概率 up_mid/(up_mid+down_mid) */
    private BigDecimal pMkt;

    /** 纯数学的 z，正 = 偏 UP */
    private BigDecimal leadSigma;

    /** Jev 拍板的选项：空仓 BUY_UP / BUY_DOWN / PASS，持仓 HOLD / SELL；R1 旧版是 BUY_UP / BUY_DOWN / WAIT，R2 为空 */
    private String jevChoice;

    /** 它的概率，到 act-threshold 才照做 */
    private BigDecimal jevChoiceP;

    /** 盘口距上次更新的毫秒数；空=没记录 */
    private Integer bookAgeMs;

    private BigDecimal upAsk;
    private BigDecimal downAsk;
    private BigDecimal upBid;
    private BigDecimal downBid;

    /**
     * 按数学估计的每份优势，只作参考：空仓是 Jev 选的那边 p_model − 卖价 − 手续费，持仓是卖出扣费后比 p_model 多拿多少。
     * R2 是按 Jev 胜率算、优势大那边的，R1 按 p_model
     */
    private BigDecimal edge;

    /** BUY_UP/BUY_DOWN/STAY_OUT/HOLD/SELL/ERROR */
    private String action;

    /**
     * 为什么这么做，"代码 + 细节"：BUY / PASS / UNSURE / NO_QUOTE / NO_BALANCE / MISSED /
     * HOLD / SELL / NO_BID / STALE_BOOK / STALE_CHAINLINK / STALE_WHILE_ASKING；R2 还有 WAIT / ASK_LOW，
     * R1 还有 NOT_CHEAP / EXPENSIVE / ASK_RANGE。页面按首个词出中文提示。异常看 error
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
