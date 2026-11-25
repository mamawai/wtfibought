package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.handover.DeliveryGate;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 持仓事件监听：sim 发来的 {@link TradeEvent}（止损 / 止盈 / 强平 / 全仓爆仓 / 限价单开出新仓），
 * 事件里的 sim 用户是哪个 RUNNING trader 的子账户，就广播一条 {@link TriggerFired}；不是 trader 的用户直接忽略。
 * 止盈触发不叫醒，不广播：平掉一部分、仓位还在的记一行，下一轮开场白报；整仓平完的由唤醒开头的对账报结局。
 * 准入（时段 / 交给哪一轮）全在调度器，这里只认人。进活的口：开口是订阅，关口是退订、等正在回调的那几条交出去。
 */
@Slf4j
@Component
@Order(30)
@RequiredArgsConstructor
public class TradeEventListener implements MessageListener, Intake {

    private final RedisMessageListenerContainer listenerContainer;
    private final AiTraderMapper traderMapper;
    private final ApplicationEventPublisher events;
    /** 止盈平掉一部分时查一下仓位还在不在 */
    private final SimTradeClient simTradeClient;
    /** 没叫醒它的事件记在这里，下一轮开场白报 */
    private final UnreportedEvents unreported;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;
    /** 持仓事件回调的闸 */
    private final DeliveryGate gate = new DeliveryGate();

    @Override
    public void open(Start how) {
        gate.open();
        listenerContainer.addMessageListener(this, new ChannelTopic(TradeEvent.CHANNEL));
        log.info("[TradeEvent] 订阅 {}", TradeEvent.CHANNEL);
    }

    @Override
    public void close() {
        listenerContainer.removeMessageListener(this);
        gate.close();
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        gate.pass(() -> handle(message));
    }

    private void handle(Message message) {
        TradeEvent event;
        try {
            event = MAPPER.readValue(message.getBody(), TradeEvent.class);
        } catch (Exception e) {
            log.warn("[TradeEvent] 解析失败: {}", e.toString());
            return;
        }
        // 查库不占 redis-sub 线程；异步之后没人接得住，不打日志就彻底无声
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
            if (TradeEvent.TAKE_PROFIT.equals(event.type())) {
                noteTakeProfit(t, event);
            } else {
                events.publishEvent(new TriggerFired(t.getId(), new EventTrigger(event)));
            }
        }
    }

    /** 止盈平掉一部分、仓位还在：记一行等下一轮开场白报。整仓平完的不记，对账会报结局 */
    private void noteTakeProfit(AiTrader trader, TradeEvent event) {
        boolean stillOpen = simTradeClient.getAllPositions(trader.getSimUserId()).stream()
                .anyMatch(p -> p.getId() != null && p.getId().equals(event.positionId()));
        if (!stillOpen) {
            return;
        }
        unreported.add(trader.getId(), List.of(new UnreportedEvents.Line(event.symbol(),
                prompts.get(userLangResolver.of(trader.getUserId()), "trader.wake.eventTakeProfit", Map.of(
                        "symbol", event.symbol(), "side", event.side(),
                        "time", BeijingTime.shortFormat(event.at()),
                        "qty", WatchText.num(event.quantity()), "price", WatchText.num(event.price()))) + '\n')));
    }
}
