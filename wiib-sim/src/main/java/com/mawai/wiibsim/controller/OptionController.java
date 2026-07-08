package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.OptionContract;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "期权接口")
@RestController
@RequestMapping("/api/option")
@RequiredArgsConstructor
public class OptionController {

    private final OptionContractService contractService;
    private final OptionOrderService orderService;
    private final OptionPositionService positionService;
    private final OptionPricingService pricingService;
    private final TradingConfig tradingConfig;

    @GetMapping("/chain/{stockId}")
    @Operation(summary = "获取期权链")
    public Result<List<OptionChainItemDTO>> getOptionChain(@PathVariable Long stockId) {
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        List<OptionContract> contracts = contractService.getActiveContracts(stockId);

        List<OptionChainItemDTO> result = contracts.stream().map(c -> {
            OptionChainItemDTO dto = new OptionChainItemDTO();
            dto.setContractId(c.getId());
            dto.setStockId(c.getStockId());
            dto.setOptionType(c.getOptionType());
            dto.setStrike(c.getStrike());
            dto.setExpireAt(c.getExpireAt());
            return dto;
        }).toList();

        return Result.ok(result);
    }

    @GetMapping("/quote/{contractId}")
    @Operation(summary = "获取期权实时报价")
    public Result<OptionQuoteDTO> getQuote(@PathVariable Long contractId) {
        return Result.ok(pricingService.getQuote(contractId));
    }

    @PostMapping("/buy")
    @Operation(summary = "买入开仓（BTO）")
    public Result<OptionOrderResultDTO> buyToOpen(@CurrentUserId Long userId, @RequestBody OptionOrderRequest request) {
        OptionOrderResultDTO result = orderService.buyToOpen(
                userId, request.getContractId(), request.getQuantity());
        return Result.ok(result);
    }

    @PostMapping("/sell")
    @Operation(summary = "卖出平仓（STC）")
    public Result<OptionOrderResultDTO> sellToClose(@CurrentUserId Long userId, @RequestBody OptionOrderRequest request) {
        OptionOrderResultDTO result = orderService.sellToClose(
                userId, request.getContractId(), request.getQuantity());
        return Result.ok(result);
    }

    @GetMapping("/positions")
    @Operation(summary = "查询期权持仓")
    public Result<List<OptionPositionDTO>> getPositions(@CurrentUserId Long userId) {
        return Result.ok(positionService.getUserPositions(userId));
    }

    @GetMapping("/orders")
    @Operation(summary = "查询期权订单")
    public Result<IPage<OptionOrderDTO>> getOrders(
            @CurrentUserId Long userId,
            @Parameter(description = "订单状态：PENDING/FILLED/CANCELLED/EXPIRED（不传则查询全部）", example = "FILLED")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码（从1开始）", example = "1")
            @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(orderService.getUserOrders(userId, status, pageNum, pageSize));
    }

}
