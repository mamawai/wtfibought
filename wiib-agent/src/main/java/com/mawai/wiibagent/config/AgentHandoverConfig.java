package com.mawai.wiibagent.config;

import com.mawai.wiibcommon.handover.HandoverConfig;
import com.mawai.wiibcommon.handover.HandoverPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;

/**
 * agent 挂到交接骨架上。关口的先后：定时器 → 实时页订阅（TraderLiveHub）→ 收盘流（KlineStreamConsumer）
 * → 价格和持仓事件订阅（WatchSentinel、ExecutionPriceConsumer、TradeEventListener）→ 波动报警（VolatilitySentinel）
 * → 起新复盘（ReviewScheduler）→ 起新轮（TraderScheduler）→ 接新对话（ChatConcurrencyGate）→ 接新的教练请求（ReplayCoachService）。
 * 关停时等手上的活最多 60 秒，还没完的提前收尾，再等 35 秒（盖过唤醒会话叫停后的 30 秒）
 */
@Configuration
@Import(HandoverConfig.class)
public class AgentHandoverConfig {

    @Bean
    public HandoverPolicy handoverPolicy() {
        return new HandoverPolicy("agent", 1, Duration.ofSeconds(60), Duration.ofSeconds(35));
    }
}
