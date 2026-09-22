package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * quant → sim 合约交易 internal API 客户端（SimInternalClient 同款配置：同机 localhost、
 * X-Internal-Token 鉴权、短超时快速失败），DTO 走 wiib-common 与 sim 编译解耦。
 *
 * <p>端点定义在 {@link SimTradeApi}（Spring 6 HTTP Interface 声明式接口），本类做三件事：
 * 构造时装配 RestClient 并生成代理；每方法 unwrap 拆 Result 壳——业务失败（sim 返回
 * Result.fail，如余额不足/止损价非法/订单不可撤）与传输失败同样抛异常，
 * 由 {@link com.mawai.wiibquant.strategy.execution.SimExecutionService} 按操作粒度捕获并保持状态机安全；
 * 以及 {@link #describe} 把失败按调用方那门语言成文。</p>
 *
 * <p><b>不给 sim 传 X-Lang</b>：{@link SimBizException} 带回来的是错误码，话由调用方自己查词表。
 * 这条链上真正知道该用哪门语言的只有调用方——trader 唤醒要跟 trader 主人的 AgentLang，
 * 而唤醒的图跑在另一条虚拟线程上，请求线程那份 {@code RequestLang} 根本传不过去；
 * 同一笔拒因还会被别的语言的人在竞技场时间线上读到。</p>
 */
@Component
public class SimTradeClient {

    private final SimTradeApi api;

    public SimTradeClient(@Value("${sim.internal.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${internal.api.token:}") String token) {
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(SimInternalRestClient.build(baseUrl, token)))
                .build()
                .createClient(SimTradeApi.class);
    }

    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        return unwrap(api.open(userId, request));
    }

    public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
        return unwrap(api.close(userId, request));
    }

    public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
        return unwrap(api.cancel(userId, orderId));
    }

    /** 改持仓止损单（sim 侧整组替换语义，与用户端同一入口）。 */
    public void setStopLoss(Long userId, com.mawai.wiibcommon.dto.FuturesStopLossRequest request) {
        unwrap(api.setStopLoss(userId, request));
    }

    /** 改持仓止盈单（整组替换语义同止损；AI Trader 有利方向移动目标位用）。 */
    public void setTakeProfit(Long userId, com.mawai.wiibcommon.dto.FuturesTakeProfitRequest request) {
        unwrap(api.setTakeProfit(userId, request));
    }

    public FuturesOrderResponse getOrder(Long userId, Long orderId) {
        return unwrap(api.order(userId, orderId));
    }

    public List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol) {
        return unwrap(api.pendingOrders(userId, symbol));
    }

    public List<FuturesPositionDTO> getPositions(Long userId, String symbol) {
        return unwrap(api.positions(userId, symbol));
    }

    /** 全 symbol 持仓（策略账户监控页用，不带 symbol 过滤）。 */
    public List<FuturesPositionDTO> getAllPositions(Long userId) {
        return unwrap(api.positions(userId, null));
    }

    /** 已平/强平仓位历史，updatedAt 倒序（交易记录 + 收益曲线数据源）。 */
    public List<FuturesPositionDTO> getClosedPositions(Long userId, int limit) {
        return unwrap(api.closedPositions(userId, limit));
    }

    public BigDecimal getBalance(Long userId) {
        Map<String, Object> data = unwrap(api.balance(userId));
        return new BigDecimal(String.valueOf(data.get("balance")));
    }

    /** 余额明细（balance + frozenBalance），AI Trader 权益计算用。 */
    public Map<String, Object> getBalanceDetail(Long userId) {
        return unwrap(api.balance(userId));
    }

    /** 幂等创建量化账户，返回 userId。 */
    public Long ensureAccount(String username, BigDecimal initialBalance) {
        Map<String, Object> data = unwrap(api.ensureAccount(username, initialBalance));
        return Long.valueOf(String.valueOf(data.get("userId")));
    }

    /** 量化子账户销户（AI Trader 过期轮次清理）：sim 侧幂等，账户不存在也算成功。 */
    public void deleteAccount(String username) {
        unwrap(api.deleteAccount(username));
    }

    /**
     * 传输层失败（连不上/读超时）：这笔单在 sim 那边是死是活不知道，
     * 调用方该拿同一个 clientRequestId 重发确认，而不是当失败重下。
     */
    public static boolean isTransportFailure(Throwable e) {
        return e instanceof ResourceAccessException;
    }

    /**
     * sim 幂等占位回的"处理中"：同一笔还在跑，结果同样未知，同键再来即可。
     * 只认 1106 不认 1105——1105 是 sim 侧抢 Redis 锁失败，那种是确定没成交，当"未知"处理
     * 会让模型收到一句"可能已经成交、别重下"，白丢一次交易。
     */
    public static boolean isProcessing(Throwable e) {
        return e instanceof SimBizException sim && sim.code() == ErrorCode.ORDER_IN_FLIGHT.getCode();
    }

    /**
     * 异常成文给人和模型看：sim 业务失败按码查词表跟给定语言，其余照原样
     * （传输失败、上游原文、代码异常都没有码可查，那些是诊断信息不是给用户的话）。
     * <p>
     * 静态：调用方常把本类换成 mock，成了实例方法就会被静默返回 null，而这句话是要落库展示的。
     */
    public static String describe(Throwable e, MessageCatalog messages, AgentLang lang) {
        if (e instanceof SimBizException sim) {
            return sim.render(messages, lang);
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * sim 侧业务失败（一律 200 + Result.fail，不靠 HTTP 状态码）。
     * <p>
     * 带的是<b>码</b>不是话：成文要用哪门语言只有调用方知道。{@code getMessage()} 是给日志与堆栈用的。
     */
    public static class SimBizException extends RuntimeException {
        private final int code;
        /** sim 那边按它自己那门语言渲染好的原话，只在码查不回词条时兜底 */
        private final String simMsg;

        public SimBizException(int code, String simMsg) {
            super("sim code=" + code + ": " + simMsg);
            this.code = code;
            this.simMsg = simMsg;
        }

        public int code() {
            return code;
        }

        /** 与 {@code BizException.render} 同一套路，只是语言得显式给——这异常常抛在没有请求语言的线程上。 */
        public String render(MessageCatalog messages, AgentLang lang) {
            ErrorCode ec = ErrorCode.of(code);
            // 只有 1000+ 的业务码查得回词条；1000 以下那批（400/500）在 sim 侧多半是
            // Result.fail(自己写的话)，那句话本身就是全部信息，退回它比渲染成"系统错误"强
            return ec != null && code >= 1000 ? messages.get(lang, ec.getMsgKey()) : simMsg;
        }
    }

    /** 拆 Result 壳：sim 业务失败统一转异常抛出。包内其他 sim 客户端共用 */
    static <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new IllegalStateException("sim internal api 空响应");
        }
        if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw new SimBizException(result.getCode(), result.getMsg());
        }
        return result.getData();
    }
}
