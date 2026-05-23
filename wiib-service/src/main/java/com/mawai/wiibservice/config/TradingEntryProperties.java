package com.mawai.wiibservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MR;

/**
 * AI Trader 入场策略池配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.entry")
public class TradingEntryProperties {

    /** 默认保留三条老策略，阶段 1 改造后不因缺配置改变现状。 */
    private List<String> enabledStrategies = List.of(
            PATH_BREAKOUT,
            PATH_MR,
            PATH_LEGACY_TREND
    );
}
