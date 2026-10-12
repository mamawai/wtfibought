package com.mawai.wiibagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * wiib-agent：AI 交易员进程（8082）。
 * 自己出 LLM 研判/对话/复盘，策略实盘、回测、量化研究这些在 wiib-quant 库里，连它们的接口一起由本进程挂载。
 * 行情(kline/depth/orderflow/markprice)从 Redis 消费 feed 进程写入，不直连交易所 WS。
 * <p>
 * 三个包一个都不能漏：wiibquant 的 controller/task/mapper 都住在库 jar 里，扫不到就是接口静默 404、mapper 注入失败。
 */
@SpringBootApplication(scanBasePackages = {"com.mawai.wiibagent", "com.mawai.wiibquant", "com.mawai.wiibcommon"})
@MapperScan({"com.mawai.wiibagent.mapper", "com.mawai.wiibquant.mapper", "com.mawai.wiibcommon.mapper"})
@EnableScheduling
public class WiibAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WiibAgentApplication.class, args);
    }
}
