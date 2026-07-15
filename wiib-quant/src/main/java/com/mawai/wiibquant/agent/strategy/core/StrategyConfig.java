package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.agent.strategy.fibo.FiboParams;
import com.mawai.wiibquant.agent.strategy.fibo.FiboRetracementStrategy;
import com.mawai.wiibquant.agent.strategy.liq.LiqFadeParams;
import com.mawai.wiibquant.agent.strategy.liq.LiqFadeStrategy;
import com.mawai.wiibquant.agent.strategy.liq.RedisLiqSideData;
import com.mawai.wiibquant.agent.strategy.sqzmom.SqzMomParams;
import com.mawai.wiibquant.agent.strategy.sqzmom.SqueezeMomentumStrategy;
import com.mawai.wiibquant.agent.strategy.turtle.TurtleParams;
import com.mawai.wiibquant.agent.strategy.turtle.TurtleStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    /**
     * TURTLE 4H 90入场/15退出、盘中触价入场（六币粗+细网格验证的高原中心），多空双向；
     * 是否运行由 enabled-ids 控制。触价单是 quant 内存态（SimExecutionService ARMED），
     * 由 feed:price 的 futures tick 触发。feed 侧 symbols 已覆盖四币 5m 流（含 BNB）。
     */
    @Bean
    public TurtleStrategy turtleStrategy() {
        return new TurtleStrategy(TurtleParams.defaults(),
                List.of("SOLUSDT", "ETHUSDT", "DOGEUSDT", "BNBUSDT"));
    }

    /**
     * LIQFADE 强平fade（LONG-only 5m，五币验证段全绿）。部署篮子 BTC/ETH/DOGE：
     * SOL 验证段仅 10 笔剔除；XRP(PF1.83 第二强)是否纳入待定。
     * premium/taker 经 RedisLiqSideData 读 feed 落的 Redis；同样依赖 feed symbols
     * 覆盖篮子币的 5m K线流 + markPrice/miniTicker 流，否则签名数据缺失只会静默降频。
     */
    @Bean
    public LiqFadeStrategy liqFadeStrategy(StringRedisTemplate redisTemplate) {
        LiqFadeParams params = LiqFadeParams.defaults();
        return new LiqFadeStrategy(params, List.of("BTCUSDT", "ETHUSDT", "DOGEUSDT"),
                new RedisLiqSideData(redisTemplate, params.premStaleMaxMs()));
    }
}
