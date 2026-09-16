package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
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
 * <p>可靠性（与原架构同级）：同机 Pub/Sub 正常不丢；sim 重启期间错过的价格穿越，由
 * {@link #recoverOnStartup} 启动时 REST 拉区间高低价补触发；feed 侧 WS 重连也会发 *-recover 事件补漏。
 * 撮合放虚拟线程执行，不阻塞 Redis 订阅线程（与原 BinanceWsClient 每价格起虚拟线程一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchPriceConsumer implements MessageListener {

    // 强平要 markPrice + currentPrice，后者从此 KV 读（与 BinanceWsClient 原逻辑一致）
    private static final String FUTURES_PRICE_KEY_PREFIX = "market:futures-price:";

    // 本进程最后一次处理 tick 的时刻，重启后据此算停机多久、该回看多少 K 线；不设 TTL，重启要读得到
    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";
    private static final long LAST_TICK_WRITE_INTERVAL_MS = 5_000L;
    /** 回看上限：Binance K 线单次最多 1000 根 */
    private static final int RECOVER_MAX_MINUTES = 1000;

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
        // 先算停机时长再订阅：订阅一开，进来的 tick 立刻把 last-tick 刷成当前时刻，读晚了就算不出真实停机多久
        int minutes = downtimeMinutes();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        Thread.startVirtualThread(() -> {
            try {
                recoverOnStartup(minutes);
            } finally {
                recovered = true;
            }
        });
        log.info("[MatchPrice] 订阅 {} 启动，启动补漏回看 {}", MarketStreamChannels.PRICE,
                minutes > 0 ? minutes + " 分钟" : "短窗（无 last-tick）");
    }

    /**
     * 停机时长换算成要回看的 1m K 线根数。读不到 last-tick（首次部署 / 键丢 / Redis 没起来）返回 0，
     * 调用方退回 feed 同款固定短窗。
     */
    private int downtimeMinutes() {
        long last;
        try {
            String v = redisTemplate.opsForValue().get(LAST_TICK_KEY);
            if (v == null) return 0;
            last = Long.parseLong(v);
        } catch (Exception e) {
            log.warn("[MatchPrice] 读 last-tick 失败，启动补漏退回短窗: {}", e.toString());
            return 0;
        }
        long downtime = System.currentTimeMillis() - last;
        if (downtime <= 0) return 0;
        // +2 根缓冲：K 线按整分对齐，停机的头尾各压着半根，少拉就漏
        long n = (downtime + 59_999) / 60_000 + 2;
        if (n > RECOVER_MAX_MINUTES) {
            log.warn("[MatchPrice] 停机 {} 分钟超出 K 线单次上限，只回看最近 {} 分钟",
                    downtime / 60_000, RECOVER_MAX_MINUTES);
            return RECOVER_MAX_MINUTES;
        }
        return (int) n;
    }

    /**
     * sim 启动补漏：拉区间高低价补触发现货/合约限价单 + 强平，覆盖本进程宕机期间错过的价格穿越。
     * minutes>0 按真实停机时长回看 1m K 线；=0（读不到 last-tick）退回 feed 同款固定短窗。
     * 合约侧遍历合约全集（含金/油/TradFi 纯合约标的），现货侧只有 crypto 有现货。
     */
    private void recoverOnStartup(int minutes) {
        List<String> spotSymbols = props.getSymbols() == null ? List.of() : props.getSymbols();
        for (String symbol : props.getAllFuturesSymbols()) {
            try {
                if (spotSymbols.contains(symbol)) {
                    BigDecimal[] spot = minutes > 0
                            ? restClient.getSpotHighLowByMinutes(symbol, minutes)
                            : restClient.getRecentHighLow(symbol);
                    if (spot != null) cryptoOrderService.recoverLimitOrders(symbol, spot[0], spot[1]);
                }
                BigDecimal[] fut = minutes > 0
                        ? restClient.getFuturesHighLowByMinutes(symbol, minutes)
                        : restClient.getRecentFuturesHighLow(symbol);
                if (fut != null) futuresSettlementService.recoverLimitOrders(symbol, fut[0], fut[1]);
                // 合约区间直接传给强平复用，别再拉一次（回看上千根时这一次重复请求的权重不便宜）
                recoverLiquidationFromRest(symbol, minutes, fut);
            } catch (Exception e) {
                log.warn("[MatchPrice] 启动补漏失败 symbol={}: {}", symbol, e.toString());
            }
        }
    }

    /** 强平补漏：拉 markPrice 区间，低点查多头爆、高点查空头爆（与原 recoverMissedLiquidations 一致）。 */
    private void recoverLiquidationFromRest(String symbol, int minutes, BigDecimal[] fut) {
        BigDecimal[] mark = minutes > 0
                ? restClient.getMarkPriceHighLowByMinutes(symbol, minutes)
                : restClient.getRecentMarkPriceHighLow(symbol);
        if (mark == null) return;
        BigDecimal futLow = fut != null ? fut[0] : mark[0];
        BigDecimal futHigh = fut != null ? fut[1] : mark[1];
        checkLiquidationRange(symbol, mark[0], mark[1], futLow, futHigh);
    }

    private void checkLiquidationRange(String symbol, BigDecimal markLow, BigDecimal markHigh,
                                       BigDecimal futLow, BigDecimal futHigh) {
        futuresLiquidationService.checkOnPriceUpdate(symbol, markLow, futLow);
        futuresLiquidationService.checkOnPriceUpdate(symbol, markHigh, futHigh);
        // 全仓补两端：空窗里的插针藏在区间高低点（equity 对单 symbol 价格线性 → 端点即最坏情形），
        // 低端抓多头重的账户、高端抓空头重的，钉价语义与实时 tick 一致
        crossLiquidationService.onPriceTick(symbol, markLow);
        crossLiquidationService.onPriceTick(symbol, markHigh);
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
            String symbol = obj.path("symbol").asString(null);
            String type = obj.path("type").asString(null);
            if (symbol == null || type == null) return;
            switch (type) {
                case "spot" -> cryptoOrderService.onPriceUpdate(symbol, new BigDecimal(obj.path("price").asString(null)));
                case "futures" -> futuresSettlementService.onPriceUpdate(symbol, new BigDecimal(obj.path("price").asString(null)));
                case "markprice" -> {
                    BigDecimal mp = new BigDecimal(obj.path("price").asString(null));
                    String cp = redisTemplate.opsForValue().get(FUTURES_PRICE_KEY_PREFIX + symbol);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, mp, cp != null ? new BigDecimal(cp) : mp);
                    crossLiquidationService.onPriceTick(symbol, mp);
                }
                case "spot-recover" -> cryptoOrderService.recoverLimitOrders(symbol,
                        new BigDecimal(obj.path("low").asString(null)), new BigDecimal(obj.path("high").asString(null)));
                case "futures-recover" -> futuresSettlementService.recoverLimitOrders(symbol,
                        new BigDecimal(obj.path("low").asString(null)), new BigDecimal(obj.path("high").asString(null)));
                case "liq-recover" -> checkLiquidationRange(symbol,
                        new BigDecimal(obj.path("markLow").asString(null)), new BigDecimal(obj.path("markHigh").asString(null)),
                        new BigDecimal(obj.path("futLow").asString(null)), new BigDecimal(obj.path("futHigh").asString(null)));
                default -> { /* 非撮合 type（如 markprice 也被 quant 哨兵消费）忽略 */ }
            }
            // 放撮合之后：写键失败不能挡住撮合
            touchLastTick();
        } catch (Exception e) {
            log.warn("[MatchPrice] 处理失败 body={} msg={}", body, e.toString());
        }
    }

    /** 记"活到几点了"：节流 5s 写一次，下次重启拿它算停机多久 */
    private void touchLastTick() {
        // 补漏没跑完先不刷：补漏是几十个 symbol 串行 REST，几十秒起步，这中间进程再挂（部署崩溃循环）
        // 下次读到的停机时长就只剩这几十秒，本该补的那段窗口再也补不回来
        if (!recovered) return;
        long now = System.currentTimeMillis();
        if (now - lastTickWriteMs < LAST_TICK_WRITE_INTERVAL_MS) return;
        lastTickWriteMs = now;
        redisTemplate.opsForValue().set(LAST_TICK_KEY, String.valueOf(now));
    }
}
