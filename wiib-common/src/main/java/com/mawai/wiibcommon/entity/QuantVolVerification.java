package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * vol 预测验证行（P3）：一个预测点（快照×horizon）到期后的对账结果。
 * QLIKE 口径 = research 的 VolForecastScore（Patton 2011）；vol-state 用快照携带的 PIT 档界分类 realized，
 * 禁止验证时重算档界。regime 刻意不验证——research 实证其无 skill，记分卡只晒有 skill 的腿。
 */
@Data
@TableName("quant_vol_verification")
public class QuantVolVerification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private String symbol;

    /** 预测时点（快照 closeTime） */
    private Long closeTime;

    /** H6 / H12 / H24 */
    private String horizon;

    private Integer forecastSigmaBps;

    /** naive 基准：预测时点上一个 horizon 窗口的 |实际对数收益|（"上期 vol"朴素预测） */
    private Integer baselineSigmaBps;

    /** 到期实际对数收益（bps，带符号） */
    private Integer realizedReturnBps;

    private Double qlike;

    private Double baselineQlike;

    private String volStatePredicted;

    private String volStateActual;

    private Boolean volStateHit;

    private LocalDateTime verifiedAt;
}
