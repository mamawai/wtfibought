package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.handover.DeliveryGate;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 撮合入口（sim 侧）。标记价走 {@link MarketStreamChannels#PRICE}：本消费者订阅后做逐仓强平和全仓精查；
 * 限价单、止损、止盈看成交价，由 {@link TradeStreamConsumer} 读成交 Stream。
 *
 * <p>空窗：上任时按 last-tick 拉 mark 1m K 线补强平（空窗 10 秒内的不补，交接上任就是这种）；feed 连接重连发 gap 事件带空窗过来——
 * mark（标记价连接）同样补强平，futures/spot（成交连接）交给 TradeStreamConsumer 拉成交价 K 线补触发。
 * 处理交给后台执行器，不阻塞 Redis 订阅线程。</p>
 *
 * <p>进活的口：开口是订阅标记价（和启动补漏），关口是退订、等正在回调的那几条交出去。</p>
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class MatchPriceConsumer implements MessageListener, Intake {

    // 本进程最后一次处理 tick 的时刻，重启后据此算空窗从哪起；不设 TTL，重启要读得到
    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";
    private static final long LAST_TICK_WRITE_INTERVAL_MS = 5_000L;
    /** 补漏空窗上限：超过就当第一次启动，不补；成交那边同一个口径 */
    private static final long MAX_GAP_MS = TradeStreamConsumer.MAX_GAP_MS;
    /** 空窗不到这么久不补漏：交接上任时的空窗约 1 秒 */
    static final long MIN_GAP_MS = 10_000L;

    private final RedisMessageListenerContainer listenerContainer;
    private final StringRedisTemplate redisTemplate;
    private final FuturesLiquidationService futuresLiquidationService;
    private final CrossLiquidationService crossLiquidationService;
    private final TradeStreamConsumer tradeStreamConsumer;
    private final BinanceRestClient restClient;
    private final BinanceProperties props;
    /** 会改账的后台活交给它，关停时等它清零 */
    private final Executor executor;
    /** 标记价回调的闸 */
    private final DeliveryGate gate = new DeliveryGate();

    /** last-tick 键上次写入时刻，节流用——每条 tick 都写 Redis 纯浪费 */
    private volatile long lastTickWriteMs = 0L;
    /** 启动补漏跑完没有：没跑完不许刷 last-tick，见 touchLastTick */
    private volatile boolean recovered = false;

    @Override
    public void open(Start how) {
        // 订阅生效后，每个 tick 都会走 onMessage → dispatch → touchLastTick，把 last-tick 刷成当前时刻。
        // 要是 last-tick 在那之后才读，读到的就是刚刚写进去的值，空窗算出来接近 0，停机那一段就补不上了。所以要"先读，再让 tick 进来"。
        // 成交流的口开在这之前，读到的是同一个值
        long last = lastAlive(redisTemplate);
        gate.open();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        long now = System.currentTimeMillis();
        if (last <= 0L || now - last > MAX_GAP_MS || now - last <= MIN_GAP_MS) {
            recovered = true;
            log.info("[MatchPrice] 订阅 {} 启动，{}，不补漏", MarketStreamChannels.PRICE,
                    last <= 0L ? "无 last-tick"
                            : now - last > MAX_GAP_MS ? "空窗 " + (now - last) + "ms 超 1h" : "空窗 " + (now - last) + "ms 不到 10 秒");
            return;
        }
        executor.execute(() -> {
            try {
                recoverGap("mark", last, now);
            } finally {
                recovered = true;
            }
        });
        log.info("[MatchPrice] 订阅 {} 启动，启动补漏空窗 {}ms", MarketStreamChannels.PRICE, now - last);
    }

    /**
     * 关口：退订标记价，等正在回调的那几条交给后台执行器（交出去的处理由它数着），再把 last-tick 记成现在，
     * 接手的实例据此算空窗；启动补漏没跑完不记
     */
    @Override
    public void close() {
        listenerContainer.removeMessageListener(this);
        gate.close();
        if (recovered) {
            redisTemplate.opsForValue().set(LAST_TICK_KEY, String.valueOf(System.currentTimeMillis()));
        }
    }

    /** sim 上次活着的时刻（最后一次处理 tick）。读不到（首次部署 / 键丢 / Redis 没起来）返回 0，调用方当不知道停了多久 */
    static long lastAlive(StringRedisTemplate redisTemplate) {
        try {
            String v = redisTemplate.opsForValue().get(LAST_TICK_KEY);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("[MatchPrice] 读 last-tick 失败，当不知道停了多久: {}", e.toString());
            return 0L;
        }
    }

    /** 空窗 [from, to] 补漏，kind 是断过的那条连接：mark 标记价，futures/spot 成交。超 1h 的空窗当第一次启动，不补 */
    private void recoverGap(String kind, long from, long to) {
        if (to - from > MAX_GAP_MS) {
            log.info("[MatchPrice] {} 空窗 {}ms 超 1h，不补漏", kind, to - from);
            return;
        }
        switch (kind) {
            case "mark" -> recoverMark(from, to);
            case "futures" -> tradeStreamConsumer.recoverGap(true, from, to);
            case "spot" -> tradeStreamConsumer.recoverGap(false, from, to);
            case null, default -> log.warn("[MatchPrice] 不认识的空窗 kind={}", kind);
        }
    }

    /** 标记价空窗：拉 mark 1m K 线，逐仓强平、全仓精查各补一遍 */
    private void recoverMark(long from, long to) {
        for (String symbol : props.getAllFuturesSymbols()) {
            try {
                List<KlineBar> mark = restClient.markBars1m(symbol, from, to);
                futuresLiquidationService.recoverGap(symbol, mark);
                crossLiquidationService.recoverGap(symbol, mark);
            } catch (Exception e) {
                log.warn("[MatchPrice] 标记价补漏失败 symbol={}: {}", symbol, e.toString());
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        gate.pass(() -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            // 解析+撮合交给后台执行器，订阅线程快速返回
            executor.execute(() -> dispatch(body));
        });
    }

    private void dispatch(String body) {
        try {
            JsonNode obj = MAPPER.readTree(body);
            String type = obj.path("type").asString(null);
            if (type == null) return;
            switch (type) {
                case "markprice" -> {
                    String symbol = obj.path("symbol").asString(null);
                    if (symbol == null) return;
                    BigDecimal mp = new BigDecimal(obj.path("price").asString(null));
                    futuresLiquidationService.checkOnPriceUpdate(symbol, mp);
                    crossLiquidationService.onPriceTick(symbol, mp);
                }
                case "gap" -> recoverGap(obj.path("kind").asString(null),
                        obj.path("from").asLong(), obj.path("to").asLong());
                default -> { /* futures 最新价 tick 是给 quant 的，sim 不用 */ }
            }
            // 放撮合之后：写键失败不能挡住撮合
            touchLastTick();
        } catch (Exception e) {
            log.warn("[MatchPrice] 处理失败 body={} msg={}", body, e.toString());
        }
    }

    /** 记"活到几点了"：节流 5s 写一次，下次重启拿它算空窗从哪起 */
    private void touchLastTick() {
        // 补漏没跑完先不刷：补漏是几十个 symbol 串行 REST，几十秒起步，这中间进程再挂（部署崩溃循环）
        // 下次读到的空窗就只剩这几十秒，本该补的那段窗口再也补不回来
        if (!recovered) return;
        long now = System.currentTimeMillis();
        if (now - lastTickWriteMs < LAST_TICK_WRITE_INTERVAL_MS) return;
        lastTickWriteMs = now;
        redisTemplate.opsForValue().set(LAST_TICK_KEY, String.valueOf(now));
    }
}
