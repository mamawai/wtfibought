package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 历史 K 线落库实体；created_at 由 DB 默认值填充，实体不映射。 */
@Data
@TableName("kline_history")
public class KlineHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;
    private String intervalCode;
    private Long openTime;
    private Long closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
}
