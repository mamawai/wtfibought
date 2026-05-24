package com.mawai.wiibservice.agent.binance.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * POST /fapi/v1/order 下单请求。
 * 不同 type 组合的必填字段：
 *   LIMIT          需要 timeInForce + price + quantity
 *   MARKET         需要 quantity
 *   STOP_MARKET    需要 stopPrice + quantity（或 closePosition=true）
 *   TAKE_PROFIT_MARKET 同上
 * 校验放在 Client 层，本类只承载数据。
 */
@Data
@Builder
public class PlaceOrderRequest {

    /** 交易对，受白名单约束 */
    private String symbol;
    /** BUY / SELL */
    private String side;
    /** LIMIT / MARKET / STOP / STOP_MARKET / TAKE_PROFIT / TAKE_PROFIT_MARKET / TRAILING_STOP_MARKET */
    private String type;
    /** 数量 */
    private BigDecimal quantity;
    /** GTC / IOC / FOK / GTX(POST_ONLY) / GTD，LIMIT 必填 */
    private String timeInForce;
    /** 限价价格，LIMIT 必填 */
    private BigDecimal price;
    /** 单向持仓填 BOTH（默认），双向填 LONG/SHORT */
    private String positionSide;
    /** 仅减仓单，单向持仓模式不可与 closePosition 同传，双向持仓模式不接受此参数 */
    private Boolean reduceOnly;
    /** 自定义订单号，正则 ^[\.A-Z\:/a-z0-9_-]{1,36}$ */
    private String newClientOrderId;
    /** ACK(默认) / RESULT */
    private String newOrderRespType;
    /** 触发价，条件单必填 */
    private BigDecimal stopPrice;
    /** 触发后是否全平仓，条件单可用 */
    private Boolean closePosition;
    /** CONTRACT_PRICE(默认) / MARK_PRICE */
    private String workingType;
    /** 条件单触发保护 */
    private Boolean priceProtect;
    /** timeInForce=GTD 时的自动撤单时间（ms） */
    private Long goodTillDate;
}
