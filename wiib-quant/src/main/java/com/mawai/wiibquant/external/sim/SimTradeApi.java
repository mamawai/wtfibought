package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesOrderProtectionRequest;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.dto.PositionHistoryDTO;
import com.mawai.wiibcommon.entity.UserLedger;
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

    @PostExchange("/{userId}/stop-loss")
    Result<Void> setStopLoss(@PathVariable Long userId, @RequestBody FuturesStopLossRequest request);

    @PostExchange("/{userId}/take-profit")
    Result<Void> setTakeProfit(@PathVariable Long userId, @RequestBody FuturesTakeProfitRequest request);

    @PostExchange("/{userId}/cancel/{orderId}")
    Result<FuturesOrderResponse> cancel(@PathVariable Long userId, @PathVariable Long orderId);

    @GetExchange("/{userId}/order/{orderId}")
    Result<FuturesOrderResponse> order(@PathVariable Long userId, @PathVariable Long orderId);

    @PostExchange("/{userId}/order/{orderId}/protection")
    Result<Void> setOrderProtection(@PathVariable Long userId, @PathVariable Long orderId,
                                    @RequestBody FuturesOrderProtectionRequest request);

    @GetExchange("/{userId}/orders")
    Result<List<FuturesOrderResponse>> groupOrders(@PathVariable Long userId, @RequestParam String groupId);

    @GetExchange("/brackets")
    Result<Map<String, List<Bracket>>> brackets(@RequestParam List<String> symbols);

    record Bracket(int tier, BigDecimal notionalFloor, BigDecimal notionalCap,
                   int maxLeverage, BigDecimal mmr, BigDecimal maintAmount) {}

    @GetExchange("/{userId}/pending-orders")
    Result<List<FuturesOrderResponse>> pendingOrders(@PathVariable Long userId, @RequestParam(required = false) String symbol);

    /** symbol 传 null 即不带查询参数 = 全 symbol 持仓（策略账户监控页用）。 */
    @GetExchange("/{userId}/positions")
    Result<List<FuturesPositionDTO>> positions(@PathVariable Long userId, @RequestParam(required = false) String symbol);

    @GetExchange("/{userId}/closed-positions")
    Result<List<FuturesPositionDTO>> closedPositions(@PathVariable Long userId, @RequestParam int limit);

    @GetExchange("/{userId}/balance")
    Result<Map<String, Object>> balance(@PathVariable Long userId);

    /** beforeId 传 null 即不带查询参数 = 最新一页。 */
    @GetExchange("/{userId}/ledger")
    Result<List<UserLedger>> ledger(@PathVariable Long userId, @RequestParam(required = false) Long beforeId,
                                    @RequestParam int limit);

    /** 已平仓位的历史，一行一笔完整仓位，带每一次成交；新平的在前 */
    @GetExchange("/{userId}/position-history")
    Result<Paged<PositionHistoryDTO>> positionHistory(@PathVariable Long userId, @RequestParam int pageNum,
                                                      @RequestParam int pageSize);

    /** sim 分页结果里用得上的那几项：这一页的行、总行数、每页几行、第几页、共几页 */
    record Paged<T>(List<T> records, long total, long size, long current, long pages) {}

    @PostExchange("/ensure-account")
    Result<Map<String, Object>> ensureAccount(@RequestParam String username, @RequestParam BigDecimal initialBalance);

    @PostExchange("/delete-account")
    Result<Void> deleteAccount(@RequestParam String username);
}
