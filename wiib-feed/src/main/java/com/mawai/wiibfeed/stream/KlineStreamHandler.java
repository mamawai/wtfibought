package com.mawai.wiibfeed.stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibfeed.KlineStreamCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 5m K线收盘流：@kline_5m，仅取已收盘 bar（k.x=true）→ KlineStreamCache。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamCache klineStreamCache;

    @Override public String name() { return "Kline5m"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "kline_5m");
    }

    @Override
    public void onMessage(String raw) {
        try {
            JSONObject root = JSON.parseObject(raw);
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                data = root;
            }
            JSONObject k = data.getJSONObject("k");
            if (k == null || !k.getBooleanValue("x")) {
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
        } catch (Exception e) {
            log.warn("[KlineWS] 解析失败: {}", e.getMessage());
        }
    }
}
