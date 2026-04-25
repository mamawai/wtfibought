package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("strategy_path_status")
public class StrategyPathStatus {

    /** BREAKOUT / MR / LEGACY_TREND；SHADOW_5OF7 不入本表。 */
    @TableId
    private String path;

    private Boolean enabled;

    private String disabledReason;

    private LocalDateTime disabledAt;

    private Integer consecutiveLossCount;

    private LocalDateTime updatedAt;
}
