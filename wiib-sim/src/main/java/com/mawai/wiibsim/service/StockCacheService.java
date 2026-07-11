package com.mawai.wiibsim.service;
import com.mawai.wiibcommon.cache.CacheService;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibsim.mapper.CompanyMapper;
import com.mawai.wiibsim.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Stock静态数据Redis缓存服务
 * <p>设计要点：</p>
 * <ul>
 *   <li>启动时预热，避免冷启动查询数据库</li>
 *   <li>定时刷新，保证数据一致性</li>
 *   <li>Hash结构存储，节省内存</li>
 *   <li>ID直接查找，O(1)复杂度</li>
 *   <li>Set维护全量ID列表，支持快速遍历</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCacheService {

    private final StockMapper stockMapper;
    private final CompanyMapper companyMapper;
    private final CacheService cacheService;

    private final Cache<Long, Map<String, String>> stockStaticCache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(10, TimeUnit.MINUTES).build();

    /** Stock静态数据前缀 */
    private static final String STOCK_STATIC_PREFIX = "stock:static:";

    /** 所有Stock ID的集合 */
    private static final String STOCK_IDS_ALL = "stock:ids:all";

    /** Company数据前缀 */
    private static final String COMPANY_PREFIX = "company:";

    /**
     * 应用启动完成后预热Stock数据
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        log.info("=== 开始预热Stock静态数据到Redis ===");
        long start = System.currentTimeMillis();

        try {
            loadAllStocksToRedis();
            long elapsed = System.currentTimeMillis() - start;
            log.info("=== Stock数据预热完成，耗时{}ms ===", elapsed);
        } catch (Exception e) {
            log.error("Stock数据预热失败", e);
        }
    }

    /**
     * 加载所有Stock和Company数据到Redis
     */
    public void loadAllStocksToRedis() {
        List<Stock> stocks = stockMapper.selectList(null);
        if (stocks.isEmpty()) {
            log.warn("数据库中没有Stock数据");
            return;
        }

        log.info("开始加载{}支股票到Redis", stocks.size());

        // 清空旧数据
        cacheService.delete(STOCK_IDS_ALL);

        for (Stock stock : stocks) {
            loadSingleStockToRedis(stock);
        }

        log.info("成功加载{}支股票到Redis", stocks.size());
    }

    /**
     * 从Redis获取Stock静态数据
     * @param stockId 股票ID
     * @return Stock数据的Hash，不存在返回null
     */
    public Map<String, String> getStockStatic(Long stockId) {
        return stockStaticCache.get(stockId, id -> {
            String key = STOCK_STATIC_PREFIX + id;
            Map<String, String> stockHash = cacheService.hGetAll(key);

            if (stockHash.isEmpty()) {
                log.warn("Redis中未找到stockId={}的静态数据，尝试从DB加载", id);
                Stock stock = stockMapper.selectById(id);
                if (stock != null) {
                    loadSingleStockToRedis(stock);
                    stockHash = cacheService.hGetAll(key);
                }
            }
            return stockHash.isEmpty() ? null : stockHash;
        });
    }

    /**
     * 获取所有Stock ID列表
     */
    public Set<Long> getAllStockIds() {
        Set<String> idStrings = cacheService.sMembers(STOCK_IDS_ALL);
        if (idStrings == null || idStrings.isEmpty()) {
            log.warn("Redis中没有Stock ID集合，重新加载");
            loadAllStocksToRedis();
            idStrings = cacheService.sMembers(STOCK_IDS_ALL);
        }
        return idStrings.stream()
                .map(Long::parseLong)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 单独加载一支股票到Redis
     */
    private void loadSingleStockToRedis(Stock stock) {
        Map<String, String> stockHash = getStockHash(stock);

        String stockKey = STOCK_STATIC_PREFIX + stock.getId();
        cacheService.hSetAll(stockKey, stockHash);

        cacheService.sAdd(STOCK_IDS_ALL, stock.getId().toString());

        if (stock.getCompanyId() != null) {
            loadCompanyToRedis(stock.getCompanyId());
        }
    }

    private static Map<String, String> getStockHash(Stock stock) {
        Map<String, String> stockHash = new HashMap<>();
        stockHash.put("id", stock.getId().toString());
        stockHash.put("code", stock.getCode());
        stockHash.put("name", stock.getName());
        stockHash.put("companyId", stock.getCompanyId() != null ? stock.getCompanyId().toString() : "");
        stockHash.put("prevClose", stock.getPrevClose() != null ? stock.getPrevClose().toPlainString() : "0");
        stockHash.put("open", stock.getOpen() != null ? stock.getOpen().toPlainString() : "0");
        stockHash.put("trendList", stock.getTrendList() != null ? stock.getTrendList() : "");
        return stockHash;
    }

    /**
     * 加载Company数据到Redis
     */
    private void loadCompanyToRedis(Long companyId) {
        Company company = companyMapper.selectById(companyId);
        if (company == null) {
            return;
        }

        Map<String, String> companyHash = new HashMap<>();
        companyHash.put("id", company.getId().toString());
        companyHash.put("name", company.getName() != null ? company.getName() : "");
        companyHash.put("industry", company.getIndustry() != null ? company.getIndustry() : "");
        companyHash.put("marketCap", company.getMarketCap() != null ? company.getMarketCap().toPlainString() : "0");
        companyHash.put("peRatio", company.getPeRatio() != null ? company.getPeRatio().toPlainString() : "0");
        companyHash.put("description", company.getDescription() != null ? company.getDescription() : "");

        String companyKey = COMPANY_PREFIX + companyId;
        cacheService.hSetAll(companyKey, companyHash);
    }

}
