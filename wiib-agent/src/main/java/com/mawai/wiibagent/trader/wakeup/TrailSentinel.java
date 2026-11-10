package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.StopOrders;
import com.mawai.wiibagent.trader.trade.TraderTrailStore;
import com.mawai.wiibagent.trader.trade.TrailsChanged;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderTrail;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 跟踪止损哨兵：听 markPrice tick（和 {@link WatchSentinel} 同一个频道），记下每条规则开始跟随后的最有利价。
 * 应有的止损 = 最有利价回撤 callbackPct；它比现挂的止损紧出一个步长（回调距离的 {@link #STEP_OF_CALLBACK}），
 * 就走 {@link StopOrders#move} 把 sim 的止损挪过去。价格往回走时什么都不做，所以止损只进不退。
 * 止损一直是 sim 里真实挂着的单：本进程停了只是不再往前挪。跟到哪了在挪的时候落库，重启后从那里接着跟。
 * 挪的时候发现仓位已经不在，就结束这条规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrailSentinel implements MessageListener {

    /** 步长：应有止损比现挂的紧出回调距离的这么多才挪一次 */
    static final BigDecimal STEP_OF_CALLBACK = new BigDecimal("0.1");
    /** 止损价留几位有效数字 */
    private static final MathContext PRICE_DIGITS = new MathContext(6);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RedisMessageListenerContainer listenerContainer;
    private final TraderTrailStore trailStore;
    private final SimTradeClient simTradeClient;
    private final AiTraderMapper traderMapper;
    private final UserLangResolver userLangResolver;

    /** 一条在跟的规则和它跟到哪了：best 是最有利价（还没开始跟为 null），stop 是上次看到或挪到的止损 */
    static final class Tracked {
        final AiTraderTrail rule;
        volatile BigDecimal best;
        volatile BigDecimal stop;
        /** 这条规则正在挪止损，别的 tick 先不管它 */
        final AtomicBoolean moving = new AtomicBoolean();

        Tracked(AiTraderTrail rule) {
            this.rule = rule;
            this.best = rule.getBestPrice();
            this.stop = rule.getStopPrice();
        }
    }

    /** 币 → 绑了仓位、在跟的规则。整份替换，读到的是快照 */
    private volatile Map<String, List<Tracked>> tracked = Map.of();

    @PostConstruct
    public void init() {
        reload();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[Trail] 订阅 {}，在跟 {} 条", MarketStreamChannels.PRICE,
                tracked.values().stream().mapToInt(List::size).sum());
    }

    @EventListener
    public void onChanged(TrailsChanged event) {
        reload();
    }

    synchronized void reload() {
        Map<String, List<Tracked>> bySymbol = new HashMap<>();
        for (AiTraderTrail t : trailStore.all()) {
            // 限价单还没成交的没有仓位，绑上了才跟
            if (t.getPositionId() != null) {
                bySymbol.computeIfAbsent(t.getSymbol(), k -> new ArrayList<>()).add(new Tracked(t));
            }
        }
        tracked = Map.copyOf(bySymbol);
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
            onPriceTick(symbol.trim().toUpperCase(Locale.ROOT), new BigDecimal(price));
        } catch (Exception e) {
            // 单条解析失败只丢本 tick，下一 tick 补上
            log.debug("[Trail] tick解析失败: {}", e.toString());
        }
    }

    /** 每个 tick：价格创了新的最有利价就记下，应有止损够一个步长了就去挪；sim 调用不占行情线程 */
    void onPriceTick(String symbol, BigDecimal price) {
        for (Tracked t : tracked.getOrDefault(symbol, List.of())) {
            boolean isLong = "LONG".equals(t.rule.getSide());
            BigDecimal best = t.best;
            boolean starting = best == null;
            if (starting) {
                // 设了启动价的等价格到；没设的从这个 tick 起算
                BigDecimal activation = t.rule.getActivationPrice();
                if (activation != null && (isLong ? price.compareTo(activation) < 0 : price.compareTo(activation) > 0)) {
                    continue;
                }
            } else if (isLong ? price.compareTo(best) <= 0 : price.compareTo(best) >= 0) {
                continue;
            }
            t.best = price;
            // 刚开始跟的那一下也要落库，重启后才知道已经启动过
            if ((starting || due(t, isLong)) && t.moving.compareAndSet(false, true)) {
                Thread.startVirtualThread(() -> {
                    try {
                        advance(t, isLong);
                    } catch (Exception e) {
                        log.warn("[Trail] 挪止损失败 traderId={} {} msg={}", t.rule.getTraderId(), symbol, e.toString());
                    } finally {
                        t.moving.set(false);
                    }
                });
            }
        }
    }

    /** 最有利价回撤 callbackPct 的位置：多单在下方，空单在上方 */
    static BigDecimal desiredStop(BigDecimal best, BigDecimal callbackPct, boolean isLong) {
        BigDecimal gap = best.multiply(callbackPct).divide(HUNDRED);
        return (isLong ? best.subtract(gap) : best.add(gap)).round(PRICE_DIGITS);
    }

    /** 应有止损比现挂的紧出一个步长了没有；仓位没挂着止损就算到了 */
    private static boolean due(Tracked t, boolean isLong) {
        if (t.stop == null) {
            return true;
        }
        BigDecimal best = t.best;
        BigDecimal desired = desiredStop(best, t.rule.getCallbackPct(), isLong);
        BigDecimal step = best.multiply(t.rule.getCallbackPct()).divide(HUNDRED).multiply(STEP_OF_CALLBACK);
        return (isLong ? desired.subtract(t.stop) : t.stop.subtract(desired)).compareTo(step) >= 0;
    }

    /** 够步长就把 sim 的止损挪到应有的位置，然后把跟到哪了落库；仓位不在了就结束这条规则 */
    void advance(Tracked t, boolean isLong) {
        AiTraderTrail rule = t.rule;
        if (due(t, isLong)) {
            BigDecimal desired = desiredStop(t.best, rule.getCallbackPct(), isLong);
            AiTrader trader = traderMapper.selectById(rule.getTraderId());
            FuturesPositionDTO pos = simTradeClient.getAllPositions(trader.getSimUserId()).stream()
                    .filter(p -> p.getId().equals(rule.getPositionId())).findFirst().orElse(null);
            if (pos == null) {
                rule.setBestPrice(t.best);
                rule.setStopPrice(t.stop);
                trailStore.endClosed(rule, userLangResolver.of(trader.getUserId()));
                return;
            }
            switch (StopOrders.move(simTradeClient, trader.getSimUserId(), pos, desired, pos.getMarkPrice())) {
                case StopOrders.Moved _ -> {
                    t.stop = desired;
                    log.info("[Trail] 止损跟到 traderId={} {} {} best={} stop={}",
                            rule.getTraderId(), rule.getSymbol(), rule.getSide(), t.best, desired);
                }
                // 现挂的比应有的还紧：记下它，等最有利价再往前走
                case StopOrders.Looser l -> t.stop = l.current();
                // 价格已经在应有止损的另一侧，sim 那张止损会自己触发
                case StopOrders.WrongSide _ -> {
                }
            }
        }
        trailStore.advance(rule.getId(), t.best, t.stop);
    }
}
