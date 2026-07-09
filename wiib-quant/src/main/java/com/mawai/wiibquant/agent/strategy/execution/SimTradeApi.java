package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.util.Result;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * quant → sim 合约交易 internal API 声明式契约（Spring 6 HTTP Interface，零额外依赖）。
 *
 * <p>方法签名即端点定义，与 sim 侧 InternalFuturesTradeController 一一对应，
 * 代理在 {@link SimTradeClient} 构造时经 HttpServiceProxyFactory 生成，无手写实现。
 * 返回 Result 原壳——sim 业务失败是 200+Result.fail 不走 HTTP 状态码，
 * 拆壳转异常统一在 facade 的 unwrap，此处不做。
 * 路径/查询参数名靠编译期 -parameters 反射获取（spring-boot-starter-parent 默认开启）。</p>
 */
@HttpExchange("/internal/futures")
public interface SimTradeApi {

    @PostExchange("/{userId}/open")
    Result<FuturesOrderResponse> open(@PathVariable Long userId, @RequestBody FuturesOpenRequest request);

    @PostExchange("/{userId}/close")
    Result<FuturesOrderResponse> close(@PathVariable Long userId, @RequestBody FuturesCloseRequest request);

    @PostExchange("/{userId}/cancel/{orderId}")
    Result<FuturesOrderResponse> cancel(@PathVariable Long userId, @PathVariable Long orderId);

    @GetExchange("/{userId}/order/{orderId}")
    Result<FuturesOrderResponse> order(@PathVariable Long userId, @PathVariable Long orderId);

    @GetExchange("/{userId}/pending-orders")
    Result<List<FuturesOrderResponse>> pendingOrders(@PathVariable Long userId, @RequestParam(required = false) String symbol);

    /** symbol 传 null 即不带查询参数 = 全 symbol 持仓（策略账户监控页用）。 */
    @GetExchange("/{userId}/positions")
    Result<List<FuturesPositionDTO>> positions(@PathVariable Long userId, @RequestParam(required = false) String symbol);

    @GetExchange("/{userId}/closed-positions")
    Result<List<FuturesPositionDTO>> closedPositions(@PathVariable Long userId, @RequestParam int limit);

    @GetExchange("/{userId}/balance")
    Result<Map<String, Object>> balance(@PathVariable Long userId);

    @PostExchange("/ensure-account")
    Result<Map<String, Object>> ensureAccount(@RequestParam String username, @RequestParam BigDecimal initialBalance);
}
