package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.handover.RedisStreamReader;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 持仓事件监听：读 sim 写进 Stream 的 {@link TradeEvent}（止损 / 止盈 / 强平 / 全仓爆仓 / 限价单开出新仓），
 * 事件里的 sim 用户是哪个 RUNNING trader 的子账户，就广播一条 {@link TriggerFired}；不是 trader 的用户直接忽略。
 * 止盈触发不叫醒，不广播：平掉一部分、仓位还在的记一行，下一轮开场白报；整仓平完的由唤醒开头的对账报结局。
 * 发生超过 5 分钟的事件不叫醒（唤醒开头的对账会报结局），止盈记一行照做。
 * 准入（时段 / 交给哪一轮）全在调度器，这里只认人。
 * 进活的口：开口是开始读，关口是停读并等读循环退出（{@link RedisStreamReader}）。消费组 agent 第一次建组从头读
 */
@Slf4j
@Component
@Order(30)
public class TradeEventListener extends RedisStreamReader {

    /** 发生超过这么久的事件不叫醒 */
    static final long STALE_MS = 300_000L;

    private final AiTraderMapper traderMapper;
    private final ApplicationEventPublisher events;
    /** 止盈平掉一部分时查一下仓位还在不在 */
    private final SimTradeClient simTradeClient;
    /** 没叫醒它的事件记在这里，下一轮开场白报 */
    private final UnreportedEvents unreported;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    public TradeEventListener(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory,
                              AiTraderMapper traderMapper, ApplicationEventPublisher events,
                              SimTradeClient simTradeClient, UnreportedEvents unreported,
                              UserLangResolver userLangResolver, PromptCatalog prompts) {
        super(redisTemplate, connectionFactory, TradeEvent.STREAM, "agent", ReadOffset.from("0"), "agent",
                Duration.ofSeconds(2), "TradeEvent");
        this.traderMapper = traderMapper;
        this.events = events;
        this.simTradeClient = simTradeClient;
        this.unreported = unreported;
        this.userLangResolver = userLangResolver;
        this.prompts = prompts;
    }

    /** 一条记录：字段 json 读回 TradeEvent 再处理；坏消息和处理失败只丢这一条 */
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        TradeEvent event;
        try {
            event = MAPPER.readValue(record.getValue().get("json"), TradeEvent.class);
        } catch (Exception e) {
            log.warn("[TradeEvent] 解析失败 id={} msg={}", record.getId(), e.toString());
            return;
        }
        try {
            onEvent(event);
        } catch (Exception e) {
            log.warn("[TradeEvent] 处理失败 type={} userId={} msg={}", event.type(), event.userId(), e.toString());
        }
    }

    void onEvent(TradeEvent event) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getSimUserId, event.userId())
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING));
        boolean stale = nowMs.getAsLong() - event.at() > STALE_MS;
        for (AiTrader t : traders) {
            if (TradeEvent.TAKE_PROFIT.equals(event.type())) {
                noteTakeProfit(t, event);
            } else if (stale) {
                log.info("[TradeEvent] 事件超过 5 分钟，不叫醒 traderId={} type={} at={}", t.getId(), event.type(), event.at());
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
