package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Jev 预测员的一局：一局一个 sim 账户，重新开局加一行，旧局的账户和决策留档 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("jev_prediction_run")
public class JevPredictionRun {

    /** 第几局，从 1 起 */
    @TableId(type = IdType.INPUT)
    private Integer runNo;

    /** 版本说明，开局时填 */
    private String label;

    /** 开局时刻(ms) */
    private Long startedAt;
}
