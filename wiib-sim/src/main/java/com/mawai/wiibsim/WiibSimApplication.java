package com.mawai.wiibsim;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * wiib-sim：真人模拟交易平台（独立进程）。
 * 模拟股票 + crypto 现货/合约/期权 + 小游戏 + 预测市场；账本=自研模拟盘(DB)。
 * 行情从 Redis 消费 feed 进程写入；量化分析在 wiib-quant，经 internal API 互通。
 */
@SpringBootApplication(scanBasePackages = {"com.mawai.wiibsim", "com.mawai.wiibcommon"})
@MapperScan({"com.mawai.wiibsim.mapper", "com.mawai.wiibcommon.mapper"})
@EnableScheduling
public class WiibSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(WiibSimApplication.class, args);
    }
}
