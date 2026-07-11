package com.mawai.wiibfeed;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KlineStreamCache {

    /** Stream 近似上限，防无限增长；约覆盖多日数据，足够 consumer 重启回溯。 */
    private static final long STREAM_MAXLEN = 10_000L;

    private final KlineHistoryStore historyStore;
    private final StringRedisTemplate redisTemplate;

    public void onClosedBar(String symbol, String interval, KlineBar bar) {
        // 调用方 KlineStreamHandler 已保证三参非空，这里只做大小写归一
        String normalizedSymbol = symbol.trim().toUpperCase();
        String normalizedInterval = interval.trim().toLowerCase();
        if (KlineHistoryStore.DEFAULT_INTERVAL.equals(normalizedInterval)) {
            // 异步落库，不占WS回调线程；下游预测管线经HTTP采集(秒级)后才读historyStore，无竞态
            Thread.startVirtualThread(() -> persistDefaultInterval(normalizedSymbol, normalizedInterval, bar));
        }
        // 收盘 → Redis Stream（唯一实时通道）：quant 侧 KlineStreamConsumer 消费后 republish 本地事件，触发预测/策略
        publishToStream(normalizedSymbol, normalizedInterval, bar);
        log.info("[KlineWS] closed symbol={} interval={} closeTime={}", normalizedSymbol, normalizedInterval, bar.closeTime());
    }

    private void persistDefaultInterval(String symbol, String interval, KlineBar bar) {
        try {
            historyStore.saveClosedBar(symbol, interval, bar);
        } catch (Exception e) {
            log.warn("[KlineWS] 5m落库失败 symbol={} closeTime={} msg={}", symbol, bar.closeTime(), e.toString());
        }
    }

    /**
     * 把收盘 bar 写入 Redis Stream——quant（含独立部署后）获取实时收盘的唯一通道。
     * 失败仅 log error 不阻断：K线已落库（历史不丢），丢的仅本次实时触发，下一根 5m 后自然恢复。
     * 字段契约（消费端 KlineStreamConsumer 按此重建 KlineBar）：
     * symbol/interval/openTime/closeTime/open/high/low/close/volume，全部 String。
     */
    private void publishToStream(String symbol, String interval, KlineBar bar) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("symbol", symbol);
            fields.put("interval", interval);
            fields.put("openTime", Long.toString(bar.openTime()));
            fields.put("closeTime", Long.toString(bar.closeTime()));
            fields.put("open", bar.open().toPlainString());
            fields.put("high", bar.high().toPlainString());
            fields.put("low", bar.low().toPlainString());
            fields.put("close", bar.close().toPlainString());
            fields.put("volume", bar.volume().toPlainString());
            var ops = redisTemplate.opsForStream();
            ops.add(StreamRecords.newRecord().in(MarketStreamChannels.KLINE_CLOSED_STREAM).ofMap(fields));
            ops.trim(MarketStreamChannels.KLINE_CLOSED_STREAM, STREAM_MAXLEN, true);  // 近似裁剪，防 Stream 无限增长
        } catch (Exception e) {
            log.error("[KlineStream] 写 Stream 失败 symbol={} closeTime={} msg={}", symbol, bar.closeTime(), e.toString());
        }
    }

}
