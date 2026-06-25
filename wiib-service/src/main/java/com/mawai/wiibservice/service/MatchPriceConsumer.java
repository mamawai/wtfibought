package com.mawai.wiibservice.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.config.BinanceProperties;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

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

    private final RedisMessageListenerContainer listenerContainer;
    private final StringRedisTemplate redisTemplate;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final FuturesSettlementService futuresSettlementService;
    private final BinanceRestClient restClient;
    private final BinanceProperties props;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        Thread.startVirtualThread(this::recoverOnStartup);
        log.info("[MatchPrice] 订阅 {} 启动", MarketStreamChannels.PRICE);
    }

    /** sim 启动补漏：拉区间高低价补触发现货/合约限价单 + 强平，覆盖本进程宕机期间错过的价格穿越。 */
    private void recoverOnStartup() {
        if (props.getSymbols() == null) return;
        for (String symbol : props.getSymbols()) {
            try {
                BigDecimal[] spot = restClient.getRecentHighLow(symbol);
                if (spot != null) cryptoOrderService.recoverLimitOrders(symbol, spot[0], spot[1]);
                BigDecimal[] fut = restClient.getRecentFuturesHighLow(symbol);
                if (fut != null) futuresSettlementService.recoverLimitOrders(symbol, fut[0], fut[1]);
                recoverLiquidationFromRest(symbol);
            } catch (Exception e) {
                log.warn("[MatchPrice] 启动补漏失败 symbol={}: {}", symbol, e.toString());
            }
        }
    }

    /** 强平补漏：拉 markPrice/futures 区间，低点查多头爆、高点查空头爆（与原 recoverMissedLiquidations 一致）。 */
    private void recoverLiquidationFromRest(String symbol) {
        BigDecimal[] mark = restClient.getRecentMarkPriceHighLow(symbol);
        if (mark == null) return;
        BigDecimal[] fut = restClient.getRecentFuturesHighLow(symbol);
        BigDecimal futLow = fut != null ? fut[0] : mark[0];
        BigDecimal futHigh = fut != null ? fut[1] : mark[1];
        checkLiquidationRange(symbol, mark[0], mark[1], futLow, futHigh);
    }

    private void checkLiquidationRange(String symbol, BigDecimal markLow, BigDecimal markHigh,
                                       BigDecimal futLow, BigDecimal futHigh) {
        futuresLiquidationService.checkOnPriceUpdate(symbol, markLow, futLow);
        futuresLiquidationService.checkOnPriceUpdate(symbol, markHigh, futHigh);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 解析+撮合放虚拟线程，订阅线程快速返回
        Thread.startVirtualThread(() -> dispatch(body));
    }

    private void dispatch(String body) {
        try {
            JSONObject obj = JSON.parseObject(body);
            String symbol = obj.getString("symbol");
            String type = obj.getString("type");
            if (symbol == null || type == null) return;
            switch (type) {
                case "spot" -> cryptoOrderService.onPriceUpdate(symbol, new BigDecimal(obj.getString("price")));
                case "futures" -> futuresSettlementService.onPriceUpdate(symbol, new BigDecimal(obj.getString("price")));
                case "markprice" -> {
                    BigDecimal mp = new BigDecimal(obj.getString("price"));
                    String cp = redisTemplate.opsForValue().get(FUTURES_PRICE_KEY_PREFIX + symbol);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, mp, cp != null ? new BigDecimal(cp) : mp);
                }
                case "spot-recover" -> cryptoOrderService.recoverLimitOrders(symbol,
                        new BigDecimal(obj.getString("low")), new BigDecimal(obj.getString("high")));
                case "futures-recover" -> futuresSettlementService.recoverLimitOrders(symbol,
                        new BigDecimal(obj.getString("low")), new BigDecimal(obj.getString("high")));
                case "liq-recover" -> checkLiquidationRange(symbol,
                        new BigDecimal(obj.getString("markLow")), new BigDecimal(obj.getString("markHigh")),
                        new BigDecimal(obj.getString("futLow")), new BigDecimal(obj.getString("futHigh")));
                default -> { /* 非撮合 type（如 markprice 也被 quant 哨兵消费）忽略 */ }
            }
        } catch (Exception e) {
            log.warn("[MatchPrice] 处理失败 body={} msg={}", body, e.toString());
        }
    }
}
