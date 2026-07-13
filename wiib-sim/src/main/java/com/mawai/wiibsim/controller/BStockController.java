package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.dto.BStockDTO;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.BStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * bStock 代币化美股接口。行情走真实 Binance 现货（K线代理 REST，价来自 feed→Redis）。
 */
@Tag(name = "bStock 代币化美股接口")
@RestController
@RequestMapping("/api/bstock")
@RequiredArgsConstructor
public class BStockController {

    private final BStockService bStockService;
    private final BinanceRestClient binanceRestClient;

    @GetMapping("/list")
    @Operation(summary = "全部上架 bStock（含实时价 + 24h 涨跌）")
    public Result<List<BStockDTO>> list() {
        return Result.ok(bStockService.listAll());
    }

    @GetMapping("/price")
    @Operation(summary = "最新价（Redis 优先，回退 REST）")
    public Result<BigDecimal> price(@RequestParam String symbol) {
        return Result.ok(bStockService.price(symbol));
    }

    @GetMapping("/klines")
    @Operation(summary = "K线（代理 Binance 现货，默认 5m）")
    public String klines(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        return binanceRestClient.getKlinesLight(symbol, interval, limit, endTime);
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "单只详情（含公司信息 + 24h 行情）")
    public Result<BStockDTO> detail(@PathVariable String symbol) {
        return Result.ok(bStockService.detail(symbol));
    }
}
