package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * quant → sim 合约交易 internal API 客户端（SimInternalClient 同款配置：同机 localhost、
 * X-Internal-Token 鉴权、短超时快速失败），DTO 走 wiib-common 与 sim 编译解耦。
 *
 * <p>业务失败（sim 返回 Result.fail，如余额不足/止损价非法/订单不可撤）与传输失败同样抛异常，
 * 由 {@link SimExecutionService} 按操作粒度捕获并保持状态机安全。</p>
 */
@Slf4j
@Component
public class SimTradeClient {

    private final RestClient restClient;

    public SimTradeClient(@Value("${sim.internal.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${internal.api.token:}") String token) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token)
                .requestFactory(factory)
                .build();
    }

    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        return unwrap(restClient.post().uri("/internal/futures/{u}/open", userId)
                .body(request).retrieve()
                .body(new ParameterizedTypeReference<Result<FuturesOrderResponse>>() {}));
    }

    public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
        return unwrap(restClient.post().uri("/internal/futures/{u}/close", userId)
                .body(request).retrieve()
                .body(new ParameterizedTypeReference<Result<FuturesOrderResponse>>() {}));
    }

    public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
        return unwrap(restClient.post().uri("/internal/futures/{u}/cancel/{o}", userId, orderId)
                .retrieve().body(new ParameterizedTypeReference<Result<FuturesOrderResponse>>() {}));
    }

    public FuturesOrderResponse getOrder(Long userId, Long orderId) {
        return unwrap(restClient.get().uri("/internal/futures/{u}/order/{o}", userId, orderId)
                .retrieve().body(new ParameterizedTypeReference<Result<FuturesOrderResponse>>() {}));
    }

    public List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol) {
        return unwrap(restClient.get().uri("/internal/futures/{u}/pending-orders?symbol={s}", userId, symbol)
                .retrieve().body(new ParameterizedTypeReference<Result<List<FuturesOrderResponse>>>() {}));
    }

    public List<FuturesPositionDTO> getPositions(Long userId, String symbol) {
        return unwrap(restClient.get().uri("/internal/futures/{u}/positions?symbol={s}", userId, symbol)
                .retrieve().body(new ParameterizedTypeReference<Result<List<FuturesPositionDTO>>>() {}));
    }

    /** 全 symbol 持仓（策略账户监控页用，不带 symbol 过滤）。 */
    public List<FuturesPositionDTO> getAllPositions(Long userId) {
        return unwrap(restClient.get().uri("/internal/futures/{u}/positions", userId)
                .retrieve().body(new ParameterizedTypeReference<Result<List<FuturesPositionDTO>>>() {}));
    }

    /** 已平/强平仓位历史，updatedAt 倒序（交易记录 + 收益曲线数据源）。 */
    public List<FuturesPositionDTO> getClosedPositions(Long userId, int limit) {
        return unwrap(restClient.get().uri("/internal/futures/{u}/closed-positions?limit={l}", userId, limit)
                .retrieve().body(new ParameterizedTypeReference<Result<List<FuturesPositionDTO>>>() {}));
    }

    public BigDecimal getBalance(Long userId) {
        Map<String, Object> data = unwrap(restClient.get().uri("/internal/futures/{u}/balance", userId)
                .retrieve().body(new ParameterizedTypeReference<Result<Map<String, Object>>>() {}));
        return new BigDecimal(String.valueOf(data.get("balance")));
    }

    /** 幂等创建量化账户，返回 userId。 */
    public Long ensureAccount(String username, BigDecimal initialBalance) {
        Map<String, Object> data = unwrap(restClient.post()
                .uri("/internal/futures/ensure-account?username={u}&initialBalance={b}", username, initialBalance)
                .retrieve().body(new ParameterizedTypeReference<Result<Map<String, Object>>>() {}));
        return Long.valueOf(String.valueOf(data.get("userId")));
    }

    /** 拆 Result 壳：sim 业务失败统一转异常抛出（sim 异常一律 200+Result.fail，不靠 HTTP 状态码）。 */
    private static <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new IllegalStateException("sim internal api 空响应");
        }
        if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw new IllegalStateException("sim api 业务失败 code=" + result.getCode() + " msg=" + result.getMsg());
        }
        return result.getData();
    }
}
