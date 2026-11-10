package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.trade.WatchesChanged;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 盯盘哨兵：盯模型用 set_watch 布的条件。触及价位和到时刻看 markPrice tick（~1s），命中就广播一个 {@link WatchTrigger}
 * 让调度器叫醒；周期收盘由调度器在 5m 收盘时来问 {@link #closeHits}，这样命中能并进同一刻的例行唤醒而不是叫两次。
 * 带动作的条件（预案）命中后先交给 {@link PresetExecutor} 执行，它说要叫醒才叫。
 * 命中：库里 ARMED → FIRED（CAS，并发 tick 只有一个赢），赢了才从名单摘掉。
 * 名单在内存里按币放着，条件一变（{@link WatchesChanged}）整份重载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchSentinel implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final TraderWatchStore watchStore;
    private final ApplicationEventPublisher events;
    /** 带动作的条件命中时，先由它把动作做掉 */
    private final PresetExecutor presets;

    /** 币 → 在盯的条件。整份替换，读到的是快照 */
    private volatile Map<String, List<AiTraderWatch>> armed = Map.of();

    @PostConstruct
    public void init() {
        reload();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[Watch] 订阅 {}，在盯 {} 条", MarketStreamChannels.PRICE,
                armed.values().stream().mapToInt(List::size).sum());
    }

    /** 条件变了整份重载；replace 在事务里，提交后再读 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(WatchesChanged event) {
        reload();
    }

    synchronized void reload() {
        Map<String, List<AiTraderWatch>> bySymbol = new HashMap<>();
        for (AiTraderWatch w : watchStore.allArmed()) {
            bySymbol.computeIfAbsent(w.getSymbol(), k -> new ArrayList<>()).add(w);
        }
        armed = Map.copyOf(bySymbol);
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        try {
            JsonNode obj = MAPPER.readTree(message.getBody());
            if (!"markprice".equals(obj.path("type").asString(null))) {
                return;
            }
            String symbol = obj.path("symbol").asString(null);
            String price = obj.path("price").asString(null);
            if (symbol == null || price == null) {
                return;
            }
            onPriceTick(symbol.trim().toUpperCase(Locale.ROOT), new BigDecimal(price), System.currentTimeMillis());
        } catch (Exception e) {
            // 单条解析失败只丢本 tick，下一 tick 补上
            log.debug("[Watch] tick解析失败: {}", e.toString());
        }
    }

    /** 触及与到时刻：每个 tick 比一遍该币的名单，命中的广播出去 */
    void onPriceTick(String symbol, BigDecimal price, long now) {
        List<AiTraderWatch> list = armed.get(symbol);
        if (list == null) {
            return;
        }
        for (AiTraderWatch w : list) {
            boolean hit = switch (w.getKind()) {
                case AiTraderWatch.TOUCH_ABOVE -> price.compareTo(w.getLevel()) >= 0;
                case AiTraderWatch.TOUCH_BELOW -> price.compareTo(w.getLevel()) <= 0;
                case AiTraderWatch.AT_TIME -> now >= w.getAtTime();
                default -> false;
            };
            if (hit && fire(w, price, now)) {
                WatchTrigger trigger = new WatchTrigger(w, price, now);
                if (!runPreset(trigger)) {
                    events.publishEvent(trigger);
                }
            }
        }
    }

    /**
     * 命中的条件带动作的话交给执行器（它在虚拟线程上调 sim，不占行情线程和 K 线事件线程），做完说要叫醒才广播。
     * 返回这条是不是预案
     */
    private boolean runPreset(WatchTrigger hit) {
        if (hit.watch().getActionJson() == null) {
            return false;
        }
        presets.submit(hit.watch(), hit.price(), hit.firedAt()).thenAccept(wake -> {
            if (wake) {
                events.publishEvent(hit);
            }
        });
        return true;
    }

    /**
     * 这根 5m 收盘命中的收盘类条件：收盘时刻落在该条件周期的边界上，收盘价又站上 / 跌破价位的，CAS 命中后交给调度器，
     * 由它决定并进例行唤醒还是单独叫醒。带动作的不在返回里：交给执行器，要叫醒时它自己广播。
     */
    List<WatchTrigger> closeHits(String symbol, long closeTime, KlineBar bar) {
        List<AiTraderWatch> list = armed.get(symbol);
        if (list == null) {
            return List.of();
        }
        BigDecimal close = bar.close();
        List<WatchTrigger> hits = new ArrayList<>();
        for (AiTraderWatch w : list) {
            if (w.getPeriod() == null || TraderScheduler.boundaryOf(closeTime, w.getPeriod()) < 0) {
                continue;
            }
            boolean hit = AiTraderWatch.CLOSE_ABOVE.equals(w.getKind()) ? close.compareTo(w.getLevel()) >= 0
                    : close.compareTo(w.getLevel()) <= 0;
            if (hit && fire(w, close, closeTime + 1)) {
                WatchTrigger trigger = new WatchTrigger(w, close, closeTime + 1);
                if (!runPreset(trigger)) {
                    hits.add(trigger);
                }
            }
        }
        return hits;
    }

    /** 库里 CAS 成功才算命中。不论成败都从名单摘掉：CAS 失败说明库里已经不是 ARMED（命中过或被替换），不用再盯 */
    private boolean fire(AiTraderWatch w, BigDecimal price, long at) {
        boolean fired = watchStore.fire(w.getId(), at, price);
        remove(w);
        if (fired) {
            log.info("[Watch] 命中 traderId={} {} {} {} @{}", w.getTraderId(), w.getSymbol(), w.getKind(), w.getLevel(), price);
        }
        return fired;
    }

    private synchronized void remove(AiTraderWatch w) {
        Map<String, List<AiTraderWatch>> copy = new HashMap<>(armed);
        List<AiTraderWatch> list = new ArrayList<>(copy.getOrDefault(w.getSymbol(), List.of()));
        list.removeIf(x -> x.getId().equals(w.getId()));
        copy.put(w.getSymbol(), list);
        armed = Map.copyOf(copy);
    }
}
