package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.agent.strategy.fibo.FiboParams;
import com.mawai.wiibquant.agent.strategy.fibo.FiboRetracementStrategy;
import com.mawai.wiibquant.agent.strategy.sqzmom.SqzMomParams;
import com.mawai.wiibquant.agent.strategy.sqzmom.SqueezeMomentumStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 策略注册处；新增策略注册成 Bean，runtime 按 strategy.runtime.enabled-ids 启用。 */
@Configuration
public class StrategyConfig {

    @Bean
    public FiboRetracementStrategy fiboRetracementStrategy() {
        return new FiboRetracementStrategy(FiboParams.defaults(), QuantConstants.WATCH_SYMBOLS);
    }

    /**
     * SQZMOM 4H 仅空头（41 币消融定稿，部署篮子 SOL/DOGE/XRP）。
     * 注意：实际驱动依赖 feed 侧 symbols 覆盖这三个币的 5m K线流，否则收不到收盘事件。
     */
    @Bean
    public SqueezeMomentumStrategy squeezeMomentumStrategy() {
        return new SqueezeMomentumStrategy(SqzMomParams.defaults(),
                List.of("SOLUSDT", "DOGEUSDT", "XRPUSDT"));
    }
}
