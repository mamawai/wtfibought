package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.AccountResetService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.InternalOrderIdempotency;
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
 * 合约交易 internal API（quant 策略执行专用通道）。
 *
 * <p>quant 的 SimExecutionService 经此在专用量化账户上开/平/挂单；成交由 sim 既有撮合链
 * （feed 价格事件驱动限价/SL/TP/强平）完成——策略决策价与撮合价同源，无两套行情失真。
 * 鉴权走 {@code InternalApiFilter} 的 X-Internal-Token（/internal/** 已在 SaToken 放行）。
 * 量化账户与真人用户同表同账本、资金独立，全部风控/费率/资金费规则一视同仁。</p>
 */
@RestController
@RequestMapping("/internal/futures")
@RequiredArgsConstructor
public class InternalFuturesTradeController {

    private final FuturesTradingService tradingService;
    private final FuturesRiskService riskService;
    private final UserService userService;
    private final AccountResetService accountResetService;
    private final InternalOrderIdempotency idempotency;
    private final CrossMarginService crossMarginService;

    /** 带 clientRequestId 就走幂等（调用方超时重发不会双仓），不带＝原样直发 */
    @PostMapping("/{userId}/open")
    public Result<FuturesOrderResponse> open(@PathVariable Long userId, @RequestBody FuturesOpenRequest request) {
        return idempotency.execute(userId, request.getClientRequestId(),
                () -> tradingService.openPosition(userId, request));
    }

    @PostMapping("/{userId}/close")
    public Result<FuturesOrderResponse> close(@PathVariable Long userId, @RequestBody FuturesCloseRequest request) {
        return idempotency.execute(userId, request.getClientRequestId(),
                () -> tradingService.closePosition(userId, request));
    }

    /** 修改止损（quant 持仓管理：TURTLE 2R 保本等）。 */
    @PostMapping("/{userId}/stop-loss")
    public Result<Void> setStopLoss(@PathVariable Long userId, @RequestBody FuturesStopLossRequest request) {
        riskService.setStopLoss(userId, request);
        return Result.ok();
    }

    /** 修改止盈（AI Trader 让利润奔跑：有利方向移动目标位）。 */
    @PostMapping("/{userId}/take-profit")
    public Result<Void> setTakeProfit(@PathVariable Long userId, @RequestBody FuturesTakeProfitRequest request) {
        riskService.setTakeProfit(userId, request);
        return Result.ok();
    }

    @PostMapping("/{userId}/cancel/{orderId}")
    public Result<FuturesOrderResponse> cancel(@PathVariable Long userId, @PathVariable Long orderId) {
        return Result.ok(tradingService.cancelOrder(userId, orderId));
    }

    @GetMapping("/{userId}/order/{orderId}")
    public Result<FuturesOrderResponse> getOrder(@PathVariable Long userId, @PathVariable Long orderId) {
        return Result.ok(tradingService.getOrder(userId, orderId));
    }

    @GetMapping("/{userId}/pending-orders")
    public Result<List<FuturesOrderResponse>> pendingOrders(@PathVariable Long userId,
                                                            @RequestParam(required = false) String symbol) {
        return Result.ok(tradingService.getPendingOrders(userId, symbol));
    }

    @GetMapping("/{userId}/positions")
    public Result<List<FuturesPositionDTO>> positions(@PathVariable Long userId,
                                                      @RequestParam(required = false) String symbol) {
        return Result.ok(tradingService.getUserPositions(userId, symbol));
    }

    @GetMapping("/{userId}/closed-positions")
    public Result<List<FuturesPositionDTO>> closedPositions(@PathVariable Long userId,
                                                            @RequestParam(required = false) String symbol,
                                                            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(tradingService.getClosedPositions(userId, symbol, limit));
    }

    @GetMapping("/{userId}/balance")
    public Result<Map<String, Object>> balance(@PathVariable Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        // available 是全仓账本的下单额度口径（净值 − 占用保证金 − 挂单占用），和开仓校验用的同一个数
        return Result.ok(Map.of("userId", user.getId(), "balance", user.getBalance(),
                "frozenBalance", user.getFrozenBalance() == null ? BigDecimal.ZERO : user.getFrozenBalance(),
                "available", crossMarginService.snapshot(userId).available()));
    }

    /**
     * 幂等创建量化机器人账户：username 已存在直接返回，不重复入金。
     * 建号 + 补记初始资金的事务边界在 UserService 那层（同生共死，理由见该方法注释）。
     */
    @PostMapping("/ensure-account")
    public Result<Map<String, Object>> ensureAccount(@RequestParam String username,
                                                     @RequestParam BigDecimal initialBalance) {
        User user = userService.ensureQuantAccount(username, initialBalance);
        return Result.ok(Map.of("userId", user.getId(), "balance", user.getBalance()));
    }

    /**
     * 量化子账户销户（AI Trader 过期轮次清理）：交易数据+账户行一并删；账户不存在视为成功（幂等）。
     * 只认 ai_trader_ 前缀的量化建号，护栏在 {@link AccountResetService#deleteQuantAccount}。
     */
    @PostMapping("/delete-account")
    public Result<Void> deleteAccount(@RequestParam String username) {
        accountResetService.deleteQuantAccount(username);
        return Result.ok();
    }
}
