package com.mawai.wiibfeed;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * wiib-feed：数据流上游进程。
 * <p>连交易所 WS（Binance 现货/合约/爆仓/aggTrade/depth/kline + Polymarket）→ 写 Redis（KV/Pub-Sub/Stream）。
 * 只扫自身 + 共享层 wiib-common（行情通道类/缓存/广播/REST客户端/基础设施config），不依赖 sim/quant。
 */
@SpringBootApplication(scanBasePackages = {"com.mawai.wiibfeed", "com.mawai.wiibcommon"})
@MapperScan({"com.mawai.wiibcommon.mapper"})
public class WiibFeedApplication {
    public static void main(String[] args) {
        SpringApplication.run(WiibFeedApplication.class, args);
    }
}
