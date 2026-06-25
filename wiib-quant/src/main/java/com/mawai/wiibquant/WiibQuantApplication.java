package com.mawai.wiibquant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * wiib-quant：量化 + 策略研究服务（独立进程）。
 * AI 量化分析(只预测不交易) + Fibo 等策略下单 Binance Testnet；
 * 行情(kline/depth/orderflow/markprice)从 Redis 消费 feed 进程写入，不直连交易所 WS。
 */
@SpringBootApplication(scanBasePackages = {"com.mawai.wiibquant", "com.mawai.wiibcommon"})
@MapperScan({"com.mawai.wiibquant.mapper", "com.mawai.wiibcommon.mapper"})
@EnableScheduling
public class WiibQuantApplication {

    public static void main(String[] args) {
        SpringApplication.run(WiibQuantApplication.class, args);
    }
}
