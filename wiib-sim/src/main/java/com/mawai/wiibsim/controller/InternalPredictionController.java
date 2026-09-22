package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionBuyRequest;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.PredictionService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 预测市场 internal API（agent 的 Jev 预测员专用通道）。
 * <p>下注/卖出走与真人同一个 {@link PredictionService}：同账本、同费率、同回合锁；
 * 账户是只有游戏钱包的机器人账户（{@link UserService#ensureGameAccount}）。
 * 鉴权走 {@code InternalApiFilter} 的 X-Internal-Token（/internal/** 已在 SaToken 放行）。
 */
@RestController
@RequestMapping("/internal/prediction")
@RequiredArgsConstructor
public class InternalPredictionController {

    private final PredictionService predictionService;
    private final UserService userService;

    /** 幂等建游戏机器人账户：交易余额 0，初始资金进游戏钱包 */
    @PostMapping("/ensure-account")
    public Result<Map<String, Object>> ensureAccount(@RequestParam String username,
                                                     @RequestParam BigDecimal initialGameBalance) {
        User user = userService.ensureGameAccount(username, initialGameBalance);
        return Result.ok(Map.of("userId", user.getId(), "gameBalance", user.getGameBalance()));
    }

    @PostMapping("/{userId}/buy")
    public Result<PredictionBetResponse> buy(@PathVariable Long userId, @RequestBody PredictionBuyRequest request) {
        return Result.ok(predictionService.buy(userId, request));
    }

    /** contracts 不传＝全卖 */
    @PostMapping("/{userId}/sell/{betId}")
    public Result<PredictionBetResponse> sell(@PathVariable Long userId, @PathVariable Long betId,
                                              @RequestParam(required = false) BigDecimal contracts) {
        return Result.ok(predictionService.sell(userId, betId, contracts));
    }

    /** 最近注单，新的在前 */
    @GetMapping("/{userId}/bets")
    public Result<List<PredictionBetResponse>> bets(@PathVariable Long userId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(predictionService.getUserBets(userId, 1, limit).getRecords());
    }

    @GetMapping("/{userId}/game-balance")
    public Result<BigDecimal> gameBalance(@PathVariable Long userId) {
        return Result.ok(userService.getGameBalance(userId));
    }

    /** 某窗口的回合（状态/结果/开收盘价）；没有这回合 data 为 null */
    @GetMapping("/round/{windowStart}")
    public Result<PredictionRoundResponse> round(@PathVariable long windowStart) {
        return Result.ok(predictionService.getRound(windowStart));
    }
}
