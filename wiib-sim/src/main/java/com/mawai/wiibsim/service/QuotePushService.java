package com.mawai.wiibsim.service;

import java.time.LocalDate;

/**
 * 行情推送服务接口
 */
public interface QuotePushService {


    /**
     * 推送所有股票行情
     * @param date 日期
     */
    void pushAllQuotes(LocalDate date);
}
