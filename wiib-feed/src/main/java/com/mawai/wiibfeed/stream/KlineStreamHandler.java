package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibfeed.KlineStreamCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/** 5m K线流：@kline_5m。每次更新(含未收盘)→广播给前端蜡烛图；收盘 bar→KlineStreamCache 喂策略。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamCache klineStreamCache;
    private final MarketBroadcaster broadcaster;

    @Override public String name() { return "Kline5m"; }
    @Override public long maxIdleSeconds() { return 90; }

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
            JsonNode root = MAPPER.readTree(raw);
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                data = root;
            }
            JsonNode k = data.path("k");
            if (!k.isObject()) {
                return;
            }
            String symbol = data.path("s").asString(null);
            if (symbol == null || symbol.isBlank()) {
                symbol = k.path("s").asString(null);
            }
            String interval = k.path("i").asString(null);
            if (symbol == null || interval == null) {
                return;
            }

            broadcastKline(symbol, k);   // 每次更新都广播：前端蜡烛图实时长 + 量/额实时

            if (feedStrategy && k.path("x").asBoolean(false)) {
                KlineBar bar = new KlineBar(
                        k.path("t").asLong(0),
                        k.path("T").asLong(0),
                        k.get("o").asDecimal(),
                        k.get("h").asDecimal(),
                        k.get("l").asDecimal(),
                        k.get("c").asDecimal(),
                        k.get("v").asDecimal()
                );
                klineStreamCache.onClosedBar(symbol, interval, bar);
            }
        } catch (Exception e) {
            log.warn("[KlineWS] 解析失败: {}", e.getMessage());
        }
    }

    /** 字段契约(前端 useKlineStream 按此解析)：i=interval, t=开盘ms, o/h/l/c, v=量, q=额, x=是否收盘。 */
    private void broadcastKline(String symbol, JsonNode k) {
        String json = "{\"i\":\"" + k.path("i").asString(null) + "\""
                + ",\"t\":" + k.path("t").asLong(0)
                + ",\"o\":\"" + k.path("o").asString(null) + "\",\"h\":\"" + k.path("h").asString(null) + "\""
                + ",\"l\":\"" + k.path("l").asString(null) + "\",\"c\":\"" + k.path("c").asString(null) + "\""
                + ",\"v\":\"" + k.path("v").asString(null) + "\",\"q\":\"" + k.path("q").asString(null) + "\""
                + ",\"x\":" + k.path("x").asBoolean(false) + "}";
        broadcaster.broadcastKline(symbol, json);
    }
}
