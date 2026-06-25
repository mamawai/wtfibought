package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibcommon.cache.CacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@Tag(name = "加密货币行情接口")
@RestController
@RequestMapping("/api/crypto")
@RequiredArgsConstructor
public class CryptoMarketController {

    private final BinanceRestClient binanceRestClient;
    private final CacheService cacheService;

    @GetMapping("/klines")
    @Operation(summary = "获取K线数据（代理Binance REST）")
    public String klines(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        return binanceRestClient.getKlinesLight(symbol, interval, limit, endTime);
    }

    @GetMapping("/price")
    @Operation(summary = "获取最新价格（Redis缓存）")
    public Result<Map<String, String>> price(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        BigDecimal price = cacheService.getCryptoPrice(symbol);
        if (price == null) {
            String json = binanceRestClient.getTickerPrice(symbol);
            return Result.ok(Map.of("raw", json));
        }
        return Result.ok(Map.of("price", price.toPlainString()));
    }
}
