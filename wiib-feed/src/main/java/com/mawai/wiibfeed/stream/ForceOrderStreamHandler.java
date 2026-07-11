package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.ForceOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全市场强平（爆仓）流：!forceOrder@arr，白名单过滤后入库。属 /market 端点。
 *
 * <p>订全市场而非逐 symbol 的原因：逐 symbol 强平消息稀疏到无法用静默判连接死活
 * （maxIdle 只能设 0，静默死连接无人发现，曾因此丢 2026-04 半月/2026-06 整月数据）；
 * 全市场每几秒必有强平，静默检测重新有意义。非白名单消息只喂看门狗心跳、不入库。</p>
 */
@Slf4j
@Component
public class ForceOrderStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final ForceOrderService forceOrderService;

    /** 入库白名单 = 配置币池（@ConfigurationProperties 构造注入时绑定已完成，直接建） */
    private final Set<String> whitelist;

    ForceOrderStreamHandler(BinanceProperties props, ForceOrderService forceOrderService) {
        this.props = props;
        this.forceOrderService = forceOrderService;
        this.whitelist = props.getSymbols().stream()
                .map(sym -> sym.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override public String name() { return "ForceOrder"; }

    // 全市场流每几秒必有消息，静默>120s 即视为死连接主动重连
    @Override public long maxIdleSeconds() { return 120; }

    @Override
    public String buildUrl() {
        return StreamUrls.rawStreamUrl(props.getFuturesWsUrl(), "market", "!forceOrder@arr");
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
        // 白名单外的币只当看门狗心跳（时间戳已在 WsConnection 更新），不入库
        if (symbol == null || !whitelist.contains(symbol.toUpperCase(Locale.ROOT))) return;

        String side = StreamParse.extractField(raw, "\"S\":\"", from);
        String price = StreamParse.extractField(raw, "\"p\":\"", from);
        String avgPrice = StreamParse.extractField(raw, "\"ap\":\"", from);
        String qty = StreamParse.extractField(raw, "\"q\":\"", from);
        if (side == null || price == null || avgPrice == null || qty == null) {
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
