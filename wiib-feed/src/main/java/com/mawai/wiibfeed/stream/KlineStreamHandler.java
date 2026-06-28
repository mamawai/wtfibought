package com.mawai.wiibfeed.stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibfeed.KlineStreamCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 5m K线流：@kline_5m。每次更新(含未收盘)→广播给前端蜡烛图；收盘 bar→KlineStreamCache 喂策略。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamCache klineStreamCache;
    private final MarketBroadcaster broadcaster;

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
            }
        } catch (Exception e) {
            log.warn("[KlineWS] 解析失败: {}", e.getMessage());
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
