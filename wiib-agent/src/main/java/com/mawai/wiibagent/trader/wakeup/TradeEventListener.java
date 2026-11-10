package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderTrailStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.market.TradeEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 持仓事件监听：sim 发来的 {@link TradeEvent}（止损 / 止盈 / 强平 / 全仓爆仓 / 限价成交），
 * 事件里的 sim 用户是哪个 RUNNING trader 的子账户，就把它叫醒；不是 trader 的用户直接忽略。
 * 准入（冷静期 / 互斥 / 时段 / 交接）全在调度器，这里只认人。
 * 限价单成交时顺手把挂单带的跟踪止损规则绑上仓位。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeEventListener implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final AiTraderMapper traderMapper;
    private final TraderScheduler scheduler;
    private final TraderTrailStore trailStore;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(TradeEvent.CHANNEL));
        log.info("[TradeEvent] 订阅 {}", TradeEvent.CHANNEL);
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        TradeEvent event;
        try {
            event = MAPPER.readValue(message.getBody(), TradeEvent.class);
        } catch (Exception e) {
            log.warn("[TradeEvent] 解析失败: {}", e.toString());
            return;
        }
        // 查库和唤醒都不占 redis-sub 线程；异步之后没人接得住，不打日志就彻底无声
        Thread.startVirtualThread(() -> {
            try {
                onEvent(event);
            } catch (Exception e) {
                log.warn("[TradeEvent] 处理失败 type={} userId={} msg={}", event.type(), event.userId(), e.toString());
            }
        });
    }

    void onEvent(TradeEvent event) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getSimUserId, event.userId())
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING));
        for (AiTrader t : traders) {
            // 限价单成交：挂单时设的跟踪止损从这一刻绑上仓位开始跟，不等模型醒
            if (TradeEvent.LIMIT_FILLED.equals(event.type())) {
                trailStore.bind(t.getId(), t.getRoundNo(), event.symbol(), event.side(), event.positionId());
            }
            scheduler.tryTriggerWake(t.getId(), new EventTrigger(event));
        }
    }
}
