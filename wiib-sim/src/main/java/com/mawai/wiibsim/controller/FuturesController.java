package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibsim.dto.PositionHistoryDTO;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesMarginService;
import com.mawai.wiibsim.service.FuturesQueryService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.KlineCacheService;
import com.mawai.wiibsim.service.PositionHistoryService;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/futures")
@RequiredArgsConstructor
public class FuturesController {

    private final FuturesTradingService futuresTradingService;
    private final FuturesMarginService futuresMarginService;
    private final FuturesQueryService futuresQueryService;
    private final FuturesRiskService futuresRiskService;
    private final ForceOrderService forceOrderService;
    private final KlineCacheService klineCacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;
    private final TradeFilterRegistry tradeFilterRegistry;
    private final PositionHistoryService positionHistoryService;
    private final FundingRateService fundingRateService;
    private final BinanceProperties binanceProperties;

    /** 开仓：用户只能开 feed 订阅的币，别的 USDT 永续只给内部接口（交易员） */
    @PostMapping("/open")
    public Result<FuturesOrderResponse> open(@CurrentUserId Long userId, @RequestBody FuturesOpenRequest request) {
        if (!binanceProperties.getAllFuturesSymbols().contains(request.getSymbol())) {
            throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        }
        return Result.ok(futuresTradingService.openPosition(userId, request));
    }

    /** 平仓 */
    @PostMapping("/close")
    public Result<FuturesOrderResponse> close(@CurrentUserId Long userId, @RequestBody FuturesCloseRequest request) {
        return Result.ok(futuresTradingService.closePosition(userId, request));
    }

    /** 一键全平：市价平掉当前用户全部仓位，返回成功笔数与失败明细 */
    @PostMapping("/close-all")
    public Result<FuturesTradingService.CloseAllResult> closeAll(@CurrentUserId Long userId) {
        return Result.ok(futuresTradingService.closeAllPositions(userId));
    }

    /** 反手：市价全平该仓位并立刻反向开等量新仓；反向开仓失败不抛，结果里带 openError（此时已空仓） */
    @PostMapping("/reverse/{positionId}")
    public Result<FuturesTradingService.ReverseResult> reverse(@CurrentUserId Long userId, @PathVariable Long positionId) {
        return Result.ok(futuresTradingService.reversePosition(userId, positionId));
    }

    /** 取消限价单 */
    @PostMapping("/cancel/{orderId}")
    public Result<FuturesOrderResponse> cancel(@CurrentUserId Long userId, @PathVariable Long orderId) {
        return Result.ok(futuresTradingService.cancelOrder(userId, orderId));
    }

    /** 追加保证金 */
    @PostMapping("/margin")
    public Result<Void> addMargin(@CurrentUserId Long userId, @RequestBody FuturesAddMarginRequest request) {
        futuresMarginService.addMargin(userId, request);
        return Result.ok();
    }

    /** 减少逐仓保证金 */
    @PostMapping("/margin/reduce")
    public Result<Void> reduceMargin(@CurrentUserId Long userId, @RequestBody FuturesReduceMarginRequest request) {
        futuresMarginService.reduceMargin(userId, request);
        return Result.ok();
    }

    /** 持仓调杠杆：全仓双向可调，逐仓只能调高 */
    @PostMapping("/leverage")
    public Result<Void> adjustLeverage(@CurrentUserId Long userId, @RequestBody FuturesAdjustLeverageRequest request) {
        futuresMarginService.adjustLeverage(userId, request);
        return Result.ok();
    }

    /** 全仓账户概览：净值/可用/占用/维持保证金（开仓面板与仓位卡展示用） */
    @GetMapping("/cross-account")
    public Result<Map<String, Object>> crossAccount(@CurrentUserId Long userId) {
        var account = crossMarginService.snapshot(userId);
        return Result.ok(Map.of(
                "balance", account.balance(),
                "unrealizedPnl", account.unrealizedPnl(),
                "equity", account.equity(),
                "available", account.available(),
                "usedMargin", account.usedMargin(),
                "pendingReserved", account.pendingReserved(),
                "maintenanceMargin", account.maintenanceMargin(),
                "positionCount", account.positions().size()));
    }

