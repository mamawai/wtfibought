package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

/** bStock 列表/详情 DTO：静态信息(bstock 表) + 实时行情(Binance 24h / Redis)。 */
@Data
public class BStockDTO {

    // ── 静态（bstock 表）──
    private Long id;
    private String symbol;      // NVDABUSDT
    private String ticker;      // NVDA
    private String name;        // 英伟达
    private String nameEn;      // Nvidia Corp
    private String industry;
    private String description;
    private String ceo;
    private String homepage;
    private BigDecimal marketCap;
    private BigDecimal peRatio;
    private BigDecimal dividendYield;
    private BigDecimal multiplier;
    private BigDecimal week52High;
    private BigDecimal week52Low;

    // ── 实时（行情）──
    private BigDecimal price;       // 最新价
    private BigDecimal changePct;   // 24h 涨跌 %
    private BigDecimal high;        // 24h 高
    private BigDecimal low;         // 24h 低
    private BigDecimal volume;      // 24h 成交额(USDT)
}
