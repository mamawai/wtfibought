package com.mawai.wiibquant.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 通用订单响应（下单 / 撤单 / 查单 / 查挂单 共用一个结构）。
 * Binance 不同接口返回字段略有差异：
 *   下单返回 updateTime，查单/查挂单还返回 time（创建时间）。
 *   撤单成功时 status=CANCELED。
 */
@Data
public class OrderResponse {

    private Long orderId;
    private String clientOrderId;
    private String symbol;
    /** BUY / SELL */
    private String side;
    /** BOTH / LONG / SHORT */
    private String positionSide;
    /** NEW / PARTIALLY_FILLED / FILLED / CANCELED / EXPIRED / REJECTED 等 */
    private String status;
    /** LIMIT / MARKET / STOP / ... */
    private String type;
    /** 条件单触发前的 type */
    private String origType;
    /** GTC / IOC / FOK / GTX / GTD */
    private String timeInForce;

    private BigDecimal price;
    private BigDecimal avgPrice;
    private BigDecimal origQty;
    private BigDecimal executedQty;
    /** 累计成交金额 */
    private BigDecimal cumQuote;
    /** 累计成交数量，部分接口返回；通常与 executedQty 等值 */
    private BigDecimal cumQty;
    private BigDecimal stopPrice;

    private Boolean reduceOnly;
    private Boolean closePosition;
    private Boolean priceProtect;

    /** CONTRACT_PRICE / MARK_PRICE */
    private String workingType;
    /** NONE / OPPONENT / OPPONENT_5/10/20 / QUEUE / QUEUE_5/10/20 */
    private String priceMatch;
    /** NONE / EXPIRE_TAKER / EXPIRE_MAKER / EXPIRE_BOTH */
    private String selfTradePreventionMode;

    /** 跟踪止损激活价（仅 TRAILING_STOP_MARKET） */
    private BigDecimal activatePrice;
    /** 跟踪止损回调比例（仅 TRAILING_STOP_MARKET） */
    private BigDecimal priceRate;

    /** 订单创建时间（查单/查挂单才有） */
    private Long time;
    private Long updateTime;
    /** TIF=GTD 时的自动撤单时间 */
    private Long goodTillDate;
}
