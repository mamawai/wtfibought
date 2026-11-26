package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.handover.HandoverConfig;
import com.mawai.wiibcommon.handover.HandoverPolicy;
import com.mawai.wiibcommon.handover.TrackedExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * sim 挂到交接骨架上。关口的先后：请求闸（RequestGate）和定时器 → 标记价订阅（MatchPriceConsumer）→ 成交流（TradeStreamConsumer）
 * → 预测回合流（PredictionRoundConsumer），开口反过来，请求闸最后放行。关停时等后台活和在处理的请求最多 10 秒，不提前收尾；
 * 让位时等它们清零最多 3 秒，等不到就重新开口、让位失败；让位后 8 秒没等到对端接手就自己恢复在任。没有要带走的状态
 */
@Configuration
@Import(HandoverConfig.class)
public class SimHandoverConfig {

    @Bean
    public HandoverPolicy handoverPolicy() {
        return new HandoverPolicy("sim", 1, Duration.ofSeconds(10), Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(8), true);
    }

    /** 会改账的后台活都交给它，关停时等它清零。按 Executor 类型注入拿到的是它 */
    @Bean
    @Primary
    public TrackedExecutor backgroundWork() {
        return new TrackedExecutor("background");
    }
}
