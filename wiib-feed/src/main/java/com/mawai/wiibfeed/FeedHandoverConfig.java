package com.mawai.wiibfeed;

import com.mawai.wiibcommon.handover.HandoverConfig;
import com.mawai.wiibcommon.handover.HandoverPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

/**
 * feed 挂到交接骨架上。口：定时器、Binance 行情连接（BinanceWsClient）、Polymarket 连接（PolymarketWsClient）。
 * 没有手上的活，也没有要带走的状态，各项都不等。交接时新实例先接手、连好出数，旧实例再让位，两边同时写出约 1–3 秒。
 * 让位就断了行情连接，之后不能恢复在任
 */
@Configuration
@Import(HandoverConfig.class)
public class FeedHandoverConfig {

    @Bean
    public HandoverPolicy handoverPolicy() {
        return new HandoverPolicy("feed", 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, false);
    }
}
