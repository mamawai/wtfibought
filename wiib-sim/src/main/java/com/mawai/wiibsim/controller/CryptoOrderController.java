package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.CryptoPositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "加密货币交易接口")
@RestController
@RequestMapping("/api/crypto/order")
@RequiredArgsConstructor
public class CryptoOrderController {

    private final CryptoOrderService cryptoOrderService;
    private final CryptoPositionService cryptoPositionService;

    @PostMapping("/buy")
    @Operation(summary = "买入（市价/限价）")
    public Result<CryptoOrderResponse> buy(@RequestBody CryptoOrderRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoOrderService.buy(userId, request));
    }

    @PostMapping("/sell")
    @Operation(summary = "卖出（市价/限价）")
    public Result<CryptoOrderResponse> sell(@RequestBody CryptoOrderRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoOrderService.sell(userId, request));
    }

    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<CryptoOrderResponse> cancel(@PathVariable Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoOrderService.cancel(userId, orderId));
    }

    @GetMapping("/list")
    @Operation(summary = "查询订单列表")
    public Result<IPage<CryptoOrderResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoOrderService.getUserOrders(userId, status, pageNum, pageSize, symbol));
    }

    @GetMapping("/position")
    @Operation(summary = "查询指定币种持仓")
    public Result<CryptoPosition> position(@RequestParam(defaultValue = "BTCUSDT") String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoPositionService.findByUserAndSymbol(userId, symbol));
    }

    @GetMapping("/positions")
    @Operation(summary = "查询所有币种持仓")
    public Result<List<CryptoPosition>> positions() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(cryptoPositionService.getUserPositions(userId));
    }

    @GetMapping("/live")
    @Operation(summary = "最新成交-匿名")
    public Result<List<CryptoOrderResponse>> live() {
        return Result.ok(cryptoOrderService.getLatestOrders());
    }
}
