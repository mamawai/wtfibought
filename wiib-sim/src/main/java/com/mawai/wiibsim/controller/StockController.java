package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.DayTickDTO;
import com.mawai.wiibcommon.dto.KlineDTO;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.MarketDataService;
import com.mawai.wiibsim.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票Controller
 */
@Tag(name = "股票接口")
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final MarketDataService marketDataService;

    /**
     * 获取所有股票列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有股票列表")
    public Result<List<StockDTO>> listAllStocks() {
        List<StockDTO> stocks = stockService.listAllStocks();
        return Result.ok(stocks);
    }

    /**
     * 分页查询股票列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询股票列表")
    public Result<IPage<StockDTO>> listStocksByPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        IPage<StockDTO> page = stockService.listStocksByPage(pageNum, pageSize);
        return Result.ok(page);
    }

    /**
     * 获取股票详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取股票详情")
    public Result<StockDTO> getStockDetail(@PathVariable Long id) {
        StockDTO stock = stockService.getStockDetail(id);
        return Result.ok(stock);
    }

    /**
     * 获取涨幅榜
     */
    @GetMapping("/gainers")
    @Operation(summary = "获取涨幅榜")
    public Result<List<StockDTO>> getTopGainers(@RequestParam(defaultValue = "10") int limit) {
        List<StockDTO> gainers = stockService.getTopGainers(limit);
        return Result.ok(gainers);
    }

    /**
     * 获取跌幅榜
     */
    @GetMapping("/losers")
    @Operation(summary = "获取跌幅榜")
    public Result<List<StockDTO>> getTopLosers(@RequestParam(defaultValue = "10") int limit) {
        List<StockDTO> losers = stockService.getTopLosers(limit);
        return Result.ok(losers);
    }

    /**
     * 获取当日分时数据
     */
    @GetMapping("/{stockId}/ticks")
    @Operation(summary = "获取当日分时数据")
    public Result<List<DayTickDTO>> getDayTicks(@PathVariable Long stockId) {
        List<DayTickDTO> ticks = marketDataService.getDayTicks(stockId);
        return Result.ok(ticks);
    }

    @GetMapping("/{stockId}/history-ticks")
    @Operation(summary = "获取历史某天分时数据")
    public Result<List<DayTickDTO>> getHistoryDayTicks(
            @PathVariable Long stockId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<DayTickDTO> ticks = marketDataService.getHistoryDayTicks(stockId, date);
        return Result.ok(ticks);
    }

    @GetMapping("/{stockId}/kline")
    @Operation(summary = "获取日K线数据")
    public Result<List<KlineDTO>> getKlineData(
            @PathVariable Long stockId,
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(marketDataService.getKlineData(stockId, days));
    }

}
