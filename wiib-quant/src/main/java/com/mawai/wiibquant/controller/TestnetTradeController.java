package com.mawai.wiibquant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetClient;
import com.mawai.wiibquant.agent.binance.model.OrderResponse;
import com.mawai.wiibquant.agent.binance.model.PlaceOrderRequest;
import com.mawai.wiibquant.agent.binance.model.PositionRisk;
import com.mawai.wiibquant.agent.binance.model.SimpleAck;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Binance Testnet 手动交易：上线/联调前人工开平仓冒烟，验证下单链路(签名→下单→持仓)通不通。
 *
 * <p>仅 admin(userId==1) 可调；symbol 受 {@link BinanceFuturesTestnetClient} 白名单约束；市价+限价都支持。
 * 与自动策略执行 {@link com.mawai.wiibquant.agent.strategy.execution.TestnetExecutionService} 互不感知，
 * 手动冒烟期间建议关闭 strategy.execution.enabled，避免两套状态机互相干扰。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/testnet/manual")
@RequiredArgsConstructor
public class TestnetTradeController {

    private final BinanceFuturesTestnetClient client;

    /** 仅 1 号用户放行；未登录/token 无效/非 1 号都判非管理员(不抛异常，避免 500 与误触发前端登录跳转)。 */
    private boolean notAdmin() {
        try {
            return StpUtil.getLoginIdAsLong() != 1L;
        } catch (Exception e) {
            return true;
        }
    }

    /** 手动下单：MARKET 立即成交 / LIMIT 挂单。传 leverage 则先调杠杆再下单。 */
    @PostMapping("/order")
    public Result<OrderResponse> order(@RequestBody ManualOrderRequest req) {
        if (notAdmin()) return Result.fail("仅管理员可操作");
        try {
            if (req.getSymbol() == null || req.getSide() == null || req.getType() == null) {
                return Result.fail("symbol/side/type 必填");
            }
            if (req.getQuantity() == null || req.getQuantity().signum() <= 0) {
                return Result.fail("quantity 必须 > 0");
            }
            boolean isLimit = "LIMIT".equalsIgnoreCase(req.getType());
            if (isLimit && (req.getPrice() == null || req.getPrice().signum() <= 0)) {
                return Result.fail("LIMIT 必须传 price");
            }
            if (req.getLeverage() != null) {
                client.setLeverage(req.getSymbol(), req.getLeverage());  // 白名单/范围校验在 client 内
            }
            PlaceOrderRequest.PlaceOrderRequestBuilder b = PlaceOrderRequest.builder()
                    .symbol(req.getSymbol())
                    .side(req.getSide().toUpperCase())
                    .type(req.getType().toUpperCase())
                    .quantity(req.getQuantity())
                    .newOrderRespType("RESULT");           // 同步返回成交价/状态，冒烟即时可见
            if (isLimit) {
                b.price(req.getPrice())
                        .timeInForce(req.getTimeInForce() == null || req.getTimeInForce().isBlank()
                                ? "GTC" : req.getTimeInForce().toUpperCase());
            }
            if (Boolean.TRUE.equals(req.getReduceOnly())) b.reduceOnly(true);

            OrderResponse resp = client.placeOrder(b.build());
            log.info("[TestnetManual] 下单 symbol={} {} {} qty={} price={} → orderId={} status={}",
                    req.getSymbol(), req.getSide(), req.getType(), req.getQuantity(), req.getPrice(),
                    resp.getOrderId(), resp.getStatus());
            return Result.ok(resp);
        } catch (Exception e) {
            log.warn("[TestnetManual] 下单失败 req={} msg={}", req, e.toString());
            return Result.fail(e.getMessage());
        }
    }

    /** 一键市价平仓：查持仓→反向 MARKET reduceOnly 全平。无持仓则提示。 */
    @PostMapping("/close")
    public Result<OrderResponse> close(@RequestParam String symbol) {
        if (notAdmin()) return Result.fail("仅管理员可操作");
        try {
            List<PositionRisk> risks = client.getPositionRisk(symbol);
            BigDecimal amt = risks == null ? BigDecimal.ZERO : risks.stream()
                    .filter(r -> symbol.equals(r.getSymbol()))
                    .map(PositionRisk::getPositionAmt)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(BigDecimal.ZERO);
            if (amt.signum() == 0) return Result.fail("当前无持仓: " + symbol);

            String side = amt.signum() > 0 ? "SELL" : "BUY";  // 多头平仓=SELL，空头=BUY
            PlaceOrderRequest req = PlaceOrderRequest.builder()
                    .symbol(symbol)
                    .side(side)
                    .type("MARKET")
                    .quantity(amt.abs())
                    .reduceOnly(true)
                    .newOrderRespType("RESULT")
                    .build();
            OrderResponse resp = client.placeOrder(req);
            log.info("[TestnetManual] 平仓 symbol={} {} qty={} → orderId={} status={}",
                    symbol, side, amt.abs(), resp.getOrderId(), resp.getStatus());
            return Result.ok(resp);
        } catch (Exception e) {
            log.warn("[TestnetManual] 平仓失败 symbol={} msg={}", symbol, e.toString());
            return Result.fail(e.getMessage());
        }
    }

    /** 撤销该 symbol 全部挂单(清场，方便重测)。 */
    @PostMapping("/cancel-all")
    public Result<SimpleAck> cancelAll(@RequestParam String symbol) {
        if (notAdmin()) return Result.fail("仅管理员可操作");
        try {
            SimpleAck ack = client.cancelAllOpenOrders(symbol);
            log.info("[TestnetManual] 撤全部挂单 symbol={}", symbol);
            return Result.ok(ack);
        } catch (Exception e) {
            log.warn("[TestnetManual] 撤单失败 symbol={} msg={}", symbol, e.toString());
            return Result.fail(e.getMessage());
        }
    }

    /** 手动下单请求体。type=MARKET 时 price/timeInForce 忽略；type=LIMIT 时 price 必填、timeInForce 默认 GTC。 */
    @Data
    public static class ManualOrderRequest {
        private String symbol;       // 受白名单约束(BTCUSDT/ETHUSDT)
        private String side;         // BUY / SELL
        private String type;         // MARKET / LIMIT
        private BigDecimal quantity; // 下单数量，必须 > 0
        private BigDecimal price;    // LIMIT 必填
        private Integer leverage;    // 可选：传了就先调杠杆
        private String timeInForce;  // 可选：LIMIT 默认 GTC，可传 GTX(POST_ONLY)/IOC/FOK
        private Boolean reduceOnly;  // 可选：仅减仓
    }
}
