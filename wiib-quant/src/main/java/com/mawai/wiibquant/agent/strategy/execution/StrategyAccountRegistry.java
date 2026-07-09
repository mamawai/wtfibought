package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 策略模拟盘账户注册表：每策略一个独立量化账户，用户名 quant-&lt;策略ID&gt; 区分，
 * sim 侧 ensure-account 按用户名幂等创建（已存在直接返回 userId，不重复入金）。
 *
 * <p>启动时对全部注册策略 Bean 预建账户，保证三策略账户开箱即在；sim 未就绪时单策略
 * 失败只警告不阻断启动，{@link #userId} 的懒创建兜底在首次取用时自动重试。
 * SimExecutionService 与 StrategyAccountService 共用本注册表，账户解析单一来源。</p>
 */
@Slf4j
@Component
public class StrategyAccountRegistry {

    private final SimTradeClient client;
    private final List<TradingStrategySpi> strategies;
    private final ConcurrentMap<String, Long> accountByStrategy = new ConcurrentHashMap<>();

    @Value("${strategy.execution.sim.initial-balance:10000}")
    BigDecimal initialBalance;

    public StrategyAccountRegistry(SimTradeClient client, List<TradingStrategySpi> strategies) {
        this.client = client;
        this.strategies = strategies;
    }

    /** 启动预建：逐策略 ensure（没有就创建，有则解析缓存），失败不拖垮启动、留懒创建兜底。 */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureAccountsOnStartup() {
        for (TradingStrategySpi strategy : strategies) {
            String id = strategy.id();
            try {
                log.info("[StrategyAccount] 启动账户就绪 strategyId={} username=quant-{} userId={}",
                        id, id, userId(id));
            } catch (Exception e) {
                log.warn("[StrategyAccount] 启动预建账户失败(首次取用时重试) strategyId={} msg={}",
                        id, e.toString());
            }
        }
    }

    /** 解析策略账户 userId：无则幂等创建并缓存（失败不缓存，下次取用重试）。 */
    public Long userId(String strategyId) {
        return accountByStrategy.computeIfAbsent(strategyId,
                id -> client.ensureAccount("quant-" + id, initialBalance));
    }
}
