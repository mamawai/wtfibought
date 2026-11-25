package com.mawai.wiibagent.config;

import com.mawai.wiibagent.handover.ArrivalJson;
import com.mawai.wiibagent.handover.HttpTraderPeer;
import com.mawai.wiibagent.trader.wakeup.TraderPeer;
import com.mawai.wiibcommon.handover.EventBridge;
import com.mawai.wiibcommon.handover.HandoverConfig;
import com.mawai.wiibcommon.handover.HandoverPolicy;
import com.mawai.wiibcommon.handover.PeerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

/**
 * agent 挂到交接骨架上。关口的先后：定时器 → 实时页订阅（TraderLiveHub）→ 收盘流（KlineStreamConsumer）
 * → 价格和持仓事件订阅（WatchSentinel、ExecutionPriceConsumer、TradeEventListener）→ 波动报警（VolatilitySentinel）
 * → 大户任务的停止标志（WhalePoolTask、WhalePositionTask）
 * → 起新复盘（ReviewScheduler）→ 起新轮（TraderScheduler）→ 接新对话（ChatConcurrencyGate）→ 接新的教练请求（ReplayCoachService）。
 * 关停时等手上的活最多 60 秒，还没完的提前收尾，再等 35 秒（盖过唤醒会话叫停后的 30 秒）；让位后 15 秒没等到对端接手就自己恢复在任
 */
@Configuration
@Import(HandoverConfig.class)
public class AgentHandoverConfig {

    @Bean
    public HandoverPolicy handoverPolicy() {
        return new HandoverPolicy("agent", 1, Duration.ofSeconds(60), Duration.ofSeconds(35), Duration.ofSeconds(15));
    }

    /** 标了 @Clustered 的事件（盯盘条件变了）转给另一个实例 */
    @Bean
    public EventBridge eventBridge(StringRedisTemplate redis, RedisMessageListenerContainer container,
                                   ApplicationEventPublisher events, @Value("${WIIB_SLOT:}") String slot) {
        return new EventBridge(redis, container, events, "agent", slot);
    }

    /** 交接时和另一个实例说话；没分槽位时没有对端 */
    @Bean
    public TraderPeer traderPeer(PeerClient peerClient, ArrivalJson json) {
        return peerClient.available() ? new HttpTraderPeer(peerClient, json) : TraderPeer.NONE;
    }
}
