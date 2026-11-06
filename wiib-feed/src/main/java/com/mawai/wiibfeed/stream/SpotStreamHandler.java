package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 现货 miniTicker 流（重 handler）：解析最新价写 Redis/缓存/广播，断开时给前端广播断线帧。
 * 现货限价单看成交价，走 {@link SpotAggTradeStreamHandler} 那条成交 Stream。
 */
@Component
@RequiredArgsConstructor
public class SpotStreamHandler implements StreamHandler {

    private static final String REDIS_KEY_PREFIX = "market:price:";

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;

    private WsConnection conn;

    @Override public String name() { return "Spot"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
    }

    private boolean isConnected() {
        return conn != null && conn.isConnected();
    }

    @Override
    public String buildUrl() {
        return StreamUrls.spotUrl(props.getWsUrl(), props.getAllSpotSymbols(), "miniTicker");
    }

    /** 断线帧带缓存价（前端现货分支要 price 字段），价缺就跳过该 symbol */
    @Override
    public void onDisconnected() {
        long ts = System.currentTimeMillis();
        for (String symbol : props.getAllSpotSymbols()) {
            BigDecimal price = cacheService.getCryptoPrice(symbol);
            if (price == null) continue;
            broadcastService.broadcastCryptoQuote(symbol,
                    "{\"price\":\"" + price.toPlainString() + "\",\"ts\":" + ts + ",\"ws\":false}");
        }
    }

    @Override
    public void onMessage(String raw) {
        // 手动indexOf解析避免Jackson反序列化开销
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        int cIdx = raw.indexOf("\"c\":\"");
        if (cIdx < 0) return;
        String price = StreamParse.extractQuoted(raw, cIdx + 5);

        // 提取服务端时间戳，缺失/异常则取本地时间
        int eIdx = raw.indexOf("\"E\":");
        long ts = StreamParse.getEventTime(raw, eIdx, System.currentTimeMillis());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, price);
        BigDecimal bd = new BigDecimal(price);
        cacheService.putCryptoPrice(symbol, bd);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts
                + ",\"ws\":" + isConnected() + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);
    }
}
