package com.mawai.wiibsim.service;

import java.time.LocalDate;

/**
 * 行情推送服务接口
 */
public interface QuotePushService {

    /**
     * 推送股票行情到指定主题
     * @param stockId 股票ID
     * @param date 日期
     */
    void pushQuote(Long stockId, LocalDate date);

    /**
     * 推送所有股票行情
     * @param date 日期
     */
    void pushAllQuotes(LocalDate date);
}
