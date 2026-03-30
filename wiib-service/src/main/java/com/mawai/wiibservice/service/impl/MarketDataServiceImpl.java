package com.mawai.wiibservice.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.DayTickDTO;
import com.mawai.wiibcommon.dto.KlineDTO;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibcommon.entity.News;
import com.mawai.wiibcommon.entity.PriceTickDaily;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.util.TickTimeUtil;
import com.mawai.wiibservice.mapper.NewsMapper;
import com.mawai.wiibservice.mapper.PriceTickDailyMapper;
import com.mawai.wiibservice.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataServiceImpl implements MarketDataService {

    private final StockService stockService;
    private final CompanyService companyService;
    private final PriceTickDailyMapper priceTickDailyMapper;
    private final NewsMapper newsMapper;
    private final CacheService cacheService;
    private final AiService aiService;
    private final StockCacheService stockCacheService;
    private final OptionContractService optionContractService;

    @Override
    public void generateNextDayMarketData(LocalDate targetDate) {
        long start = System.currentTimeMillis();
        log.info("开始生成次日行情: {}", targetDate);

        // 当日市场情绪（25-74），所有股票共用
        int marketSentiment = 25 + ThreadLocalRandom.current().nextInt(50);
        log.info("今日市场情绪: {}", marketSentiment);

        List<Stock> stocks = stockService.list();

        for (Stock stock : stocks) {
            List<BigDecimal> prices = generateStockPrices(stock, targetDate, marketSentiment);
            PriceTickDaily daily = new PriceTickDaily();
            daily.setStockId(stock.getId());
            daily.setTradeDate(targetDate);
            daily.setPrices(prices);
            priceTickDailyMapper.insert(daily);
            log.info("生成股票{}走势完成，共{}个点", stock.getCode(), prices.size());

            // 预缓存K线OHLC
            BigDecimal kOpen = prices.getFirst();
            BigDecimal kClose = prices.getLast();
            BigDecimal kHigh = prices.stream().max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal kLow = prices.stream().min(Comparator.naturalOrder()).orElseThrow();
            cacheService.hSet("kline:" + stock.getId(), targetDate.toString(),
                    kOpen + "," + kHigh + "," + kLow + "," + kClose);
        }

        // 刷新缓存
        stockCacheService.loadAllStocksToRedis();

        long elapsed = System.currentTimeMillis() - start;
        log.info("次日行情生成完成，共{}只股票，耗时{}ms", stocks.size(), elapsed);
    }

    @Override
    public void loadDayDataToRedis(LocalDate date) {
        log.info("加载{}行情到Redis", date);

        List<Stock> stocks = stockService.list();

        for (Stock stock : stocks) {
            PriceTickDaily daily = priceTickDailyMapper.selectOne(
                new LambdaQueryWrapper<PriceTickDaily>()
                    .eq(PriceTickDaily::getStockId, stock.getId())
                    .eq(PriceTickDaily::getTradeDate, date)
            );

            if (daily == null || daily.getPrices() == null || daily.getPrices().isEmpty()) continue;

            // 分时数据写入Hash，field=index, value=price, 单点查O(1)
            String tickKey = String.format("tick:%s:%d", date, stock.getId());
            cacheService.delete(tickKey);
            Map<String, String> tickMap = new HashMap<>();
            List<BigDecimal> prices = daily.getPrices();
            for (int i = 0; i < prices.size(); i++) {
                tickMap.put(String.valueOf(i), prices.get(i).toPlainString());
            }
            cacheService.hSetAll(tickKey, tickMap);

            // 当日汇总：只预热open和prevClose，high/low由实时行情动态更新
            BigDecimal open = daily.getPrices().getFirst();

            String dailyKey = String.format("stock:daily:%s:%d", date, stock.getId());
            cacheService.hSet(dailyKey, "open", open.toString());
            cacheService.hSet(dailyKey, "high", open.toString()); // 初始化为开盘价
            cacheService.hSet(dailyKey, "low", open.toString());  // 初始化为开盘价
            cacheService.hSet(dailyKey, "last", open.toString()); // 初始化为开盘价
            cacheService.hSet(dailyKey, "prevClose", stock.getPrevClose().toString());

            // 设置7天过期
            cacheService.expire(tickKey, 7, TimeUnit.DAYS);
            cacheService.expire(dailyKey, 7, TimeUnit.DAYS);

            log.info("加载股票{}行情到Redis，共{}个点", stock.getCode(), daily.getPrices().size());
        }
    }

    @Override
    public List<DayTickDTO> getDayTicks(Long stockId) {
        LocalDate date = LocalDate.now();
        String key = String.format("tick:%s:%d", date, stockId);

        int endIndex = TickTimeUtil.effectiveEndIndex(LocalTime.now());
        if (endIndex < 0) return Collections.emptyList();

        Map<String, String> tickMap = cacheService.hGetAll(key);
        if (tickMap.isEmpty()) return Collections.emptyList();

        List<DayTickDTO> result = new ArrayList<>();
        for (int i = 0; i <= endIndex; i++) {
            String price = tickMap.get(String.valueOf(i));
            if (price != null) {
                result.add(new DayTickDTO(TickTimeUtil.indexToTime(i).toString(), new BigDecimal(price)));
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> getRealtimeQuote(Long stockId, LocalDate date, LocalTime time) {
        // 获取当前tick
        String tickKey = String.format("tick:%s:%d", date, stockId);
        int index = TickTimeUtil.timeToIndex(time);
        if (index < 0) return null;
        String tickValue = cacheService.hGet(tickKey, String.valueOf(index));
        if (tickValue == null) return null;

        BigDecimal price = new BigDecimal(tickValue);

        // 获取当日汇总
        String dailyKey = String.format("stock:daily:%s:%d", date, stockId);
        Map<String, String> daily = cacheService.hGetAll(dailyKey);

        BigDecimal open = new BigDecimal(daily.getOrDefault("open", price.toString()));
        BigDecimal high = new BigDecimal(daily.getOrDefault("high", price.toString()));
        BigDecimal low = new BigDecimal(daily.getOrDefault("low", price.toString()));
        BigDecimal prevClose = new BigDecimal(daily.getOrDefault("prevClose", price.toString()));

        // 更新最高最低价
        if (price.compareTo(high) > 0) {
            cacheService.hSet(dailyKey, "high", price.toString());
            high = price;
        }
        if (price.compareTo(low) < 0) {
            cacheService.hSet(dailyKey, "low", price.toString());
            low = price;
        }
        cacheService.hSet(dailyKey, "last", price.toString());

        // 写入L1 Caffeine
        daily.put("open", open.toPlainString());
        daily.put("high", high.toPlainString());
        daily.put("low", low.toPlainString());
        daily.put("last", price.toPlainString());
        daily.put("prevClose", prevClose.toPlainString());
        cacheService.putStockDaily(stockId, daily);

        Map<String, Object> result = new HashMap<>();
        result.put("stockId", stockId);
        result.put("price", price);
        result.put("time", time.toString());
        result.put("open", open);
        result.put("high", high);
        result.put("low", low);
        result.put("prevClose", prevClose);
        return result;
    }

    @Override
    public Map<String, Object> refreshDailyCacheFromTicks(LocalDate date, LocalTime time) {
        LocalDate targetDate = date != null ? date : LocalDate.now();
        LocalTime asOfTime = time != null ? time : LocalTime.now();

        Set<Long> allStockIds = stockCacheService.getAllStockIds();
        if (allStockIds.isEmpty()) {
            return Map.of(
                "date", targetDate.toString(),
                "time", asOfTime.toString(),
                "updated", 0,
                "skipped", 0
            );
        }

        int updated = 0;
        int skipped = 0;

        for (Long stockId : allStockIds) {
            String tickKey = String.format("tick:%s:%d", targetDate, stockId);
            Map<String, String> tickMap = cacheService.hGetAll(tickKey);
            if (tickMap.isEmpty()) {
                skipped++;
                continue;
            }

            int maxIndex = TickTimeUtil.effectiveEndIndex(asOfTime);
            if (maxIndex < 0) { skipped++; continue; }

            BigDecimal open = new BigDecimal(tickMap.get("0"));
            BigDecimal last = null;
            BigDecimal high = null;
            BigDecimal low = null;

            for (int i = 0; i <= maxIndex; i++) {
                String priceStr = tickMap.get(String.valueOf(i));
                if (priceStr == null) continue;
                BigDecimal price = new BigDecimal(priceStr);
                if (high == null || price.compareTo(high) > 0) high = price;
                if (low == null || price.compareTo(low) < 0) low = price;
                last = price;
            }

            if (last == null || high == null || low == null) {
                skipped++;
                continue;
            }

            String dailyKey = String.format("stock:daily:%s:%d", targetDate, stockId);

            Map<String, String> update = new HashMap<>();
            update.put("open", open.toPlainString());
            update.put("high", high.toPlainString());
            update.put("low", low.toPlainString());
            update.put("last", last.toPlainString());

            String prevClose = cacheService.hGet(dailyKey, "prevClose");
            update.put("prevClose", Objects.requireNonNullElseGet(prevClose, open::toPlainString));

            cacheService.hSetAll(dailyKey, update);
            cacheService.expire(tickKey, 7, TimeUnit.DAYS);
            cacheService.expire(dailyKey, 7, TimeUnit.DAYS);

            updated++;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("date", targetDate.toString());
        result.put("time", asOfTime.toString());
        result.put("updated", updated);
        result.put("skipped", skipped);
        return result;
    }

    private List<BigDecimal> generateStockPrices(Stock stock, LocalDate date, int marketSentiment) {
        // 查询昨日收盘价（优先Redis，不命中则查库）
        LocalDate prevDate = date.minusDays(1);
        String prevDailyKey = String.format("stock:daily:%s:%d", prevDate, stock.getId());
        String prevCloseStr = cacheService.hGet(prevDailyKey, "last");
        BigDecimal oldPrevClose = stock.getPrevClose(); // 保存更新前的昨收（即前天的收盘价，或者上一次更新的状态）

        if (prevCloseStr != null) {
            stock.setPrevClose(new BigDecimal(prevCloseStr));
        } else {
            PriceTickDaily prevDaily = priceTickDailyMapper.selectOne(
                new LambdaQueryWrapper<PriceTickDaily>()
                    .eq(PriceTickDaily::getStockId, stock.getId())
                    .eq(PriceTickDaily::getTradeDate, prevDate)
            );
            if (prevDaily != null && prevDaily.getPrices() != null && !prevDaily.getPrices().isEmpty()) {
                stock.setPrevClose(prevDaily.getPrices().getLast());
            }
        }
        
        // 更新 trendList (基于 oldPrevClose 和 stock.getPrevClose() 的比较)
        // stock.getPrevClose() 现在是昨天收盘价
        // oldPrevClose 是前天收盘价
        if (oldPrevClose != null && stock.getPrevClose() != null) {
            List<String> trends = getTrends(stock, oldPrevClose);
            stock.setTrendList(String.join(",", trends));
        }

        // AI生成开盘价+GBM参数
        Company company = companyService.getById(stock.getCompanyId());
        GbmParams params = generateGbmParams(stock, company, marketSentiment);

        // 把开盘价存到Stock表
        BigDecimal openPrice = BigDecimal.valueOf(params.openPrice).setScale(2, RoundingMode.HALF_UP);
        stock.setOpen(openPrice);
        stockService.updateById(stock);
        log.info("更新股票{} - 开盘价: {}", stock.getCode(), openPrice);

        // 生成期权链
        BigDecimal annualSigma = BigDecimal.valueOf(params.sigma).multiply(BigDecimal.valueOf(Math.sqrt(252)));
        LocalDateTime expireAt = date.atTime(15, 0);
        optionContractService.generateOptionChain(stock.getId(), stock.getPrevClose(), annualSigma, expireAt, 5);
        log.info("生成股票{}期权链完成", stock.getCode());

        // 生成股票新闻
        generateStockNews(stock, company, date, marketSentiment);

        // 固定参数
        int steps = 1440;
        double dt = 1.0 / steps;

        // GBM生成价格序列
        double[] prices = new double[steps];
        prices[0] = params.openPrice;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 预分配跳跃位置 (Pre-allocate Jumps)
        int[] jumpMap = new int[steps];
        // 用于防止同一个时间点既涨又跌，或者重复添加
        Set<Integer> usedIndices = new HashSet<>();
        // 分配向上跳跃的位置
        for (int k = 0; k < params.positiveLambda; k++) {
            int idx;
            do {
                idx = 10 + random.nextInt(1421); // 随机选一个时间点 (10到1430)
            } while (usedIndices.contains(idx)); // 如果这个点已经被占用了，就重选

            usedIndices.add(idx);
            jumpMap[idx] = 1; // 标记为向上跳
        }

        // 分配向下跳跃的位置
        for (int k = 0; k < params.negativeLambda; k++) {
            int idx;
            do {
                idx = 10 + random.nextInt(1421); // 随机选一个时间点 (10到1430)
            } while (usedIndices.contains(idx));

            usedIndices.add(idx);
            jumpMap[idx] = -1; // 标记为向下跳
        }

        // 设定跳跃时的随机波动 (让每次暴涨/暴跌的幅度稍微不一样)
        double jumpSigma = 0.01;

        for (int i = 1; i < steps; i++) {
            // --- A. 基础 GBM 波动 ---
            double z = random.nextGaussian();
            z = Math.clamp(z, -4.0, 4.0); // 防极值

            double gbmPrice = prices[i - 1] * Math.exp(
                    (params.mu - 0.5 * params.sigma * params.sigma) * dt
                            + params.sigma * Math.sqrt(dt) * z
            );

            // --- B. 检查当前时间点是否有预设的跳跃 ---
            double jumpMultiplier = 1.0;

            if (jumpMap[i] == 1) {
                // 触发向上跳跃
                double rawJump = params.pJumpMu + jumpSigma * random.nextGaussian();
                // 钳制上限 5%
                double clampedJump = Math.clamp(rawJump, 0.01, 0.05);
                jumpMultiplier = Math.exp(clampedJump);
            } else if (jumpMap[i] == -1) {
                // 触发向下跳跃
                double rawJump = params.nJumpMu + jumpSigma * random.nextGaussian();
                // 钳制下限 -5%
                double clampedJump = Math.clamp(rawJump, -0.05, -0.01);
                jumpMultiplier = Math.exp(clampedJump);
            }
            // --- C. 合成最终价格 ---
            prices[i] = gbmPrice * jumpMultiplier;
            // 兜底防止价格过低
            if (prices[i] < 0.01) prices[i] = 0.01;
        }

        List<BigDecimal> result = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            result.add(BigDecimal.valueOf(prices[i]).setScale(2, RoundingMode.HALF_UP));
        }

        return result;
    }

    private static List<String> getTrends(Stock stock, BigDecimal oldPrevClose) {
        int trend = stock.getPrevClose().compareTo(oldPrevClose);
        trend = Integer.compare(trend, 0); // 1, 0, -1

        List<String> trends = new ArrayList<>();
        if (stock.getTrendList() != null && !stock.getTrendList().isEmpty()) {
            for (String s : stock.getTrendList().split(",")) {
                if (!s.trim().isEmpty()) {
                    trends.add(s.trim());
                }
            }
        }
        trends.add(String.valueOf(trend));
        // 保持最近10个
        if (trends.size() > 10) {
            trends = trends.subList(trends.size() - 10, trends.size());
        }
        return trends;
    }

    private GbmParams generateGbmParams(Stock stock, Company company, int marketSentiment) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int stockSentiment = 5 + rnd.nextInt(90); // 股票情绪5-94
        boolean stable = isStableIndustry(company.getIndustry());
        String sigmaRange = stable ? "0.01-0.025" : "0.025-0.06";

        String prompt = String.format("""
            你是每日股票行情模拟器。根据以下信息生成今日GBM模型参数。

            【股票】%s - %s，昨收: %.2f，近十日趋势: %s
            【公司】%s，行业: %s，市值: %.0f，市盈率: %.2f
            【市场情绪】%d（25-74：25-39低迷，40-59一般，60-74高涨）
            【股票情绪】%d（5-94：5-34低迷，35-64一般，65-94高涨）

            【参数要求】
            - openPrice: 开盘价，昨收价±2%%内
            - mu: 日收益率，-0.05到0.05
            - sigma: 日波动率，范围%s

            只返回JSON：{"openPrice": xx.xx, "mu": 0.xx, "sigma": 0.xx}
            """,
            stock.getCode(),
            stock.getName(),
            stock.getPrevClose().doubleValue(),
            stock.getTrendList() != null ? stock.getTrendList() : "无",
            company.getName(),
            company.getIndustry(),
            company.getMarketCap().doubleValue(),
            company.getPeRatio().doubleValue(),
            marketSentiment,
            stockSentiment,
            sigmaRange
        );

        double openPrice;
        double mu;
        double sigma;

        try {
            String response = aiService.chat(prompt);
            String json = response.replaceAll("(?s).*?(\\{.*?}).*", "$1");
            JSONObject obj = JSONUtil.parseObj(json);

            openPrice = obj.getDouble("openPrice");
            mu = obj.getDouble("mu");
            sigma = obj.getDouble("sigma");
        } catch (Exception e) {
            log.warn("AI生成参数失败，使用默认值: {}", e.getMessage());
            openPrice = stock.getPrevClose().doubleValue();
            mu = 0.0;
            sigma = 0.02;
        }

        // 跳跃参数由代码随机生成，根据行业决定范围
        int maxJumps = stable ? 2 : 5;
        int totalJumps = rnd.nextInt(maxJumps + 1); // 稳定行业0-2，波动行业0-5
        int positiveLambda = rnd.nextInt(totalJumps + 1);
        int negativeLambda = totalJumps - positiveLambda;
        double pJumpMu = 0.02 + rnd.nextDouble() * 0.02; // 0.02-0.04
        double nJumpMu = -(0.02 + rnd.nextDouble() * 0.02); // -0.04到-0.02

        log.info("生成参数 - {}: openPrice={}, mu={}, sigma={}, positiveLambda={}, negativeLambda={}, pJumpMu={}, nJumpMu={}",
            stock.getCode(), openPrice, mu, sigma, positiveLambda, negativeLambda, pJumpMu, nJumpMu);
        return new GbmParams(openPrice, mu, sigma, positiveLambda, negativeLambda, pJumpMu, nJumpMu);
    }

    private record GbmParams(double openPrice, double mu, double sigma,
                             int positiveLambda, int negativeLambda,
                             double pJumpMu, double nJumpMu) {}

    private static final Set<String> STABLE_INDUSTRIES = Set.of(
        "金融", "银行", "保险", "公用事业", "电力", "水务", "燃气",
        "基础设施", "食品饮料", "医疗保健", "日用消费", "农业"
    );

    private boolean isStableIndustry(String industry) {
        if (industry == null) return false;
        String lower = industry.toLowerCase();
        return STABLE_INDUSTRIES.stream().anyMatch(lower::contains);
    }

    @Override
    public List<DayTickDTO> getHistoryDayTicks(Long stockId, LocalDate date) {
        String cacheKey = String.format("history-ticks:%d:%s", stockId, date);
        String cached = cacheService.get(cacheKey);
        if (cached != null) return JSONUtil.toList(cached, DayTickDTO.class);

        PriceTickDaily daily = priceTickDailyMapper.selectOne(
            new LambdaQueryWrapper<PriceTickDaily>()
                .eq(PriceTickDaily::getStockId, stockId)
                .eq(PriceTickDaily::getTradeDate, date)
        );
        if (daily == null || daily.getPrices() == null) {
            return Collections.emptyList();
        }

        List<BigDecimal> prices = daily.getPrices();
        List<DayTickDTO> result = new ArrayList<>(prices.size());
        for (int i = 0; i < prices.size(); i++) {
            result.add(new DayTickDTO(TickTimeUtil.indexToTime(i).toString(), prices.get(i)));
        }
        cacheService.set(cacheKey, JSONUtil.toJsonStr(result), Duration.ofHours(1));
        return result;
    }

    @Override
    public List<KlineDTO> getKlineData(Long stockId, int days) {
        String cacheKey = "kline:" + stockId;
        // 15:00后收盘，当天K线可见；否则排除当天
        LocalDate cutoff = LocalTime.now().isAfter(LocalTime.of(15, 0))
                ? LocalDate.now().plusDays(1) : LocalDate.now();
        String cutoffStr = cutoff.toString();
        Map<String, String> cached = cacheService.hGetAll(cacheKey);

        if (!cached.isEmpty()) {
            List<KlineDTO> result = cached.entrySet().stream()
                    .filter(e -> e.getKey().compareTo(cutoffStr) < 0)
                    .map(e -> parseKline(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(KlineDTO::getDate).reversed())
                    .limit(days)
                    .toList();
            if (!result.isEmpty()) return result;
        }

        // 冷启动：查PG并回填缓存
        List<KlineDTO> fromDb = priceTickDailyMapper.selectKline(stockId, cutoff, days);
        Map<String, String> toCache = new HashMap<>();
        for (KlineDTO k : fromDb) {
            toCache.put(k.getDate().toString(),
                    k.getOpen() + "," + k.getHigh() + "," + k.getLow() + "," + k.getClose());
        }
        if (!toCache.isEmpty()) cacheService.hSetAll(cacheKey, toCache);
        return fromDb;
    }

    private KlineDTO parseKline(String dateStr, String csv) {
        String[] p = csv.split(",");
        return new KlineDTO(LocalDate.parse(dateStr),
                new BigDecimal(p[0]), new BigDecimal(p[1]),
                new BigDecimal(p[2]), new BigDecimal(p[3]));
    }

    private void generateStockNews(Stock stock, Company company, LocalDate date, int marketSentiment) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int stockSentiment = 5 + rnd.nextInt(91);

        String prompt = String.format("""
            重要：这是一个完全虚拟的平行世界
            为以下股票虚构出3条今日新闻，要求贴合实际、有细节。

            【股票】%s - %s
            【公司】%s，行业: %s，简介: %s
            【市场情绪】%d（25-74：25-39低迷，40-59一般，60-74高涨）
            【股票情绪】%d（5-94：5-34低迷，35-64一般，65-94高涨）

            要求：
            1. 第1条：股票相关新闻说辞可以含糊不清（稍微透露今天股票可能的涨幅情况）
            2. 第2、3条：公司业务新闻（产品发布、合作、市场拓展等）

            每条新闻包含title（15-25字）和content（150-250字）。
            返回JSON数组：[{"type":"stock","title":"...","content":"..."},{"type":"company","title":"...","content":"..."},{"type":"company","title":"...","content":"..."}]
            """,
            stock.getCode(),
            stock.getName(),
            company.getName(),
            company.getIndustry(),
            company.getDescription(),
            marketSentiment,
            stockSentiment
        );

        try {
            String response = aiService.chat(prompt);
            String json = response.replaceAll("(?s).*?(\\[.*?]).*", "$1");
            JSONArray arr = JSONUtil.parseArray(json);

            LocalDateTime baseTime = date.atTime(8, 30);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                News news = new News();
                news.setStockCode(stock.getCode());
                news.setTitle(obj.getStr("title"));
                news.setContent(obj.getStr("content"));
                news.setNewsType(obj.getStr("type"));
                news.setPublishTime(baseTime.plusMinutes(rnd.nextInt(60)));
                newsMapper.insert(news);
            }
            log.info("生成股票{}新闻完成，共{}条", stock.getCode(), arr.size());
        } catch (Exception e) {
            log.warn("生成新闻失败: {} - {}", stock.getCode(), e.getMessage());
        }
    }
}
