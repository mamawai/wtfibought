package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionBuyRequest;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * agent → sim 预测市场 internal API 客户端（Jev 预测员下注通道）。
 * 业务失败（余额不足 / 回合已锁 / 无价）与传输失败都抛异常，
 * 拆壳在 {@link SimTradeClient#unwrap}，业务错误是 {@link SimTradeClient.SimBizException}。
 */
@Component
public class SimPredictionClient {

    private final SimPredictionApi api;

    public SimPredictionClient(@Value("${sim.internal.base-url:http://localhost:8080}") String baseUrl,
                               @Value("${internal.api.token:}") String token) {
        this.api = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(SimInternalRestClient.build(baseUrl, token)))
                .build()
                .createClient(SimPredictionApi.class);
    }

    /** 幂等建只有游戏钱包的机器人账户，返回 userId */
    public Long ensureAccount(String username, BigDecimal initialGameBalance) {
        Map<String, Object> data = SimTradeClient.unwrap(api.ensureAccount(username, initialGameBalance));
        return Long.valueOf(String.valueOf(data.get("userId")));
    }

    /** 按当前盘口卖价买入 side（UP/DOWN），amount 是本金（USDT），手续费另扣 */
    public PredictionBetResponse buy(Long userId, String side, BigDecimal amount) {
        PredictionBuyRequest req = new PredictionBuyRequest();
        req.setSide(side);
        req.setAmount(amount);
        return SimTradeClient.unwrap(api.buy(userId, req));
    }

    /** 按当前盘口买价卖出；contracts 传 null 全卖 */
    public PredictionBetResponse sell(Long userId, Long betId, BigDecimal contracts) {
        return SimTradeClient.unwrap(api.sell(userId, betId, contracts));
    }

    /** 最近注单，新的在前 */
    public List<PredictionBetResponse> recentBets(Long userId, int limit) {
        return SimTradeClient.unwrap(api.bets(userId, limit));
    }

    public BigDecimal gameBalance(Long userId) {
        return SimTradeClient.unwrap(api.gameBalance(userId));
    }

    /** 某窗口的回合；null＝sim 里没有这回合 */
    public PredictionRoundResponse round(long windowStart) {
        return SimTradeClient.unwrap(api.round(windowStart));
    }
}
