package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.OrderRequest;
import com.mawai.wiibcommon.dto.OrderResponse;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 订单Controller
 */
@Tag(name = "订单接口")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/buy")
    @Operation(summary = "买入下单（市价/限价）")
    public Result<OrderResponse> buy(@CurrentUserId Long userId, @RequestBody OrderRequest request) {
        return Result.ok(orderService.buy(userId, request));
    }

    @PostMapping("/sell")
    @Operation(summary = "卖出下单（市价/限价）")
    public Result<OrderResponse> sell(@CurrentUserId Long userId, @RequestBody OrderRequest request) {
        return Result.ok(orderService.sell(userId, request));
    }

    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<OrderResponse> cancel(@CurrentUserId Long userId, @PathVariable Long orderId) {
        return Result.ok(orderService.cancel(userId, orderId));
    }

    @GetMapping("/list")
    @Operation(summary = "查询订单列表")
    public Result<IPage<OrderResponse>> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(orderService.getUserOrders(userId, status, pageNum, pageSize));
    }

    @GetMapping("/live")
    @Operation(summary = "查询用户实时订单列表最新的20个-匿名展示")
    public Result<List<OrderResponse>> live() {
        return Result.ok(orderService.getLatestOrders());
    }
}
