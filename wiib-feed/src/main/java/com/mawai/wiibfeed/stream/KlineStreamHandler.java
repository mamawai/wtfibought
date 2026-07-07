package com.mawai.wiibfeed.stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibfeed.KlineStreamCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/** 5m K线流：@kline_5m。每次更新(含未收盘)→广播给前端蜡烛图；收盘 bar→KlineStreamCache 喂策略 + taker买量落Redis(LiqFade签名用)。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineStreamHandler implements StreamHandler {

    // 5m 桶 taker 买量（base 量纲），每桶一键：market:takerbuy5m:<SYM>:<openMs>，值=V 原串。
    // quant RedisLiqSideData 按 openMs 直查；TTL 即保留窗口——LiqFade 只用最近 3 根，2h≈24 根纯冗余
    private static final String REDIS_TAKER_BUY_KEY_PREFIX = "market:takerbuy5m:";
    private static final Duration TAKER_BUY_TTL = Duration.ofHours(2);

    private final BinanceProperties props;
    private final KlineStreamCache klineStreamCache;
    private final MarketBroadcaster broadcaster;
    private final StringRedisTemplate redisTemplate;

    @Override public String name() { return "Kline5m"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "kline_5m");
    }

    @Override
    public void onMessage(String raw) {
        handle(raw, true);   // 本(5m)连接：广播 + 收盘喂策略
    }

    /**
     * 解析一条 kline 消息：恒广播当前根(含未收盘)给前端；feedStrategy 时再把收盘 bar 喂策略。
     * 15m 副连接 {@link Kline15mStreamHandler} 复用本方法并传 feedStrategy=false——只广播，
     * 绝不进策略 Stream（KlineStreamCache 会触发 5m 预测/策略，混入 15m 会污染）。
     */
    public void handle(String raw, boolean feedStrategy) {
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                data = root;
            }
            JSONObject k = data.getJSONObject("k");
            if (k == null) {
                return;
            }
            String symbol = data.getString("s");
            if (symbol == null || symbol.isBlank()) {
                symbol = k.getString("s");
            }
            String interval = k.getString("i");
            if (symbol == null || interval == null) {
                return;
            }

            broadcastKline(symbol, k);   // 每次更新都广播：前端蜡烛图实时长 + 量/额实时

            if (feedStrategy && k.getBooleanValue("x")) {
                KlineBar bar = new KlineBar(
                        k.getLongValue("t"),
                        k.getLongValue("T"),
                        new BigDecimal(k.getString("o")),
                        new BigDecimal(k.getString("h")),
                        new BigDecimal(k.getString("l")),
                        new BigDecimal(k.getString("c")),
                        new BigDecimal(k.getString("v"))
                );
                klineStreamCache.onClosedBar(symbol, interval, bar);
                // 只在 5m 收盘路径写（15m 副连接 feedStrategy=false 天然进不来，不会污染 5m 桶）；
                // KlineBar 不扩字段——taker 买量走 LiqSideData 侧通道，回测/落库/广播全都不动
                storeTakerBuy(symbol, k);
            }
        } catch (Exception e) {
            log.warn("[KlineWS] 解析失败: {}", e.getMessage());
        }
    }

    /** 收盘桶 taker 买量落 Redis（V=taker买base量）；I/O 单独 try，失败只丢这根采样（下游 NaN），日志归因准确。 */
    private void storeTakerBuy(String symbol, JSONObject k) {
        try {
            redisTemplate.opsForValue().set(
                    REDIS_TAKER_BUY_KEY_PREFIX + symbol + ":" + k.getLongValue("t"), k.getString("V"), TAKER_BUY_TTL);
        } catch (Exception e) {
            log.warn("[KlineWS] taker买量写Redis失败 symbol={} openTime={} msg={}", symbol, k.getLongValue("t"), e.getMessage());
        }
    }

    /** 字段契约(前端 useKlineStream 按此解析)：i=interval, t=开盘ms, o/h/l/c, v=量, q=额, x=是否收盘。 */
    private void broadcastKline(String symbol, JSONObject k) {
        String json = "{\"i\":\"" + k.getString("i") + "\""
                + ",\"t\":" + k.getLongValue("t")
                + ",\"o\":\"" + k.getString("o") + "\",\"h\":\"" + k.getString("h") + "\""
                + ",\"l\":\"" + k.getString("l") + "\",\"c\":\"" + k.getString("c") + "\""
                + ",\"v\":\"" + k.getString("v") + "\",\"q\":\"" + k.getString("q") + "\""
                + ",\"x\":" + k.getBooleanValue("x") + "}";
        broadcaster.broadcastKline(symbol, json);
    }
}
