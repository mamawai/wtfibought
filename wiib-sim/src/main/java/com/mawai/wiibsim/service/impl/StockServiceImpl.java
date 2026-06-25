package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CompanyMapper;
import com.mawai.wiibsim.mapper.StockMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.StockCacheService;
import com.mawai.wiibsim.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 股票服务实现
 * Stock实体只存静态数据，实时数据从Redis获取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockServiceImpl extends ServiceImpl<StockMapper, Stock> implements StockService {

    private final CompanyMapper companyMapper;
    private final CacheService cacheService;
    private final StockCacheService stockCacheService;

    private static final String STOCK_LIST_CACHE_KEY = "stock:list:all";
    private static final String STOCK_DETAIL_CACHE_KEY = "stock:detail:";
    private static final int CACHE_EXPIRE_SECONDS = 60;

    /**
     * 根据股票ID查找（优先从Redis获取）
     */
    @Override
    public Stock findById(Long id) {
        // 优先从Redis获取
        Map<String, String> stockStatic = stockCacheService.getStockStatic(id);
        if (stockStatic != null) {
            return mapToStock(stockStatic);
        }

        // Redis未命中，查询数据库
        return baseMapper.selectById(id);
    }

    /**
     * 将Redis Hash映射为Stock实体
     */
    private Stock mapToStock(Map<String, String> map) {
        Stock stock = new Stock();
        stock.setId(Long.parseLong(map.get("id")));
        stock.setCode(map.get("code"));
        stock.setName(map.get("name"));
        String companyIdStr = map.get("companyId");
        if (companyIdStr != null && !companyIdStr.isEmpty()) {
            stock.setCompanyId(Long.parseLong(companyIdStr));
        }
        stock.setPrevClose(new BigDecimal(map.get("prevClose")));
        stock.setOpen(new BigDecimal(map.get("open")));
        stock.setTrendList(map.get("trendList"));
        return stock;
    }

    /**
     * 获取股票详情（含公司信息，带缓存）
     */
    @Override
    public StockDTO getStockDetail(Long id) {
        String cacheKey = STOCK_DETAIL_CACHE_KEY + id;
        StockDTO cached = cacheService.getObject(cacheKey);
        if (cached != null) {
            return cached;
        }

        Stock stock = findById(id);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }

        Company company = companyMapper.selectById(stock.getCompanyId());
        StockDTO dto = buildStockDTO(stock, company);

        cacheService.setObject(cacheKey, dto, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return dto;
    }

    /**
     * 获取所有股票列表（带缓存）
     */
    @Override
    public List<StockDTO> listAllStocks() {
        List<StockDTO> cached = cacheService.getList(STOCK_LIST_CACHE_KEY);
        if (cached != null) {
            return cached;
        }

        List<Stock> stocks = baseMapper.selectList(null);
        Map<Long, Company> companyMap = batchLoadCompanies(stocks);
        List<StockDTO> dtos = stocks.stream()
                .map(stock -> buildStockDTO(stock, companyMap.get(stock.getCompanyId())))
                .collect(Collectors.toList());

        cacheService.setObject(STOCK_LIST_CACHE_KEY, dtos, CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return dtos;
    }

    /**
     * 分页查询股票列表
     */
    @Override
    public IPage<StockDTO> listStocksByPage(int pageNum, int pageSize) {
        Page<Stock> page = new Page<>(pageNum, pageSize);
        baseMapper.selectPage(page, null);

        Map<Long, Company> companyMap = batchLoadCompanies(page.getRecords());
        return page.convert((stock) -> buildStockDTO(stock, companyMap.get(stock.getCompanyId())));
    }

    /**
     * 获取涨幅榜（按涨跌幅降序）
     */
    @Override
    public List<StockDTO> getTopGainers(int limit) {
        List<Stock> stocks = baseMapper.selectList(null);
        Map<Long, Company> companyMap = batchLoadCompanies(stocks);
        return stocks.stream()
                .map(stock -> buildStockDTO(stock, companyMap.get(stock.getCompanyId())))
                .filter(dto -> dto.getChangePct() != null)
                .sorted(Comparator.comparing(StockDTO::getChangePct).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取跌幅榜（按涨跌幅升序）
     */
    @Override
    public List<StockDTO> getTopLosers(int limit) {
        List<Stock> stocks = baseMapper.selectList(null);
        Map<Long, Company> companyMap = batchLoadCompanies(stocks);
        return stocks.stream()
                .map(stock -> buildStockDTO(stock, companyMap.get(stock.getCompanyId())))
                .filter(dto -> dto.getChangePct() != null)
                .sorted(Comparator.comparing(StockDTO::getChangePct))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 批量查询Company并构建Map
     */
    private Map<Long, Company> batchLoadCompanies(List<Stock> stocks) {
        List<Long> companyIds = stocks.stream()
                .map(Stock::getCompanyId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (companyIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return companyMapper.selectByIds(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, c -> c));
    }

    /**
     * 构建StockDTO（静态数据+Redis实时数据）
     */
    private StockDTO buildStockDTO(Stock stock, Company company) {
        StockDTO dto = new StockDTO();
        dto.setId(stock.getId());
        dto.setCode(stock.getCode());
        dto.setName(stock.getName());
        dto.setPrevClose(stock.getPrevClose());

        // 从Redis获取实时数据
        Map<String, BigDecimal> quote = cacheService.getDailyQuote(stock.getId());
        if (quote != null) {
            dto.setPrice(quote.get("last"));
            dto.setOpenPrice(quote.get("open"));
            dto.setHighPrice(quote.get("high"));
            dto.setLowPrice(quote.get("low"));

            // 计算涨跌
            BigDecimal price = quote.get("last");
            if (price != null && stock.getPrevClose() != null && stock.getPrevClose().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal change = price.subtract(stock.getPrevClose());
                BigDecimal changePct = change.divide(stock.getPrevClose(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                dto.setChange(change);
                dto.setChangePct(changePct);
            }
        } else {
            BigDecimal prevLast = cacheService.getPrevTradingDayLast(stock.getId());
            if (prevLast != null) {
                dto.setPrevClose(prevLast);
            }
        }

        // 公司信息
        if (company != null) {
            dto.setIndustry(company.getIndustry());
            dto.setMarketCap(company.getMarketCap());
            dto.setPeRatio(company.getPeRatio());
            dto.setCompanyDesc(company.getDescription());
        }

        // 解析trendList（历史数据）
        List<Integer> trends = new ArrayList<>();
        if (stock.getTrendList() != null && !stock.getTrendList().isEmpty()) {
            try {
                trends = Arrays.stream(stock.getTrendList().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Integer::parseInt)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("解析trendList失败: {}", stock.getTrendList());
            }
        }

        // 追加今天的涨跌（基于当前价格 vs 昨收价）
        if (dto.getChange() != null) {
            int todayTrend = Integer.compare(dto.getChange().compareTo(BigDecimal.ZERO), 0);
            trends.add(todayTrend);
            // 保持最近10个
            if (trends.size() > 10) {
                trends = new ArrayList<>(trends.subList(trends.size() - 10, trends.size()));
            }
        }

        dto.setTrendList(trends);

        return dto;
    }
}
