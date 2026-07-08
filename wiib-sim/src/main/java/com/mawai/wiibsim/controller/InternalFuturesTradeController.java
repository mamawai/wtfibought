package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RestController
@RequestMapping("/internal/futures")
@RequiredArgsConstructor
public class InternalFuturesTradeController {

    private final FuturesTradingService tradingService;
    private final UserService userService;
    private final UserMapper userMapper;

    @PostMapping("/{userId}/open")
    public Result<FuturesOrderResponse> open(@PathVariable Long userId, @RequestBody FuturesOpenRequest request) {
        return Result.ok(tradingService.openPosition(userId, request));
    }

    @PostMapping("/{userId}/close")
    public Result<FuturesOrderResponse> close(@PathVariable Long userId, @RequestBody FuturesCloseRequest request) {
        return Result.ok(tradingService.closePosition(userId, request));
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
        return Result.ok(Map.of("userId", user.getId(), "balance", user.getBalance(),
                "frozenBalance", user.getFrozenBalance() == null ? BigDecimal.ZERO : user.getFrozenBalance()));
    }

    /**
     * 幂等创建量化机器人账户：username 已存在直接返回，不重复入金。
     * 并发重复创建概率极低（quant 每策略仅首次取用时调一次），不加锁。
     */
    @PostMapping("/ensure-account")
    public Result<Map<String, Object>> ensureAccount(@RequestParam String username,
                                                     @RequestParam BigDecimal initialBalance) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            user = new User();
            user.setUsername(username);
            user.setLinuxDoId("internal:" + username);   // 机器人标识，避开 OAuth 用户命名空间
            user.setBalance(initialBalance);
            user.setFrozenBalance(BigDecimal.ZERO);
            user.setIsBankrupt(false);
            user.setBankruptCount(0);
            userMapper.insert(user);
            log.info("[InternalFutures] 创建量化账户 username={} userId={} balance={}",
                    username, user.getId(), initialBalance);
        }
        return Result.ok(Map.of("userId", user.getId(), "balance", user.getBalance()));
    }
}
