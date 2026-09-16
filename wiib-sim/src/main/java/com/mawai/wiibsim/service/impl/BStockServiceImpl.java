package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.BStockDTO;
import com.mawai.wiibcommon.entity.BStock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.mapper.BStockMapper;
import com.mawai.wiibsim.service.BStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * bStock 读取服务实现。静态信息读 bstock 表；实时价来自 feed 写入的 Redis（{@code market:price:*}），
 * 24h 涨跌/高低走 Binance 批量 ticker（缓存 15s，避免每次 /list 都打接口）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BStockServiceImpl extends ServiceImpl<BStockMapper, BStock> implements BStockService {

    private final CacheService cacheService;
    private final BinanceRestClient binanceRestClient;

    // 10 只一次批量拉，缓存 15s：/list 高频调用不逐只打 Binance
    private static final String TICKER_CACHE_KEY = "bstock:ticker24h";
    private static final Duration TICKER_TTL = Duration.ofSeconds(15);

    // bStock 符号集缓存（现货引擎判瞬时结算用）：符号极少变，5min TTL 足够
    private static final long SYMBOL_CACHE_TTL_MS = 5 * 60 * 1000L;
    private volatile Set<String> symbolCache;
    private volatile long symbolCacheAt;

    @Override
    public List<BStockDTO> listAll() {
        List<BStock> stocks = lambdaQuery()
                .eq(BStock::getEnabled, true)
                .orderByAsc(BStock::getSort)
                .list();
        if (stocks.isEmpty()) return Collections.emptyList();

        Map<String, JsonNode> tickers = loadTicker24h(stocks.stream().map(BStock::getSymbol).toList());
        return stocks.stream().map(s -> toDTO(s, tickers.get(s.getSymbol()))).toList();
    }

    @Override
    public BStockDTO detail(String symbol) {
        BStock s = lambdaQuery().eq(BStock::getSymbol, symbol).one();
        if (s == null) throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        // 24h 行情和 /list 共用那份 15s 批量缓存，详情页刷多少次都不逐只打币安
        List<String> symbols = lambdaQuery().eq(BStock::getEnabled, true).select(BStock::getSymbol).list()
                .stream().map(BStock::getSymbol).toList();
        return toDTO(s, loadTicker24h(symbols).get(s.getSymbol()));
    }

    @Override
    public BigDecimal price(String symbol) {
        // 不是 bStock 的符号直接报不存在，不拿它去回退币安
        if (!isBStockSymbol(symbol)) throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        BigDecimal p = cacheService.getCryptoPrice(symbol);
        if (p != null) return p;
        // Redis 未命中（如刚启动 WS 未推）→ 回退 REST
        try {
            String json = binanceRestClient.getTickerPrice(symbol);
            if (json != null) return MAPPER.readTree(json).path("price").asDecimal(null);
        } catch (Exception e) {
            log.warn("获取{}最新价失败: {}", symbol, e.getMessage());
        }
        return null;
    }

    /** 批量拉 24h 行情（缓存 15s），返回 symbol→ticker JSON。失败降级空 map，DTO 回退 Redis 现价。 */
    private Map<String, JsonNode> loadTicker24h(List<String> symbols) {
        String json = cacheService.get(TICKER_CACHE_KEY);
        if (json == null) {
            json = binanceRestClient.get24hTickers(symbols);
            if (json != null) cacheService.set(TICKER_CACHE_KEY, json, TICKER_TTL);
        }
        Map<String, JsonNode> map = new HashMap<>();
        if (json != null) {
            for (JsonNode o : MAPPER.readValue(json, ArrayNode.class)) {
                map.put(o.path("symbol").asString(null), o);
            }
        }
        return map;
    }

    /** 静态字段整体拷贝 + 合并实时行情；无 24h 数据时价用 Redis 现价兜底。 */
    private BStockDTO toDTO(BStock s, JsonNode t) {
        BStockDTO d = new BStockDTO();
        BeanUtils.copyProperties(s, d);
        if (t != null) {
            d.setPrice(t.path("lastPrice").asDecimal(null));
            d.setChangePct(t.path("priceChangePercent").asDecimal(null));
            d.setHigh(t.path("highPrice").asDecimal(null));
            d.setLow(t.path("lowPrice").asDecimal(null));
            d.setVolume(t.path("quoteVolume").asDecimal(null));
        } else {
            d.setPrice(cacheService.getCryptoPrice(s.getSymbol()));
        }
        return d;
    }

    @Override
    public boolean isBStockSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        Set<String> cache = symbolCache;
        if (cache == null || System.currentTimeMillis() - symbolCacheAt > SYMBOL_CACHE_TTL_MS) {
            cache = lambdaQuery().select(BStock::getSymbol).list()
                    .stream().map(BStock::getSymbol).collect(Collectors.toSet());
            symbolCache = cache;
            symbolCacheAt = System.currentTimeMillis();
        }
        return cache.contains(symbol);
    }
}
