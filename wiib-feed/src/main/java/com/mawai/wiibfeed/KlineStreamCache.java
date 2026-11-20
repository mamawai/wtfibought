package com.mawai.wiibfeed;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
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
        // 另起虚拟线程，不占 WS 回调线程；先落库再发 Stream，下游收到事件时库里已有这根，落库失败也照发
        Thread.startVirtualThread(() -> {
            if (KlineHistoryStore.DEFAULT_INTERVAL.equals(normalizedInterval)) {
                persistDefaultInterval(normalizedSymbol, normalizedInterval, bar);
            }
            // 收盘 → Redis Stream（唯一实时通道）：agent 侧 KlineStreamConsumer 消费后 republish 本地事件，
            // 触发策略信号 / 交易员唤醒 / 叙事对账
            publishToStream(normalizedSymbol, normalizedInterval, bar);
        });
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
     * 把收盘 bar 写入 Redis Stream，写的时候顺带近似裁剪——agent 拿实时收盘只有这一条路。
     * 失败只记 error：这一根的策略评估、交易员唤醒、叙事对账就没了；下一根照常触发，策略视图那时从库里补上漏掉的这根。
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
            redisTemplate.opsForStream().add(StreamRecords.newRecord().in(MarketStreamChannels.KLINE_CLOSED_STREAM).ofMap(fields),
                    XAddOptions.trim(TrimOptions.maxLen(STREAM_MAXLEN).approximate()));
        } catch (Exception e) {
            log.error("[KlineStream] 写 Stream 失败 symbol={} closeTime={} msg={}", symbol, bar.closeTime(), e.toString());
        }
    }

}
