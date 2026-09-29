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
 * Jev 预测员每局每回合每次唤醒一行：发出的 state、Jev 的回答、概率、盘口、动作、注单；结算后回填结果与盈亏。
 * v5 起三组对照各占一局，一次唤醒按涉及的局各写一行，共用同一份 state 和 Jev 的回答；Jev 只答盘面六道题，买卖由代码按各组规则定。
 * R3、R4 买卖由 Jev 拍板，R4 起空仓持仓同一道题、不问谁赢，p_jev 为空；v4 之后持仓行只记 Jev 的选择。
 * p_model、p_mkt 照记，Brier 在 SQL 里现算。
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

    /** 检查点：开盘后第几秒，首字母是唤醒方式：T 整点如 T150，J 赔率突变如 J57（v5 起有）；同一局里不重复 */
    private String checkpoint;

    /**
     * 这一行看的是哪一边 UP / DOWN：突变唤醒看突变那一边，整点空仓看数学上领先的那边、持仓看手里那一边；
     * v5 起有，盘口或 Chainlink 太旧没往下走的行为空
     */
    private String side;

    /** 决策时刻(ms) */
    private Long decidedAt;

    /** 发给 Jev 的 state 原文 */
    private String stateJson;

    /**
     * Jev 的回答原样：v5 是盘面六道题 win / pattern / push_fading / flow_confirms / dip_recovered / latest_against；R4 只有入场题 entry；
     * R3 是谁赢两问加空仓入场题或持仓离场题，R2 只有谁赢两问，R1 是后劲题和决定题
     */
    private String answersJson;

    /** 纯数学的上涨概率：领先 / 剩余时间 / 波动 出的 Φ(z)，末分钟含锁定；只 R3 写进 state 给 Jev 比，R4 起只记分 */
    private BigDecimal pModel;

    /**
     * Jev 的上涨概率：v5 看 UP 时是 win 题的概率，看 DOWN 时是 1 − win；R2、R3 是 UP 会赢的概率和 1 − DOWN 会赢的概率取平均；
     * R1 旧版是数学概率按后劲修正后的值；R4 不问为空
     */
    private BigDecimal pJev;

    /** 市场隐含上涨概率 up_mid/(up_mid+down_mid) */
    private BigDecimal pMkt;

    /** 纯数学的 z，正 = 偏 UP */
    private BigDecimal leadSigma;

    /**
     * Jev 拍板的选项 BUY_UP / BUY_DOWN / PASS，R4 空仓持仓都是这三个；R3 持仓是 HOLD / SELL，R1 旧版是 BUY_UP / BUY_DOWN / WAIT；
     * R2、v5 Jev 不拍板为空
     */
    private String jevChoice;

    /** 它的概率，R3、R4 空仓时到 act-threshold 才照做 */
    private BigDecimal jevChoiceP;

    /** 盘口距上次更新的毫秒数；空=没记录 */
    private Integer bookAgeMs;

    private BigDecimal upAsk;
    private BigDecimal downAsk;
    private BigDecimal upBid;
    private BigDecimal downBid;

    /**
     * v5：突变行记这次突变 UP 中间价涨了多少，UP 跌的记 0；整点行为空。
     * R4：最近 15 秒里 UP 中间价 3 秒内的最大涨幅，没涨是 0，不管到没到写进 state 的阈值都记，采样不够为空
     */
    private BigDecimal oddsJumpUp;

    /** v5：突变行记这次突变 UP 中间价跌了多少，负数，UP 涨的记 0；整点行为空。R4：同上的最大跌幅，负数，没跌是 0 */
    private BigDecimal oddsJumpDown;

    /** 唤醒后 15 秒的 UP 中间价，回填任务补；越过回合末尾或没有采样为空；v5 起有 */
    @TableField("up_mid_15s")
    private BigDecimal upMid15s;

    /** 唤醒后 45 秒的 UP 中间价，同上 */
    @TableField("up_mid_45s")
    private BigDecimal upMid45s;

    /**
     * 按数学估计的每份优势，只给页面作参考：R3、R4 买入行是那边 p_model − 卖价 − 手续费、持仓行不记，
     * R3 和 v4 的持仓行是卖出扣费后比 p_model 多拿多少。R2 是按 Jev 胜率算、优势大那边的，R1 按 p_model；v5 不记
     */
    private BigDecimal edge;

    /** BUY_UP/BUY_DOWN/STAY_OUT/HOLD/SELL/ERROR；SELL 只 R3、v4 和 v5-3 有，v4 那一局持仓时加注也记 BUY_* */
    private String action;

    /**
     * 为什么这么做，"代码 + 细节"：v5 是 BUY / FADING / PRICE_BAND / NO_PULLBACK / HOLD / SELL / NO_BID / MISSED / NO_QUOTE /
     * NO_BALANCE / STALE_BOOK / STALE_CHAINLINK / STALE_WHILE_ASKING；R3、R4 还有 PASS / UNSURE，
     * v4 那一局还有 ADD / MAX_STAKE，R2 还有 WAIT / ASK_LOW，R1 还有 NOT_CHEAP / EXPENSIVE / ASK_RANGE。页面按首个词出中文提示。异常看 error
     */
    private String reason;

    /** 本行开的注单（BUY_*，v4 那一局含加注）；持仓行（HOLD / SELL）v5 记这一局这一回合在持的那一注，之前各版记本回合在持的第一笔 */
    private Long betId;

    /** 本金 cost，不含手续费；v5 持仓行同 bet_id 那一注，之前各版持仓行是在持的合计 */
    private BigDecimal stake;

    /** 份数；持仓行同上 */
    private BigDecimal shares;

    /** 均价；持仓行同上，之前各版是在持的加权均价 */
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
