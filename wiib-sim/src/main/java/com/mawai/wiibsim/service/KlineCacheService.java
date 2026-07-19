package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * K线代理的 Redis 缓存层：多人同刷/切周期不再放大到 Binance（权重限频、418 封 IP 是全站行情单点风险）。
 * <p>一致性依据：已闭合 bar 不可变，历史翻页（带 endTime）可长缓存；最新页只有最后一根会变，
 * 而图表最后一根由 WS 流实时驱动、REST 仅作进页快照，短 TTL 的滞后会被 WS 首帧立即覆盖。
 * <p>Redis 故障直接穿透打 Binance，不影响可用性；非 JSON 数组的错误响应不进缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineCacheService {

    /** 最新页（不带 endTime）：只保"同一时刻大家看同一份"，滞后由 WS 兜底 */
    private static final Duration LATEST_TTL = Duration.ofSeconds(10);
    /** 历史翻页（带 endTime）：闭合 bar 不可变，1h 纯为控内存 */
    private static final Duration HISTORY_TTL = Duration.ofHours(1);

    private final BinanceRestClient binanceRestClient;
    private final StringRedisTemplate redisTemplate;

    /** 现货K线（crypto 现货 / bStock 共用） */
    public String spotKlines(String symbol, String interval, int limit, Long endTime) {
        return cached("spot", symbol, interval, limit, endTime,
                () -> binanceRestClient.getKlinesLight(symbol, interval, limit, endTime));
    }

    /** 合约K线 */
    public String futuresKlines(String symbol, String interval, int limit, Long endTime) {
        return cached("fut", symbol, interval, limit, endTime,
                () -> binanceRestClient.getFuturesKlinesLight(symbol, interval, limit, endTime));
    }

    private String cached(String market, String symbol, String interval, int limit, Long endTime, Supplier<String> loader) {
        // symbol 大写归一防 key 分裂；请求参数保持原样透传（与无缓存时行为一致）
        String key = "kline:" + market + ":" + symbol.toUpperCase() + ":" + interval + ":" + limit
                + ":" + (endTime == null ? "latest" : endTime);
        try {
            String hit = redisTemplate.opsForValue().get(key);
            if (hit != null) return hit;
        } catch (Exception e) {
            log.warn("[KlineCache] Redis 读失败，穿透直连 key={}: {}", key, e.getMessage());
        }
        String fresh = loader.get();
        if (fresh != null && fresh.startsWith("[")) {
            try {
                redisTemplate.opsForValue().set(key, fresh, endTime == null ? LATEST_TTL : HISTORY_TTL);
            } catch (Exception e) {
                log.warn("[KlineCache] Redis 写失败 key={}: {}", key, e.getMessage());
            }
        }
        return fresh;
    }
}
