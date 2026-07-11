package com.mawai.wiibsim.service.impl;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;

import cn.hutool.json.JSONObject;
import com.mawai.wiibsim.service.MarketDataService;
import com.mawai.wiibsim.service.QuotePushService;
import com.mawai.wiibsim.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 行情推送服务实现（定时推送）
 *
 * <p><b>设计说明：</b></p>
 * 本系统是模拟交易系统，行情数据预先生成并存储在Redis中。
 * 推送采用定时推送方式（每10秒查询Redis获取当前时间点数据）。
 *
 * <p><b>为什么是定时推送？</b></p>
 * <ul>
 *   <li>行情数据是预生成的静态数据，存储在Redis</li>
 *   <li>没有外部数据源推送，无法实现事件驱动</li>
 *   <li>只能定时查询Redis，根据当前时间获取数据</li>
 *   <li>10秒间隔已足够流畅（模拟系统无需毫秒级）</li>
 * </ul>
 *
 * <p><b>为什么推送全部股票？</b></p>
 * <ul>
 *   <li>STOMP自动过滤：无人订阅的topic不会真的发送数据</li>
 *   <li>100支股票，推送耗时~300ms，每10秒执行，占用3% CPU</li>
 *   <li>代码简单，无需维护订阅追踪</li>
 *   <li>如果股票数量上千，再考虑优化</li>
 * </ul>
 *
 * <p><b>优化要点：</b></p>
 * <ul>
 *   <li>Redis缓存：从Redis获取Stock静态数据，避免DB查询</li>
 *   <li>STOMP过滤：自动过滤无订阅的推送</li>
 *   <li>简单直接：无额外订阅管理</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotePushServiceImpl implements QuotePushService {

    private static final int PUSH_CONCURRENCY = 5;

    private final MarketDataService marketDataService;
    private final StockCacheService stockCacheService;
    private final MarketBroadcaster broadcastService;

    /**
     * 推送单个股票行情
     */
    public void pushQuote(Long stockId, LocalDate date) {
        try {
            // 从Redis获取Stock静态数据（避免DB查询）
            Map<String, String> stockStatic = stockCacheService.getStockStatic(stockId);
            if (stockStatic == null) {
                log.warn("Stock静态数据未找到: {}", stockId);
                return;
            }

            String stockCode = stockStatic.get("code");
            String stockName = stockStatic.get("name");

            // 从Redis获取实时行情
            LocalTime now = LocalTime.now();
            LocalTime alignedTime = alignToTick(now);
            Map<String, Object> quote = marketDataService.getRealtimeQuote(stockId, date, alignedTime);

            if (quote == null) {
                return;
            }

            // 构建推送消息
            JSONObject message = new JSONObject();
            message.set("code", stockCode);
            message.set("name", stockName);
            message.set("price", quote.get("price"));
            message.set("open", quote.get("open"));
            message.set("high", quote.get("high"));
            message.set("low", quote.get("low"));
            message.set("prevClose", quote.get("prevClose"));
            message.set("timestamp", System.currentTimeMillis());

            // 通过Redis广播
            // STOMP会自动过滤：如果没人订阅该topic，不会真的发送
            broadcastService.broadcastStockQuote(stockCode, message.toString());

            log.info("推送行情: {}", stockCode);
        } catch (Exception e) {
            log.error("推送行情失败: {}", stockId, e);
        }
    }

    /**
     * 推送所有股票行情（虚拟线程并发，Semaphore限流）
     */
    @Override
    public void pushAllQuotes(LocalDate date) {
        Set<Long> allStockIds = stockCacheService.getAllStockIds();
        if (allStockIds.isEmpty()) {
            log.warn("没有股票数据");
            return;
        }

        Semaphore semaphore = new Semaphore(PUSH_CONCURRENCY);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Long stockId : allStockIds) {
                executor.submit(() -> {
                    try {
                        semaphore.acquire();
                        pushQuote(stockId, date);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        semaphore.release();
                    }
                });
            }
        }

        log.info("推送行情完成，共{}支股票（{}并发）", allStockIds.size(), PUSH_CONCURRENCY);
    }

    /** 对齐到10秒整点 */
    private LocalTime alignToTick(LocalTime time) {
        int seconds = time.toSecondOfDay();
        int aligned = (seconds / 10) * 10;
        return LocalTime.ofSecondOfDay(aligned);
    }
}
