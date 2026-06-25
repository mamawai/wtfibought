package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.ForceOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 全市场强平（爆仓）流：@forceOrder 稀疏事件，解析后入库。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForceOrderStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final ForceOrderService forceOrderService;

    @Override public String name() { return "ForceOrder"; }

    // forceOrder 是稀疏事件流（爆仓发生时才推），不设静默阈值
    @Override public long maxIdleSeconds() { return 0; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "forceOrder");
    }

    @Override
    public void onMessage(String raw) {
        int oIdx = raw.indexOf("\"o\":{");
        String arg = raw.length() > 300 ? raw.substring(0, 300) : raw;
        if (oIdx < 0) {
            log.warn("[ForceOrder] 消息缺少o字段: {}", arg);
            return;
        }

        int from = oIdx + 5;

        String symbol = StreamParse.extractField(raw, "\"s\":\"", from);
        String side = StreamParse.extractField(raw, "\"S\":\"", from);
        String price = StreamParse.extractField(raw, "\"p\":\"", from);
        String avgPrice = StreamParse.extractField(raw, "\"ap\":\"", from);
        String qty = StreamParse.extractField(raw, "\"q\":\"", from);
        if (symbol == null || side == null || price == null || avgPrice == null || qty == null) {
            log.warn("[ForceOrder] 字段解析失败: {}", arg);
            return;
        }

        String status = StreamParse.extractField(raw, "\"X\":\"", from);
        if (status == null) status = "FILLED";

        long tt = System.currentTimeMillis();
        int tIdx = raw.indexOf("\"T\":", from);
        if (tIdx >= 0) {
            int tStart = tIdx + 4;
            int tEnd = raw.indexOf(',', tStart);
            if (tEnd < 0) tEnd = raw.indexOf('}', tStart);
            if (tEnd > tStart) tt = Long.parseLong(raw.substring(tStart, tEnd));
        }
        final String finalStatus = status;
        final long tradeTime = tt;

        Thread.startVirtualThread(() -> {
            try {
                forceOrderService.handleForceOrder(symbol, side,
                        new BigDecimal(price), new BigDecimal(avgPrice),
                        new BigDecimal(qty), finalStatus, tradeTime);
            } catch (Exception e) {
                log.warn("爆仓入库异常 {} {}: {}", symbol, side, e.getMessage());
            }
        });
    }
}
