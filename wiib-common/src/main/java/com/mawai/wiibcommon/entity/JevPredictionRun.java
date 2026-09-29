package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Jev 预测员的一局：一局一个 sim 账户，旧局的账户和决策留档。
 * v5 起一次开局建三局，三组对照各占一局，arm 是哪一组；每组局号最大的那一局在跑
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("jev_prediction_run")
public class JevPredictionRun {

    /** v5-1：突变就买，不看 Jev */
    public static final String ARM_JUMP_CODE = "JUMP_CODE";
    /** v5-2：突变时 Jev 判不在变弱才买 */
    public static final String ARM_JUMP_JEV = "JUMP_JEV";
    /** v5-3：整点唤醒，Jev 参与买卖 */
    public static final String ARM_TIMER_JEV = "TIMER_JEV";
    /** 三组，按 v5-1、v5-2、v5-3 排 */
    public static final List<String> ARMS = List.of(ARM_JUMP_CODE, ARM_JUMP_JEV, ARM_TIMER_JEV);

    /** 第几局，从 1 起 */
    @TableId(type = IdType.INPUT)
    private Integer runNo;

    /** 版本说明，开局时填 */
    private String label;

    /** 开局时刻(ms) */
    private Long startedAt;

    /** 哪一组 JUMP_CODE / JUMP_JEV / TIMER_JEV；v5 之前的旧局为空 */
    private String arm;

    /** 这一局账户的初始资金，旧局是 100 */
    private BigDecimal initialBalance;
}
