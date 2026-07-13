package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.CryptoPositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * bStock 交易接口：股票专属入口，**复用现货引擎**——买卖/杠杆借款/利息全走 crypto 现货那套，
 * 仅卖出为瞬时结算（引擎按符号自动区分）。持仓即 crypto_position 中属 bStock 的部分。
 */
@Tag(name = "bStock 交易接口")
@RestController
@RequestMapping("/api/bstock/order")
@RequiredArgsConstructor
public class BStockOrderController {

    private final CryptoOrderService cryptoOrderService;
    private final CryptoPositionService cryptoPositionService;
    private final BStockService bStockService;

    /** 拒绝非 bStock 符号走本入口，避免与 crypto 交易混用 */
    private void requireBStock(String symbol) {
        if (!bStockService.isBStockSymbol(symbol)) throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
    }

    @PostMapping("/buy")
    @Operation(summary = "买入（市价/限价，支持杠杆借款）")
    public Result<CryptoOrderResponse> buy(@CurrentUserId Long userId, @RequestBody CryptoOrderRequest request) {
        requireBStock(request.getSymbol());
        return Result.ok(cryptoOrderService.buy(userId, request));
    }

    @PostMapping("/sell")
    @Operation(summary = "卖出（市价/限价，瞬时到账）")
    public Result<CryptoOrderResponse> sell(@CurrentUserId Long userId, @RequestBody CryptoOrderRequest request) {
        requireBStock(request.getSymbol());
        return Result.ok(cryptoOrderService.sell(userId, request));
    }

    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<CryptoOrderResponse> cancel(@CurrentUserId Long userId, @PathVariable Long orderId) {
        return Result.ok(cryptoOrderService.cancel(userId, orderId));
    }

    @GetMapping("/list")
    @Operation(summary = "查询订单列表")
    public Result<IPage<CryptoOrderResponse>> list(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam String symbol) {
        return Result.ok(cryptoOrderService.getUserOrders(userId, status, pageNum, pageSize, symbol));
    }

    @GetMapping("/positions")
    @Operation(summary = "查询全部 bStock 持仓（crypto_position 中属 bStock 的部分）")
    public Result<List<CryptoPosition>> positions(@CurrentUserId Long userId) {
        List<CryptoPosition> bstocks = cryptoPositionService.getUserPositions(userId).stream()
                .filter(p -> bStockService.isBStockSymbol(p.getSymbol()))
                .toList();
        return Result.ok(bstocks);
    }
}