    /** 设置止损 */
    @PostMapping("/stop-loss")
    public Result<Void> setStopLoss(@CurrentUserId Long userId, @RequestBody FuturesStopLossRequest request) {
        futuresRiskService.setStopLoss(userId, request);
        return Result.ok();
    }

    /** 设置止盈 */
    @PostMapping("/take-profit")
    public Result<Void> setTakeProfit(@CurrentUserId Long userId, @RequestBody FuturesTakeProfitRequest request) {
        futuresRiskService.setTakeProfit(userId, request);
        return Result.ok();
    }

    /** 查询仓位列表 */
    @GetMapping("/positions")
    public Result<List<FuturesPositionDTO>> positions(@CurrentUserId Long userId, @RequestParam(required = false) String symbol) {
        return Result.ok(futuresQueryService.getUserPositions(userId, symbol));
    }

    /**
     * 仓位历史：自己已平/已强平的合约仓位，一条记录是一笔完整仓位（开仓到全部平掉）。
     * <p>
     * 跟 /orders 的区别是粒度：那个是一笔笔委托流水，这个把同一仓位的开仓、加仓、分批平仓
     * 合成一条，给的是"这笔仓位最后赚了多少、回报率多少"。
     */
    @GetMapping("/position-history")
    public Result<IPage<PositionHistoryDTO>> positionHistory(@CurrentUserId Long userId,
                                                             @RequestParam(required = false) String symbol,
                                                             @RequestParam(defaultValue = "1") int pageNum,
                                                             @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(positionHistoryService.page(userId, symbol, pageNum, pageSize));
    }

    /** 查询订单列表 */
    @GetMapping("/orders")
    public Result<IPage<FuturesOrderResponse>> orders(@CurrentUserId Long userId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String symbol) {
        return Result.ok(futuresQueryService.getUserOrders(userId, status, pageNum, pageSize, symbol));
    }

    /** 最新成交-匿名 */
    @GetMapping("/live")
    public Result<List<FuturesOrderResponse>> live() {
        return Result.ok(futuresQueryService.getLatestOrders());
    }

    /** Binance爆仓记录-匿名 */
    @GetMapping("/force-orders")
    public Result<IPage<ForceOrder>> forceOrders(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.clamp(pageSize, 1, 100);
        return Result.ok(forceOrderService.getPage(symbol, safePageNum, safePageSize));
    }

    /** 首页爆仓卡：只取最新一条，绕开分页那条 COUNT(*) 全表扫。无记录返回 null */
    @GetMapping("/force-orders/latest")
    public Result<ForceOrder> forceOrderLatest() {
        return Result.ok(forceOrderService.getLatest());
    }

    /** 合约K线（代理 Binance，Redis短TTL缓存） */
    @GetMapping("/klines")
    public String klines(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        // 翻历史要登录，同 CryptoMarketController.klines
        if (endTime != null) StpUtil.checkLogin();
        return klineCacheService.futuresKlines(symbol, interval, limit, endTime);
    }

    /** 永续合约档位表：前端预估强平价/MMR 用，避免与后端两份硬编码 */
    @GetMapping("/brackets")
    public Result<Map<String, List<FuturesLeverageBracketRegistry.Bracket>>> brackets() {
        return Result.ok(bracketRegistry.getAllBrackets());
    }

    /**
     * 资金费率：只读 feed 写的缓存，绝不回源 Binance。
     * 无合约的标的（bStock 等）和缓存没命中都返回 null，前端不渲染这段。
     */
    @GetMapping("/funding-rate")
    public Result<FundingRateService.FundingRateView> fundingRate(@RequestParam String symbol) {
        return Result.ok(fundingRateService.query(symbol));
    }

    /** 交易过滤器（步长/最小数量/最小名义额，对齐Binance exchangeInfo，启动时刷新）：合约+现货两套 */
    @GetMapping("/trade-filters")
    public Result<Map<String, Map<String, TradeFilterDefaults.Filter>>> tradeFilters() {
        return Result.ok(Map.of("futures", tradeFilterRegistry.allFutures(), "spot", tradeFilterRegistry.allSpot()));
    }
}
