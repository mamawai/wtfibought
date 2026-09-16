package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.TradingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 资金费率的唯一出口。只在结算点（0:00/8:00/16:00）调一次官方全量 premiumIndex 写缓存，
 * 扣费和前端查询都只读缓存——查询链路绝不打 Binance。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingRateService {

    /** 结算间隔 8 小时，对齐 ScheduledTasks 的 cron（服务器本地时区 0/8/16 点） */
    private static final int SETTLE_HOURS = 8;

    private final BinanceRestClient restClient;
    private final BinanceProperties binanceProperties;
    private final CacheService cacheService;
    private final TradingConfig tradingConfig;

    /** 前端查询的返回体；rate 是小数（0.0001 = 0.01%/8h） */
    public record FundingRateView(String symbol, BigDecimal rate, long fetchedAt, long nextTime) {}

    /** 结算点拉一次官方全量，只留我们的合约标的写缓存。拉不到就不动缓存，上一轮的值还能用满 TTL */
    public void refresh() {
        Map<String, BigDecimal> rates = parseFundingRates(
                restClient.getPremiumIndexAll(), binanceProperties.getAllFuturesSymbols());
        if (rates.isEmpty()) {
            log.warn("资金费率全量拉取失败或为空，本轮沿用缓存");
            return;
        }
        long now = System.currentTimeMillis();
        rates.forEach((symbol, rate) -> cacheService.putFundingRate(symbol, rate, now));
        log.info("资金费率缓存刷新 {}个 {}", rates.size(), rates);
    }

    /** 扣费取数：读缓存，缓存空了回退配置固定费率 */
    public BigDecimal rateForSettlement(String symbol) {
        CacheService.FundingRate cached = cacheService.getFundingRate(symbol);
        if (cached != null && cached.rate() != null) return cached.rate();
        BigDecimal fallback = tradingConfig.getFutures().getFundingRate();
        log.warn("资金费率缓存缺失 symbol={} 回退固定费率 {}", symbol, fallback);
        return fallback;
    }

    /** 前端查询：只读缓存。无合约的标的（bStock 等）和缓存没命中都返回 null */
    public FundingRateView query(String symbol) {
        if (symbol == null || !binanceProperties.getAllFuturesSymbols().contains(symbol)) return null;
        CacheService.FundingRate cached = cacheService.getFundingRate(symbol);
        if (cached == null || cached.rate() == null) return null;
        return new FundingRateView(symbol, cached.rate(), cached.fetchedAt(),
                nextFundingTime(System.currentTimeMillis(), ZoneId.systemDefault()));
    }

    /** 下一个 0:00/8:00/16:00 的毫秒时间戳；正卡在结算点上算下一个 */
    static long nextFundingTime(long nowMs, ZoneId zone) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        ZonedDateTime slot = now.truncatedTo(ChronoUnit.HOURS)
                .withHour(now.getHour() / SETTLE_HOURS * SETTLE_HOURS);
        return slot.plusHours(SETTLE_HOURS).toInstant().toEpochMilli();
    }

    /** 解析 premiumIndex 全量数组（几百个合约），只挑我们订阅的那些的 lastFundingRate */
    static Map<String, BigDecimal> parseFundingRates(String json, Collection<String> symbols) {
        if (json == null || json.isBlank() || symbols == null || symbols.isEmpty()) return Map.of();
        Set<String> wanted = new HashSet<>(symbols);
        Map<String, BigDecimal> result = new HashMap<>();
        try {
            ArrayNode arr = MAPPER.readValue(json, ArrayNode.class);
            for (JsonNode item : arr) {
                String symbol = item.path("symbol").asString(null);
                if (symbol == null || !wanted.contains(symbol)) continue;
                BigDecimal rate = item.path("lastFundingRate").asDecimal(null);
                if (rate != null) result.put(symbol, rate);
            }
        } catch (Exception e) {
            log.warn("解析 premiumIndex 全量响应失败: {}", e.toString());
            return Map.of();
        }
        return result;
    }
}
