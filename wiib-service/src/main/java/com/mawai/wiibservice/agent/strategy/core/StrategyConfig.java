package com.mawai.wiibservice.agent.strategy.core;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibservice.agent.strategy.fibo.FiboParams;
import com.mawai.wiibservice.agent.strategy.fibo.FiboRetracementStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 策略注册处；新增策略注册成 Bean，runtime 按配置启用。 */
@Configuration
public class StrategyConfig {

    @Bean
    public FiboRetracementStrategy fiboRetracementStrategy() {
        return new FiboRetracementStrategy(FiboParams.defaults(), QuantConstants.WATCH_SYMBOLS);
    }
}
