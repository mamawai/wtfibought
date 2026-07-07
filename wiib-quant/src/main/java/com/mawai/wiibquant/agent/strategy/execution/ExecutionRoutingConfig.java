package com.mawai.wiibquant.agent.strategy.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 执行端口路由：strategy.execution.target=testnet|sim 二选一（默认 testnet 保持既有行为）。
 * 两个实现都是常驻 Bean（testnet 的手动自检接口不受影响），@Primary 决定 StrategyRuntime 拿到哪个；
 * 未选中的实现因收不到调用而完全静默——单轨运行，不存在双发。
 */
@Slf4j
@Configuration
public class ExecutionRoutingConfig {

    @Bean
    @Primary
    public StrategyExecutionPort strategyExecutionPort(
            @Value("${strategy.execution.target:testnet}") String target,
            TestnetExecutionService testnet,
            SimExecutionService sim) {
        boolean useSim = "sim".equalsIgnoreCase(target);
        log.info("[ExecutionRouting] 策略执行目标 = {}", useSim ? "sim(本平台模拟盘)" : "testnet(Binance)");
        return useSim ? sim : testnet;
    }
}
