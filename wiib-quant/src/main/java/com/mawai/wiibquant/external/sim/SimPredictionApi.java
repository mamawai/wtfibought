package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionBuyRequest;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
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
 * agent → sim 预测市场 internal API 契约，与 sim 的 InternalPredictionController 一一对应。
 * 返回 Result 原壳，拆壳转异常在 {@link SimPredictionClient}（同 {@link SimTradeApi} 的约定）。
 */
@HttpExchange("/internal/prediction")
public interface SimPredictionApi {

    @PostExchange("/ensure-account")
    Result<Map<String, Object>> ensureAccount(@RequestParam String username, @RequestParam BigDecimal initialGameBalance);

    @PostExchange("/{userId}/buy")
    Result<PredictionBetResponse> buy(@PathVariable Long userId, @RequestBody PredictionBuyRequest request);

    @PostExchange("/{userId}/sell/{betId}")
    Result<PredictionBetResponse> sell(@PathVariable Long userId, @PathVariable Long betId,
                                       @RequestParam(required = false) BigDecimal contracts);

    @GetExchange("/{userId}/bets")
    Result<List<PredictionBetResponse>> bets(@PathVariable Long userId, @RequestParam int limit);

    @GetExchange("/{userId}/game-balance")
    Result<BigDecimal> gameBalance(@PathVariable Long userId);

    @GetExchange("/round/{windowStart}")
    Result<PredictionRoundResponse> round(@PathVariable long windowStart);
}
