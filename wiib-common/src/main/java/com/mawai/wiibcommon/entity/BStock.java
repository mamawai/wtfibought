package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * bStock 代币化美股（静态信息）。
 * 真实行情/价格走 feed→Redis，本表只存标的身份与公司基本面，独立于虚拟 {@link Stock}/{@link Company}。
 */
@Data
@TableName("bstock")
public class BStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Binance 现货符号，如 NVDABUSDT（查价/下单用） */
    private String symbol;

    /** 真实股票代码，如 NVDA（展示用） */
    private String ticker;

    /** 中文名，如 英伟达 */
    private String name;

    /** 英文名，如 Nvidia Corp */
    private String nameEn;

    /** 行业 */
    private String industry;

    /** 中文简介 */
    private String description;

    private String ceo;

    private String homepage;

    /** 市值(USD) */
    private BigDecimal marketCap;

    /** 市盈率 */
    private BigDecimal peRatio;

    /** 股息率 */
    private BigDecimal dividendYield;

    /** bStock 乘数（信息用，现货价已含分红再投） */
    private BigDecimal multiplier;

    /** 52周最高 */
    @TableField("week52_high")
    private BigDecimal week52High;

    /** 52周最低 */
    @TableField("week52_low")
    private BigDecimal week52Low;

    /** 是否上架 */
    private Boolean enabled;

    /** 展示排序 */
    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
