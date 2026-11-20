package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.KlineCacheService;
import cn.dev33.satoken.stp.StpUtil;
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

    private final CacheService cacheService;
    private final KlineCacheService klineCacheService;

    @GetMapping("/klines")
    @Operation(summary = "获取K线数据（代理Binance REST，Redis短TTL缓存）")
    public String klines(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        // 翻历史（带 endTime）要登录：最新一屏全站共用一个缓存键，endTime 每个值都是新键，游客拿它能穿透缓存打币安
        if (endTime != null) StpUtil.checkLogin();
        return klineCacheService.spotKlines(symbol, interval, limit, endTime);
    }

    @GetMapping("/price")
    @Operation(summary = "获取最新价格（Redis缓存）")
    public Result<Map<String, String>> price(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        BigDecimal price = cacheService.getCryptoPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return Result.ok(Map.of("price", price.toPlainString()));
    }
}
