package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.DayTickDTO;
import com.mawai.wiibcommon.dto.KlineDTO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 行情数据服务接口
 */
public interface MarketDataService {

    /**
     * 生成次日所有股票的行情数据
     * 写入数据库price_tick表 + Redis Hash
     */
    void generateNextDayMarketData(LocalDate targetDate);

    /**
     * 加载指定日期的行情数据到Redis
     * 从数据库读取，写入Redis Hash
     */
    void loadDayDataToRedis(LocalDate date);

    /**
     * 获取当日分时数据（到当前时间为止）
     * @return 按时间排序的tick列表
     */
    List<DayTickDTO> getDayTicks(Long stockId);

    /**
     * 获取实时行情（用于WebSocket推送）
     * 包含：price, time, open, high, low, prevClose
     * 同时更新当日最高最低价
     */
    Map<String, Object> getRealtimeQuote(Long stockId, LocalDate date, LocalTime time);

    /**
     * 管理接口：将[开盘到指定时间]之间的所有ticks重建到当日汇总缓存（open/high/low/last）
     *
     * <p>用于修复：行情推送从中途开始，导致high/low/last未包含早盘波动的问题。</p>
     *
     * @param date 指定日期（通常是今天）
     * @param time 截止时间（通常是当前时间）
     * @return {date, time, updated, skipped}
     */
    Map<String, Object> refreshDailyCacheFromTicks(LocalDate date, LocalTime time);

    /**
     * 获取历史某天的分时数据（K线悬停用）
     *
     * @param stockId 股票ID
     * @param  date  日期
     * @return 按时间排序的tick列表
     */
    List<DayTickDTO> getHistoryDayTicks(Long stockId, LocalDate date);

    /**
     * 获取日K线数据（带Redis缓存）
     */
    List<KlineDTO> getKlineData(Long stockId, int days);
}
