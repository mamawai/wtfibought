package com.mawai.wiibquant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * BlockBeats(律动)快讯 API 配置。
 * 免费额度是一次性总量(不回血)，故只由 NewsCache 的 poller 低频调用，其余消费方读缓存。
 */
@Data
@Component
@ConfigurationProperties(prefix = "blockbeats")
public class BlockBeatsProperties {

    /** 请求头 api-key */
    private String apiKey;
    /** API base（pro 端） */
    private String baseUrl = "https://api-pro.theblockbeats.info";
    /** 语言 cn/en */
    private String lang = "cn";
    /** 每次拉取条数 1-50 */
    private int size = 20;
    /** 缓存过期(ms)：懒加载复用窗口——距上次成功拉取在此值内则复用不打 API，超过则重拉。默认 10min */
    private long expiryMs = 10 * 60 * 1000L;
}
