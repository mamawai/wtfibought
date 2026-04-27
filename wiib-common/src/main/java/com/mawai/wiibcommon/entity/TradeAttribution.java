package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "trade_attribution", autoResultMap = true)
public class TradeAttribution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;

    private String symbol;

    /** BREAKOUT / MR / LEGACY_TREND；SHADOW_5OF7 不入本表。 */
    private String strategyPath;

    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String entryFactorsJson;

    private BigDecimal pnl;

    private Integer holdingMinutes;

    /** TP / TRAILING / SL / TIMEOUT / REVERSAL / MANUAL / UNKNOWN。 */
    private String exitReason;
}
