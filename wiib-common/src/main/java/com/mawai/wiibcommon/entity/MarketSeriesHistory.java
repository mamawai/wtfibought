package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 链下时点序列落库实体（资金费/恐惧贪婪等单值序列）；created_at 由 DB 默认值填充，实体不映射。 */
@Data
@TableName("market_series_history")
public class MarketSeriesHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;       // 标的；全市场序列(如 F&G)用 'GLOBAL'
    private String seriesCode;   // SeriesCode.name()
    private Long ts;             // epoch 毫秒
    private BigDecimal value;
}
