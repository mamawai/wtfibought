package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 撮合价格事件订阅者（sim 侧）。feed 把行情价格发到 {@link MarketStreamChannels#PRICE}，本消费者订阅后
 * 按 type 分发到现货/合约强平/合约结算撮合，替代原 feed 进程内直调——拆服务后 sim 进程靠它从 Redis 取价撮合。
 *
 * <p>可靠性（与原架构同级）：同机 Pub/Sub 正常不丢；空窗（本进程重启、feed 价格连接断连）统一走
 * {@link #recoverGap}——本进程启动时按 last-tick 算空窗，feed 侧连接重连时发 gap 事件带空窗过来，
 * 两条路都是拉这段的 1m K 线、逐单逐仓按创建时间过滤后补触发。
 * 撮合放虚拟线程执行，不阻塞 Redis 订阅线程（与原 BinanceWsClient 每价格起虚拟线程一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchPriceConsumer implements MessageListener {

    // 强平要 markPrice + currentPrice，后者从此 KV 读（与 BinanceWsClient 原逻辑一致）
    private static final String FUTURES_PRICE_KEY_PREFIX = "market:futures-price:";

    // 本进程最后一次处理 tick 的时刻，重启后据此算空窗从哪起；不设 TTL，重启要读得到
    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";
    private static final long LAST_TICK_WRITE_INTERVAL_MS = 5_000L;
    /** 补漏空窗上限：超过就当第一次启动，不补 */
    private static final long MAX_GAP_MS = 60 * 60_000L;

    private final RedisMessageListenerContainer listenerContainer;
    private final StringRedisTemplate redisTemplate;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final FuturesSettlementService futuresSettlementService;
    private final CrossLiquidationService crossLiquidationService;
    private final BinanceRestClient restClient;
    private final BinanceProperties props;

    /** last-tick 键上次写入时刻，节流用——每条 tick 都写 Redis 纯浪费 */
    private volatile long lastTickWriteMs = 0L;
    /** 启动补漏跑完没有：没跑完不许刷 last-tick，见 touchLastTick */
    private volatile boolean recovered = false;

    @PostConstruct
    public void init() {
        // 订阅生效后，每个 tick 都会走 onMessage → dispatch → touchLastTick，把 last-tick 刷成当前时刻。
        // 要是 last-tick 在那之后才读，读到的就是刚刚写进去的值，空窗算出来接近 0，停机那一段就补不上了。所以要"先读，再让 tick 进来"
        long last = readLastTick();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        long now = System.currentTimeMillis();
        if (last <= 0L || now - last > MAX_GAP_MS) {
            recovered = true;
            log.info("[MatchPrice] 订阅 {} 启动，{}，不补漏", MarketStreamChannels.PRICE,
                    last <= 0L ? "无 last-tick" : "空窗 " + (now - last) + "ms 超 1h");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                recoverGap("spot", last, now);
                recoverGap("futures", last, now);
            } finally {
                recovered = true;
            }
        });
        log.info("[MatchPrice] 订阅 {} 启动，启动补漏空窗 {}ms", MarketStreamChannels.PRICE, now - last);
    }

    /** 读不到（首次部署 / 键丢 / Redis 没起来）返回 0，调用方跳过补漏。 */
    private long readLastTick() {
        try {
            String v = redisTemplate.opsForValue().get(LAST_TICK_KEY);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("[MatchPrice] 读 last-tick 失败，跳过启动补漏: {}", e.toString());
            return 0L;
        }
    }

    /**
     * 空窗 [from, to] 补漏：拉这段的 1m K 线交给各撮合服务，逐单/逐仓按创建时间过滤后判穿越。
     * spot/futures 两侧分开，对应 feed 的现货连接与两条合约连接。超 1h 的空窗当第一次启动，不补。
     */
    private void recoverGap(String kind, long from, long to) {
        if (to - from > MAX_GAP_MS) {
            log.info("[MatchPrice] {} 空窗 {}ms 超 1h，不补漏", kind, to - from);
            return;
        }
        if ("spot".equals(kind)) {
            for (String symbol : props.getAllSpotSymbols()) {
                try {
                    cryptoOrderService.recoverGap(symbol, restClient.spotBars1m(symbol, from, to));
                } catch (Exception e) {
                    log.warn("[MatchPrice] 现货补漏失败 symbol={}: {}", symbol, e.toString());
                }
            }
            return;
        }
        if ("futures".equals(kind)) {
            for (String symbol : props.getAllFuturesSymbols()) {
                try {
                    List<KlineBar> fut = restClient.futuresBars1m(symbol, from, to);
                    List<KlineBar> mark = restClient.markBars1m(symbol, from, to);
                    futuresSettlementService.recoverGap(symbol, fut);
                    futuresLiquidationService.recoverGap(symbol, mark, fut);
                    crossLiquidationService.recoverGap(symbol, mark);
                } catch (Exception e) {
                    log.warn("[MatchPrice] 合约补漏失败 symbol={}: {}", symbol, e.toString());
                }
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 解析+撮合放虚拟线程，订阅线程快速返回
        Thread.startVirtualThread(() -> dispatch(body));
    }

    private void dispatch(String body) {
        try {
            JsonNode obj = MAPPER.readTree(body);
            String type = obj.path("type").asString(null);
            if (type == null) return;
            String symbol = obj.path("symbol").asString(null);
            // gap 事件没有 symbol，价格类才必填
            if (symbol == null && !"gap".equals(type)) return;
            switch (type) {
                case "spot" -> cryptoOrderService.onPriceUpdate(symbol, new BigDecimal(obj.path("price").asString(null)));
                case "futures" -> futuresSettlementService.onPriceUpdate(symbol, new BigDecimal(obj.path("price").asString(null)));
                case "markprice" -> {
                    BigDecimal mp = new BigDecimal(obj.path("price").asString(null));
                    String cp = redisTemplate.opsForValue().get(FUTURES_PRICE_KEY_PREFIX + symbol);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, mp, cp != null ? new BigDecimal(cp) : mp);
                    crossLiquidationService.onPriceTick(symbol, mp);
                }
                case "gap" -> recoverGap(obj.path("kind").asString(null),
                        obj.path("from").asLong(), obj.path("to").asLong());
                default -> { /* 非撮合 type 忽略 */ }
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
