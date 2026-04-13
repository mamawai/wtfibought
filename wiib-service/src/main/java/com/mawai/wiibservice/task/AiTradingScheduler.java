package com.mawai.wiibservice.task;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import com.mawai.wiibservice.agent.trading.AiTradingTools;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import com.mawai.wiibservice.service.OrderFlowAggregator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class AiTradingScheduler {

    private static final String AI_LINUX_DO_ID = "AI_TRADER";
    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*([\\s\\S]*?)```");

    private static final java.time.format.DateTimeFormatter DERIBIT_EXPIRY_FMT =
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("dMMMuu").toFormatter(java.util.Locale.ENGLISH);

    private static final String[][] PRICE_CHANGE_SOURCES = {
            {"5m", "1m", "5"}, {"15m", "5m", "3"}, {"30m", "5m", "6"},
            {"1h", "15m", "4"}, {"4h", "1h", "4"}, {"24h", "1h", "24"},
    };

    private final AtomicLong aiUserId = new AtomicLong(0);
    private final AtomicInteger cycleCounter = new AtomicInteger(0);
    private final Set<String> runningSymbols = ConcurrentHashMap.newKeySet();

    private final UserMapper userMapper;
    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final AiTradingDecisionMapper tradingDecisionMapper;
    private final CacheService cacheService;
    private final AiAgentRuntimeManager runtimeManager;
    private final BinanceRestClient binanceRestClient;
    private final ForceOrderService forceOrderService;
    private final OrderFlowAggregator orderFlowAggregator;
    private final DepthStreamCache depthStreamCache;
    private final DeribitClient deribitClient;

    public AiTradingScheduler(UserMapper userMapper,
                              FuturesTradingService futuresTradingService,
                              FuturesRiskService futuresRiskService,
                              FuturesPositionMapper futuresPositionMapper,
                              QuantForecastCycleMapper cycleMapper,
                              QuantSignalDecisionMapper decisionMapper,
                              AiTradingDecisionMapper tradingDecisionMapper,
                              CacheService cacheService,
                              AiAgentRuntimeManager runtimeManager,
                              BinanceRestClient binanceRestClient,
                              ForceOrderService forceOrderService,
                              OrderFlowAggregator orderFlowAggregator,
                              DepthStreamCache depthStreamCache,
                              DeribitClient deribitClient) {
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.futuresRiskService = futuresRiskService;
        this.futuresPositionMapper = futuresPositionMapper;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.tradingDecisionMapper = tradingDecisionMapper;
        this.cacheService = cacheService;
        this.runtimeManager = runtimeManager;
        this.binanceRestClient = binanceRestClient;
        this.forceOrderService = forceOrderService;
        this.orderFlowAggregator = orderFlowAggregator;
        this.depthStreamCache = depthStreamCache;
        this.deribitClient = deribitClient;
    }

    @PostConstruct
    public void init() {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getLinuxDoId, AI_LINUX_DO_ID));
        if (existing != null) {
            aiUserId.set(existing.getId());
            log.info("[AI-Trader] 已有AI账户 id={} balance={}", existing.getId(), existing.getBalance());
        } else {
            User ai = new User();
            ai.setLinuxDoId(AI_LINUX_DO_ID);
            ai.setUsername("AI Trader");
            ai.setBalance(INITIAL_BALANCE);
            ai.setFrozenBalance(BigDecimal.ZERO);
            ai.setIsBankrupt(false);
            ai.setBankruptCount(0);
            ai.setMarginLoanPrincipal(BigDecimal.ZERO);
            ai.setMarginInterestAccrued(BigDecimal.ZERO);
            userMapper.insert(ai);
            aiUserId.set(ai.getId());
            log.info("[AI-Trader] 创建AI账户 id={} balance={}", ai.getId(), INITIAL_BALANCE);
        }
        cycleCounter.set(tradingDecisionMapper.selectMaxCycleNo());
    }

    public Long getAiUserId() {
        return aiUserId.get();
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void tradingCycle() {
        if (aiUserId.get() == 0) return;
        triggerTradingCycle(QuantConstants.WATCH_SYMBOLS);
    }

    public int triggerTradingCycle(List<String> symbols) {
        if (aiUserId.get() == 0) {
            throw new IllegalStateException("AI交易员未初始化");
        }
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("交易对不能为空");
        }
        int cycleNo = cycleCounter.incrementAndGet();
        for (String symbol : symbols) {
            Thread.startVirtualThread(() -> runTradingCycle(symbol, cycleNo));
        }
        return cycleNo;
    }

    private void runTradingCycle(String symbol, int cycleNo) {
        if (!runningSymbols.add(symbol)) {
            log.warn("[AI-Trader] {} 上一轮未完成，跳过", symbol);
            return;
        }
        long userId = aiUserId.get();
        log.info("[AI-Trader] 交易周期开始 symbol={}", symbol);

        try {
            User userBefore = userMapper.selectById(userId);
            BigDecimal balanceBefore = userBefore != null ? userBefore.getBalance() : BigDecimal.ZERO;

            var positions = futuresTradingService.getUserPositions(userId, symbol);
            String positionSnapshot = JSON.toJSONString(positions);

            var forecast = cycleMapper.selectLatest(symbol);
            var signals = decisionMapper.selectLatestBySymbol(symbol);

            List<AiTradingDecision> recentDecisions = tradingDecisionMapper.selectRecentBySymbol(symbol, 5);

            BigDecimal futuresPrice = cacheService.getFuturesPrice(symbol);
            BigDecimal markPrice = cacheService.getMarkPrice(symbol);

            String liveMarketData = fetchAndComputeMarketData(symbol);

            String userPrompt = buildUserPrompt(symbol, userBefore, positions, forecast, signals,
                    recentDecisions, futuresPrice, markPrice, liveMarketData);
            log.info("[AI-Trader] prompt symbol={} len={}\n{}", symbol, userPrompt.length(), userPrompt);

            AiTradingTools tools = new AiTradingTools(userId, symbol, userMapper, futuresTradingService,
                    futuresRiskService, futuresPositionMapper, cycleMapper, decisionMapper, cacheService);
            ReactAgent agent = runtimeManager.createTradingAgent(tools);
            StringBuilder response = new StringBuilder();
            agent.streamMessages(userPrompt, RunnableConfig.builder()
                            .threadId("ai-trader-" + symbol + "-" + cycleNo).build())
                    .doOnNext(msg -> {
                        if (msg instanceof AssistantMessage am) {
                            String text = am.getText();
                            if (text != null) response.append(text);
                        }
                    })
                    .blockLast();

            String fullResponse = response.toString();
            log.info("[AI-Trader] response symbol={} len={}\n{}", symbol, fullResponse.length(), fullResponse);

            String action = "HOLD";
            String reasoning = "";
            Matcher m = JSON_BLOCK.matcher(fullResponse);
            String reasoningStr = fullResponse.length() > 500 ? fullResponse.substring(fullResponse.length() - 500) : fullResponse;
            if (m.find()) {
                try {
                    var jsonStr = m.group(1).trim();
                    if (jsonStr.startsWith("[")) {
                        var arr = JSON.parseArray(jsonStr);
                        for (int i = 0; i < arr.size(); i++) {
                            var obj = arr.getJSONObject(i);
                            if (symbol.equals(obj.getString("symbol"))) {
                                action = obj.getString("action");
                                reasoning = obj.getString("reasoning");
                                break;
                            }
                        }
                        if (reasoning.isEmpty() && !arr.isEmpty()) {
                            action = arr.getJSONObject(0).getString("action");
                            reasoning = arr.getJSONObject(0).getString("reasoning");
                        }
                    } else {
                        var obj = JSON.parseObject(jsonStr);
                        action = obj.getString("action");
                        reasoning = obj.getString("reasoning");
                    }
                } catch (Exception e) {
                    log.warn("[AI-Trader] JSON解析失败 symbol={}", symbol, e);
                    reasoning = reasoningStr;
                }
            } else {
                reasoning = reasoningStr;
            }

            User userAfter = userMapper.selectById(userId);
            BigDecimal balanceAfter = userAfter != null ? userAfter.getBalance() : BigDecimal.ZERO;

            AiTradingDecision decision = new AiTradingDecision();
            decision.setCycleNo(cycleNo);
            decision.setSymbol(symbol);
            decision.setAction(action != null ? action : "HOLD");
            decision.setReasoning(reasoning);
            decision.setMarketContext(String.format("price=%s mark=%s", futuresPrice, markPrice));
            decision.setPositionSnapshot(positionSnapshot);
            decision.setExecutionResult(fullResponse.length() > 4000
                    ? fullResponse.substring(fullResponse.length() - 4000) : fullResponse);
            decision.setBalanceBefore(balanceBefore);
            decision.setBalanceAfter(balanceAfter);
            tradingDecisionMapper.insert(decision);

            log.info("[AI-Trader] 交易周期完成 symbol={} action={} balanceBefore={} balanceAfter={}",
                    symbol, action, balanceBefore, balanceAfter);

        } catch (Exception e) {
            log.error("[AI-Trader] 交易周期异常 symbol={}", symbol, e);
        } finally {
            runningSymbols.remove(symbol);
        }
    }

    // ==================== Prompt构建 ====================

    private String buildUserPrompt(String symbol, User user, List<?> positions,
                                   QuantForecastCycle forecast,
                                   List<?> signals, List<AiTradingDecision> recentDecisions,
                                   BigDecimal futuresPrice, BigDecimal markPrice, String liveMarketData) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前交易对: ").append(symbol).append("\n\n");

        sb.append("### 账户状态\n");
        if (user != null) {
            sb.append("- 可用余额: ").append(user.getBalance()).append(" USDT\n");
            sb.append("- 冻结余额: ").append(user.getFrozenBalance()).append(" USDT\n");
            BigDecimal posMargin = BigDecimal.ZERO;
            BigDecimal posUnpnl = BigDecimal.ZERO;
            if (positions != null) {
                for (Object p : positions) {
                    if (p instanceof com.mawai.wiibcommon.dto.FuturesPositionDTO dto) {
                        if (dto.getMargin() != null) posMargin = posMargin.add(dto.getMargin());
                        if (dto.getUnrealizedPnl() != null) posUnpnl = posUnpnl.add(dto.getUnrealizedPnl());
                    }
                }
            }
            BigDecimal totalAsset = user.getBalance().add(user.getFrozenBalance()).add(posMargin).add(posUnpnl);
            BigDecimal pnl = totalAsset.subtract(INITIAL_BALANCE);
            sb.append("- 总资产: ").append(totalAsset).append(" USDT (累计盈亏: ").append(pnl).append(")\n");
        }

        sb.append("\n### 当前价格\n");
        sb.append("- 合约价: ").append(futuresPrice).append("\n");
        sb.append("- 标记价: ").append(markPrice).append("\n");

        sb.append("\n### 当前持仓\n");
        if (positions == null || positions.isEmpty()) {
            sb.append("⚠️ 空仓！你应该积极寻找开仓机会。\n");
        } else {
            sb.append(JSON.toJSONString(positions)).append("\n");
            sb.append("→ 评估持仓是否需要：平仓止盈/止损、加仓、修改止盈止损价\n");
        }

        sb.append("\n### 量化分析报告\n");
        if (forecast != null) {
            sb.append("- 综合决策: ").append(forecast.getOverallDecision()).append("\n");
            sb.append("- 风控状态: ").append(forecast.getRiskStatus()).append("\n");
            sb.append("- 预测时间: ").append(forecast.getForecastTime()).append("\n");
            appendReportData(sb, forecast.getReportJson());
        } else {
            sb.append("暂无量化分析\n");
        }

        if (signals != null && !signals.isEmpty()) {
            sb.append("\n### 各时间窗口信号\n");
            boolean hasDirection = false;
            for (Object sig : signals) {
                if (sig instanceof QuantSignalDecision s) {
                    sb.append(String.format("- [%s] 方向=%s 置信度=%.0f%% 建议杠杆≤%dx 仓位≤%.0f%%\n",
                            s.getHorizon(), s.getDirection(),
                            s.getConfidence().doubleValue() * 100,
                            s.getMaxLeverage(),
                            s.getMaxPositionPct().doubleValue() * 100));
                    if (!"NO_TRADE".equals(s.getDirection())) hasDirection = true;
                }
            }
            if (hasDirection && (positions == null || positions.isEmpty())) {
                sb.append("⚠️ 有方向性信号但你是空仓状态，应该开仓！\n");
            }
        }

        if (liveMarketData != null && !liveMarketData.isBlank()) {
            sb.append(liveMarketData).append("\n");
        }

        if (!recentDecisions.isEmpty()) {
            long holdCount = recentDecisions.stream().filter(d -> "HOLD".equals(d.getAction())).count();
            sb.append("\n### 最近").append(recentDecisions.size()).append("次决策");
            if (holdCount >= 2) {
                sb.append(" 🚨 已连续").append(holdCount).append("次HOLD！你过于保守，必须立即开仓交易！");
            }
            sb.append("\n");
            for (AiTradingDecision d : recentDecisions) {
                sb.append(String.format("- [%s] %s: %s\n",
                        d.getCreatedAt(), d.getAction(), d.getReasoning()));
            }
        }

        sb.append("\n## 行动要求\n");
        sb.append("1. 先调用 getMarketPrice 获取最新价格\n");
        if (positions != null && !positions.isEmpty()) {
            sb.append("2. 调用 getPositions 查看持仓最新状态\n");
            sb.append("3. 决定：平仓/加仓/修改止盈止损/开反向仓位\n");
        } else {
            sb.append("2. 根据信号方向开仓（必须设止损+止盈）\n");
        }
        sb.append("4. 执行交易后输出JSON决策摘要\n");
        return sb.toString();
    }

    private void appendReportData(StringBuilder sb, String reportJson) {
        if (reportJson == null || reportJson.isBlank()) return;
        try {
            var report = JSON.parseObject(reportJson);

            String summary = report.getString("summary");
            if (summary != null) sb.append("- 市场总结: ").append(summary).append("\n");

            Integer confidence = report.getInteger("confidence");
            if (confidence != null) sb.append("- 系统置信度: ").append(confidence).append("%\n");

            var adviceArr = report.getJSONArray("positionAdvice");
            if (adviceArr != null && !adviceArr.isEmpty()) {
                sb.append("- 交易方案:\n");
                for (int i = 0; i < adviceArr.size(); i++) {
                    var a = adviceArr.getJSONObject(i);
                    String type = a.getString("type");
                    if ("NO_TRADE".equals(type)) {
                        sb.append(String.format("  [%s] 观望\n", a.getString("period")));
                    } else {
                        sb.append(String.format("  [%s] %s 入场=%s 止损=%s 止盈=%s 盈亏比=%s\n",
                                a.getString("period"), type,
                                a.getString("entry"), a.getString("stopLoss"),
                                a.getString("takeProfit"), a.getString("riskReward")));
                    }
                }
            }

            var debate = report.getJSONObject("debateSummary");
            if (debate != null) {
                String judge = debate.getString("judgeReasoning");
                if (judge != null) sb.append("- 多空裁判结论: ").append(judge).append("\n");
            }

            var warnings = report.getJSONArray("riskWarnings");
            if (warnings != null && !warnings.isEmpty()) {
                sb.append("- 风险提示: ");
                for (int i = 0; i < Math.min(warnings.size(), 3); i++) {
                    if (i > 0) sb.append("; ");
                    sb.append(warnings.getString(i));
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("[AI-Trader] reportJson解析跳过 {}", e.getMessage());
        }
    }

    // ==================== 数据采集 + 特征计算 ====================

    private String fetchAndComputeMarketData(String symbol) {
        long startMs = System.currentTimeMillis();

        // 与CollectDataNode对齐的并行采集
        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        int[] limits = {120, 288, 192, 168, 180, 90};
        String[] spotIntervals = {"1m", "5m"};
        int[] spotLimits = {120, 288};

        Map<String, String> klineData = new HashMap<>();
        Map<String, String> spotKlineData = new HashMap<>();
        String spotTickerRaw = null;
        String spotObRaw = null;
        String tickerRaw = null;
        String fundingRaw = null;
        String fundingHistRaw = null;
        String obRaw = null;
        String oiRaw = null;
        String oiHistRaw = null;
        String lsrRaw = null;
        String topTraderRaw = null;
        String takerRaw = null;
        String fgiRaw = null;
        String forceOrdersRaw = null;
        String dvolRaw = null;
        String bookSummaryRaw = null;
        String newsRaw = null;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 合约K线 6周期
            @SuppressWarnings("unchecked")
            Future<String>[] klineFutures = new Future[6];
            for (int i = 0; i < 6; i++) {
                int idx = i;
                klineFutures[i] = executor.submit(
                        () -> binanceRestClient.getFuturesKlines(symbol, intervals[idx], limits[idx], null));
            }
            // 现货K线 2周期
            @SuppressWarnings("unchecked")
            Future<String>[] spotKlineFutures = new Future[2];
            for (int i = 0; i < 2; i++) {
                int idx = i;
                spotKlineFutures[i] = executor.submit(
                        () -> binanceRestClient.getKlines(symbol, spotIntervals[idx], spotLimits[idx], null));
            }

            var tickerF = executor.submit(() -> binanceRestClient.getFutures24hTicker(symbol));
            var spotTickerF = executor.submit(() -> binanceRestClient.get24hTicker(symbol));
            var fundingF = executor.submit(() -> binanceRestClient.getFundingRate(symbol));
            var fundingHistF = executor.submit(() -> binanceRestClient.getFundingRateHistory(symbol, 48));
            var obF = executor.submit(() -> {
                if (depthStreamCache != null) {
                    String ws = depthStreamCache.getFreshDepth(symbol, 2000);
                    if (ws != null) return ws;
                }
                return binanceRestClient.getFuturesOrderbook(symbol, 20);
            });
            var spotObF = executor.submit(() -> binanceRestClient.getOrderbook(symbol, 20));
            var oiF = executor.submit(() -> binanceRestClient.getOpenInterest(symbol));
            var oiHistF = executor.submit(() -> binanceRestClient.getOpenInterestHist(symbol, "5m", 48));
            var lsrF = executor.submit(() -> binanceRestClient.getLongShortRatio(symbol));
            var topTraderF = executor.submit(() -> binanceRestClient.getTopTraderPositionRatio(symbol, "5m", 24));
            var takerF = executor.submit(() -> binanceRestClient.getTakerLongShortRatio(symbol, "5m", 24));
            var fgiF = executor.submit(() -> binanceRestClient.getFearGreedIndex(1));
            var forceOrdersF = executor.submit(() -> buildForceOrdersJson(symbol));

            String coin = symbol.replace("USDT", "").replace("USDC", "");
            var newsF = executor.submit(() -> binanceRestClient.getCryptoNews(coin, 30, "EN"));
            Future<String> dvolF = deribitClient != null
                    ? executor.submit(() -> deribitClient.getDvolIndex(coin, 3600)) : null;
            Future<String> bookSummaryF = deribitClient != null
                    ? executor.submit(() -> deribitClient.getBookSummaryByCurrency(coin)) : null;

            // 收集合约K线
            for (int i = 0; i < 6; i++) {
                try {
                    String data = klineFutures[i].get(10, TimeUnit.SECONDS);
                    if (data != null) klineData.put(intervals[i], data);
                } catch (Exception e) {
                    log.debug("[AI-Trader] K线{}跳过: {}", intervals[i], e.getMessage());
                }
            }
            // 收集现货K线
            for (int i = 0; i < 2; i++) {
                try {
                    String data = spotKlineFutures[i].get(10, TimeUnit.SECONDS);
                    if (data != null) spotKlineData.put(spotIntervals[i], data);
                } catch (Exception e) {
                    log.debug("[AI-Trader] 现货K线{}跳过: {}", spotIntervals[i], e.getMessage());
                }
            }

            tickerRaw = safeGet(tickerF, "ticker");
            spotTickerRaw = safeGet(spotTickerF, "spotTicker");
            fundingRaw = safeGet(fundingF, "funding");
            fundingHistRaw = safeGet(fundingHistF, "fundingHist");
            obRaw = safeGet(obF, "orderbook");
            spotObRaw = safeGet(spotObF, "spotOb");
            oiRaw = safeGet(oiF, "oi");
            oiHistRaw = safeGet(oiHistF, "oiHist");
            lsrRaw = safeGet(lsrF, "lsr");
            topTraderRaw = safeGet(topTraderF, "topTrader");
            takerRaw = safeGet(takerF, "taker");
            fgiRaw = safeGet(fgiF, "fgi");
            forceOrdersRaw = safeGet(forceOrdersF, "forceOrders");
            newsRaw = safeGet(newsF, "news");
            if (dvolF != null) dvolRaw = safeGet(dvolF, "dvol");
            if (bookSummaryF != null) bookSummaryRaw = safeGet(bookSummaryF, "bookSummary");
        } catch (Exception e) {
            log.warn("[AI-Trader] 数据采集异常: {}", e.getMessage());
        }

        long collectMs = System.currentTimeMillis() - startMs;

        // 技术指标计算
        Map<String, Map<String, Object>> indicatorsByTf = new LinkedHashMap<>();
        Map<String, List<BigDecimal[]>> parsedKlines = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> closesByInterval = new LinkedHashMap<>();

        for (var entry : klineData.entrySet()) {
            List<BigDecimal[]> klines = CryptoIndicatorCalculator.parseKlines(entry.getValue());
            if (klines.size() < 30) continue;
            indicatorsByTf.put(entry.getKey(), CryptoIndicatorCalculator.calcAll(klines));
            parsedKlines.put(entry.getKey(), klines);
            List<BigDecimal> closes = new ArrayList<>(klines.size());
            for (BigDecimal[] k : klines) closes.add(k[2]);
            closesByInterval.put(entry.getKey(), closes);
        }

        Map<String, List<BigDecimal[]>> parsedSpotKlines = new LinkedHashMap<>();
        Map<String, List<BigDecimal>> spotClosesByInterval = new LinkedHashMap<>();
        for (var entry : spotKlineData.entrySet()) {
            List<BigDecimal[]> klines = CryptoIndicatorCalculator.parseKlines(entry.getValue());
            if (klines.isEmpty()) continue;
            parsedSpotKlines.put(entry.getKey(), klines);
            List<BigDecimal> closes = new ArrayList<>(klines.size());
            for (BigDecimal[] k : klines) closes.add(k[2]);
            spotClosesByInterval.put(entry.getKey(), closes);
        }

        // 与BuildFeaturesNode对齐的微结构特征

        BigDecimal lastPrice = extractLastPrice(tickerRaw, closesByInterval);
        BigDecimal spotLastPrice = extractLastPrice(spotTickerRaw, spotClosesByInterval);

        double spotBidAskImbalance = calcBidAskImbalance(spotObRaw);
        BigDecimal spotPriceChange5m = calcRecentPctChange(spotClosesByInterval.get("1m"), 5);
        double spotPerpBasisBps = calcBasisBps(lastPrice, spotLastPrice);
        double spotLeadLagScore = calcSpotLeadLagScore(parsedSpotKlines, parsedKlines);

        double bidAskImbalance = calcBidAskImbalance(obRaw);
        double fundingDeviation = calcFundingDeviation(fundingRaw);
        double[] fundingTrend = calcFundingTrend(fundingHistRaw);
        double oiChangeRate = calcOiChangeRate(oiHistRaw);
        double lsrExtreme = calcLsrExtreme(lsrRaw);
        double[] liqResult = calcLiquidationPressure(forceOrdersRaw);
        double topTraderBias = calcTrendBias(topTraderRaw, "longShortRatio", 0.5);
        double takerBuySellPressure = calcTrendBias(takerRaw, "buySellRatio", 0.3);
        int fearGreedIndex = calcFearGreed(fgiRaw);
        String fearGreedLabel = mapFearGreedLabel(fearGreedIndex);

        double tradeDelta = calcTradeDeltaFallback(parsedKlines);
        double tradeIntensity = 0;
        double largeTradeBias = 0;
        if (orderFlowAggregator != null && orderFlowAggregator.hasData(symbol)) {
            OrderFlowAggregator.Metrics m3 = orderFlowAggregator.getMetrics(symbol, 180);
            if (m3 != null && m3.tradeCount() >= 10) {
                tradeDelta = Math.clamp(m3.tradeDelta(), -1, 1);
                largeTradeBias = Math.clamp(m3.largeTradeBias(), -1, 1);
                OrderFlowAggregator.Metrics m60 = orderFlowAggregator.getMetrics(symbol, 60);
                if (m60 != null && m60.tradeCount() > 0) {
                    tradeIntensity = Math.clamp(m60.tradeIntensity() / 10.0, 0, 3);
                }
            }
        }

        BigDecimal atr1m = extractAtr(indicatorsByTf, "1m");
        BigDecimal atr5m = extractAtr(indicatorsByTf, "5m");
        BigDecimal bollBw = extractBollBandwidth(indicatorsByTf, "5m");
        boolean bollSqueeze = bollBw != null && bollBw.doubleValue() < 1.5;

        Map<String, BigDecimal> priceChanges = new LinkedHashMap<>();
        for (String[] src : PRICE_CHANGE_SOURCES) {
            List<BigDecimal> closes = closesByInterval.get(src[1]);
            int bars = Integer.parseInt(src[2]);
            if (closes == null || closes.size() <= bars) continue;
            BigDecimal change = CryptoIndicatorCalculator.pctChange(
                    closes.get(closes.size() - 1 - bars), closes.getLast());
            if (change != null) priceChanges.put(src[0], change);
        }

        String regime = detectRegime(indicatorsByTf, bollBw, parsedKlines);

        double dvolIndex = parseDvol(dvolRaw);
        double[] ivFeatures = parseOptionIv(bookSummaryRaw, lastPrice);
        double atmIv = ivFeatures[0];
        double ivSkew25d = ivFeatures[1];
        double ivTermSlope = ivFeatures[2];

        long totalMs = System.currentTimeMillis() - startMs;
        log.info("[AI-Trader] 数据采集+计算完成 symbol={} klines={} spotKlines={} ind={} " +
                        "collect={}ms total={}ms " +
                        "futImb={} spotImb={} basis={}bps spotLead={} " +
                        "fundingDev={} fundTrend={} fundExtreme={} oiChg={} lsr={} liqP={} liqV={} " +
                        "topTrader={} taker={} fgi={}/{} tradeDelta={} tradeIntensity={} largeBias={} " +
                        "atr1m={} atr5m={} bollBw={} squeeze={} regime={} " +
                        "dvol={} atmIv={} skew={} termSlope={}",
                symbol, parsedKlines.size(), parsedSpotKlines.size(), indicatorsByTf.size(),
                collectMs, totalMs,
                fmt3(bidAskImbalance), fmt3(spotBidAskImbalance),
                String.format("%.2f", spotPerpBasisBps), fmt3(spotLeadLagScore),
                fmt3(fundingDeviation), fmt3(fundingTrend[0]), fmt3(fundingTrend[1]),
                fmt3(oiChangeRate), fmt3(lsrExtreme),
                fmt3(liqResult[0]), String.format("%.0f", liqResult[1]),
                fmt3(topTraderBias), fmt3(takerBuySellPressure),
                fearGreedIndex, fearGreedLabel,
                fmt3(tradeDelta), fmt2(tradeIntensity), fmt3(largeTradeBias),
                atr1m, atr5m, bollBw, bollSqueeze, regime,
                String.format("%.1f", dvolIndex), String.format("%.1f", atmIv),
                String.format("%.2f", ivSkew25d), String.format("%.2f", ivTermSlope));

        return formatMarketData(new StringBuilder(), symbol, tickerRaw, obRaw, oiRaw, fundingRaw,
                lsrRaw, topTraderRaw, takerRaw, fgiRaw,
                spotObRaw, lastPrice, spotLastPrice,
                spotBidAskImbalance, spotPriceChange5m, spotPerpBasisBps, spotLeadLagScore,
                indicatorsByTf, priceChanges, bidAskImbalance,
                fundingDeviation, fundingTrend, oiChangeRate, lsrExtreme, liqResult,
                topTraderBias, takerBuySellPressure,
                tradeDelta, tradeIntensity, largeTradeBias,
                atr1m, atr5m, bollBw, bollSqueeze, regime,
                fearGreedIndex, fearGreedLabel,
                dvolIndex, atmIv, ivSkew25d, ivTermSlope,
                newsRaw);
    }

    // ==================== 格式化 ====================

    private String formatMarketData(StringBuilder sb, String symbol,
                                    String tickerRaw, String obRaw, String oiRaw,
                                    String fundingRaw, String lsrRaw, String topTraderRaw,
                                    String takerRaw, String fgiRaw,
                                    String spotObRaw,
                                    BigDecimal lastPrice, BigDecimal spotLastPrice,
                                    double spotBidAskImbalance, BigDecimal spotPriceChange5m,
                                    double spotPerpBasisBps, double spotLeadLagScore,
                                    Map<String, Map<String, Object>> indicatorsByTf,
                                    Map<String, BigDecimal> priceChanges,
                                    double bidAskImbalance,
                                    double fundingDeviation, double[] fundingTrend,
                                    double oiChangeRate, double lsrExtreme,
                                    double[] liqResult, double topTraderBias, double takerBuySellPressure,
                                    double tradeDelta, double tradeIntensity, double largeTradeBias,
                                    BigDecimal atr1m, BigDecimal atr5m, BigDecimal bollBw, boolean bollSqueeze,
                                    String regime,
                                    int fearGreedIndex, String fearGreedLabel,
                                    double dvolIndex, double atmIv, double ivSkew25d, double ivTermSlope,
                                    String newsRaw) {

        sb.append("\n### 实时行情\n");
        if (tickerRaw != null) {
            try {
                var t = JSON.parseObject(tickerRaw);
                sb.append(String.format("- 24h涨跌: %s%% 高=%s 低=%s 成交额=%sUSDT\n",
                        t.getString("priceChangePercent"),
                        t.getString("highPrice"), t.getString("lowPrice"),
                        t.getString("quoteVolume")));
            } catch (Exception ignored) {}
        }

        sb.append("\n### 盘口与微结构\n");
        if (obRaw != null) {
            try {
                var o = JSON.parseObject(obRaw);
                var bids = o.getJSONArray("bids");
                var asks = o.getJSONArray("asks");
                if (bids != null && asks != null && !bids.isEmpty() && !asks.isEmpty()) {
                    BigDecimal bestBid = bids.getJSONArray(0).getBigDecimal(0);
                    BigDecimal bestAsk = asks.getJSONArray(0).getBigDecimal(0);
                    sb.append(String.format("- 合约 买一=%s 卖一=%s 价差=%s\n",
                            bestBid, bestAsk, bestAsk.subtract(bestBid).toPlainString()));
                }
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- 合约盘口失衡: %.3f (正=买压,负=卖压)\n", bidAskImbalance));
        sb.append(String.format("- 订单流: tradeDelta=%.3f tradeIntensity=%.2f largeTradeBias=%.3f\n",
                tradeDelta, tradeIntensity, largeTradeBias));

        sb.append("\n### 现货-合约联动\n");
        if (spotObRaw != null) {
            try {
                var o = JSON.parseObject(spotObRaw);
                var bids = o.getJSONArray("bids");
                var asks = o.getJSONArray("asks");
                if (bids != null && asks != null && !bids.isEmpty() && !asks.isEmpty()) {
                    sb.append(String.format("- 现货 买一=%s 卖一=%s\n",
                            bids.getJSONArray(0).getBigDecimal(0), asks.getJSONArray(0).getBigDecimal(0)));
                }
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- 现货盘口失衡: %.3f\n", spotBidAskImbalance));
        sb.append(String.format("- 现货5m变化: %s%%\n", spotPriceChange5m));
        sb.append(String.format("- 现货-合约基差: %.2f bps (正=合约溢价)\n", spotPerpBasisBps));
        sb.append(String.format("- 现货领先度: %.3f (正=现货更强)\n", spotLeadLagScore));
        if (spotLastPrice != null && lastPrice != null) {
            sb.append(String.format("- 现货价=%s 合约价=%s\n", spotLastPrice.toPlainString(), lastPrice.toPlainString()));
        }

        sb.append("\n### 持仓量与资金费率\n");
        if (oiRaw != null) {
            try {
                sb.append("- OI: ").append(JSON.parseObject(oiRaw).getString("openInterest")).append("\n");
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- OI变化率: %.3f (正=资金流入)\n", oiChangeRate));
        if (fundingRaw != null) {
            try {
                // premiumIndex返回单个对象
                var fi = JSON.parseObject(fundingRaw);
                sb.append(String.format("- 当前资金费率: %s 下次结算: %s\n",
                        fi.getString("lastFundingRate"), fi.getString("nextFundingTime")));
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- 资金费率偏离: %.3f 趋势: %.3f 极端度: %.3f\n",
                fundingDeviation, fundingTrend[0], fundingTrend[1]));

        sb.append("\n### 市场情绪\n");
        if (lsrRaw != null) {
            try {
                var arr = JSON.parseArray(lsrRaw);
                if (!arr.isEmpty()) {
                    sb.append("- 散户多空比: ").append(arr.getJSONObject(0).getString("longShortRatio")).append("\n");
                }
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- 散户极端度: %.3f\n", lsrExtreme));
        if (topTraderRaw != null) {
            try {
                var arr = JSON.parseArray(topTraderRaw);
                if (!arr.isEmpty()) {
                    var latest = arr.getJSONObject(0);
                    sb.append(String.format("- 大户: 多=%s%% 空=%s%% 趋势偏差=%.3f\n",
                            latest.getString("longAccount"), latest.getString("shortAccount"), topTraderBias));
                }
            } catch (Exception ignored) {}
        }
        if (takerRaw != null) {
            try {
                var arr = JSON.parseArray(takerRaw);
                if (!arr.isEmpty()) {
                    sb.append(String.format("- 主动买卖比: %s 趋势偏差=%.3f\n",
                            arr.getJSONObject(0).getString("buySellRatio"), takerBuySellPressure));
                }
            } catch (Exception ignored) {}
        }
        sb.append(String.format("- 爆仓压力: %.3f 总爆仓额=%.0fUSDT\n", liqResult[0], liqResult[1]));
        sb.append(String.format("- 恐贪指数: %d (%s)\n", fearGreedIndex, fearGreedLabel));

        sb.append("\n### 技术指标\n");
        for (String tf : List.of("5m", "15m", "1h", "4h")) {
            Map<String, Object> ind = indicatorsByTf.get(tf);
            if (ind == null) continue;
            sb.append(String.format("- [%s] RSI=%s MACD_DIF=%s MACD_hist=%s ADX=%s +DI=%s -DI=%s 均线排列=%s\n",
                    tf,
                    bdVal(ind.get("rsi14")),
                    bdVal(ind.get("macd_dif")),
                    bdVal(ind.get("macd_hist")),
                    bdVal(ind.get("adx")),
                    bdVal(ind.get("plus_di")),
                    bdVal(ind.get("minus_di")),
                    ind.get("ma_alignment")));
            sb.append(String.format("  布林: 上=%s 中=%s 下=%s PB=%s BW=%s\n",
                    bdVal(ind.get("boll_upper")), bdVal(ind.get("boll_mid")),
                    bdVal(ind.get("boll_lower")), bdVal(ind.get("boll_pb")),
                    bdVal(ind.get("boll_bandwidth"))));
            sb.append(String.format("  KDJ: K=%s D=%s J=%s 量比=%s 动量=%s\n",
                    bdVal(ind.get("kdj_k")), bdVal(ind.get("kdj_d")), bdVal(ind.get("kdj_j")),
                    bdVal(ind.get("volume_ratio")), ind.get("close_trend")));
        }

        sb.append("\n### 波动率与状态\n");
        sb.append(String.format("- ATR(1m)=%s ATR(5m)=%s BollBW(5m)=%s squeeze=%s\n",
                atr1m, atr5m, bollBw, bollSqueeze));
        sb.append(String.format("- 市场状态: %s\n", regime));

        if (!priceChanges.isEmpty()) {
            sb.append("- 多周期涨跌:");
            priceChanges.forEach((k, v) ->
                    sb.append(String.format(" %s=%s%%", k, v.setScale(2, RoundingMode.HALF_UP))));
            sb.append("\n");
        }

        if (dvolIndex > 0 || atmIv > 0) {
            sb.append("\n### 期权波动率\n");
            if (dvolIndex > 0) sb.append(String.format("- DVOL指数: %.1f\n", dvolIndex));
            if (atmIv > 0) sb.append(String.format("- ATM隐含波动率: %.1f%%\n", atmIv));
            if (ivSkew25d != 0) sb.append(String.format("- 25d偏斜: %.2f (正=看涨偏好)\n", ivSkew25d));
            if (ivTermSlope != 0) sb.append(String.format("- 期限结构斜率: %.2f (正=远期更高)\n", ivTermSlope));
        }

        String newsSection = formatNews(newsRaw);
        if (!newsSection.isEmpty()) sb.append(newsSection);

        return sb.toString();
    }

    private String formatNews(String newsData) {
        if (newsData == null || newsData.isBlank() || "{}".equals(newsData)) return "";
        try {
            JSONObject root = JSON.parseObject(newsData.getBytes(StandardCharsets.UTF_8));
            JSONArray items = root.getJSONArray("Data");
            if (items == null || items.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n### 相关新闻\n");
            int count = 0;
            for (int i = 0; i < items.size() && count < 5; i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;
                String title = item.getString("TITLE");
                if (title != null && !title.isBlank()) {
                    sb.append("- ").append(title).append("\n");
                    count++;
                }
            }
            return count > 0 ? sb.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 数据采集辅助 ====================

    private String buildForceOrdersJson(String symbol) {
        List<ForceOrder> orders = forceOrderService.getRecent(symbol, 30);
        if (orders == null || orders.isEmpty()) return null;
        List<Map<String, Object>> list = orders.stream().map(o -> Map.<String, Object>of(
                "side", o.getSide(), "price", o.getAvgPrice(), "origQty", o.getQuantity()
        )).toList();
        return JSON.toJSONString(list);
    }

    private String safeGet(Future<String> f, String label) {
        try { return f.get(10, TimeUnit.SECONDS); }
        catch (Exception e) { log.debug("[AI-Trader] {}跳过: {}", label, e.getMessage()); return null; }
    }

    // ==================== 盘口微结构 ====================

    private double calcBidAskImbalance(String obJson) {
        if (obJson == null) return 0;
        try {
            JSONObject ob = JSON.parseObject(obJson);
            double bidVol = sumDepth(ob.getJSONArray("bids"), 5);
            double askVol = sumDepth(ob.getJSONArray("asks"), 5);
            double total = bidVol + askVol;
            return total > 0 ? (bidVol - askVol) / total : 0;
        } catch (Exception e) { return 0; }
    }

    private double sumDepth(JSONArray levels, int depth) {
        if (levels == null) return 0;
        double sum = 0;
        for (int i = 0; i < Math.min(levels.size(), depth); i++) {
            sum += levels.getJSONArray(i).getDoubleValue(1);
        }
        return sum;
    }

    // ==================== 资金费率 ====================

    private double calcFundingDeviation(String fundingJson) {
        if (fundingJson == null) return 0;
        try {
            JSONObject f = JSON.parseObject(fundingJson);
            double rate = f.getDoubleValue("lastFundingRate");
            return (rate - 0.0001) / 0.0003;
        } catch (Exception e) { return 0; }
    }

    private double[] calcFundingTrend(String fundingHistJson) {
        if (fundingHistJson == null || fundingHistJson.isBlank()) return new double[]{0, 0};
        try {
            JSONArray arr = JSON.parseArray(fundingHistJson);
            if (arr == null || arr.size() < 3) return new double[]{0, 0};
            List<Double> rates = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                rates.add(arr.getJSONObject(i).getDoubleValue("fundingRate"));
            }
            int mid = rates.size() / 2;
            double olderAvg = rates.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double recentAvg = rates.subList(mid, rates.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double trend = Math.clamp((recentAvg - olderAvg) / 0.0003, -1, 1);
            double totalAvg = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0001);
            double latest = rates.getLast();
            double extreme = Math.clamp((latest - totalAvg) / 0.0003, -1, 1);
            return new double[]{trend, extreme};
        } catch (Exception e) { return new double[]{0, 0}; }
    }

    // ==================== 持仓量 ====================

    private double calcOiChangeRate(String oiHistJson) {
        if (oiHistJson == null || oiHistJson.isBlank()) return 0;
        try {
            JSONArray arr = JSON.parseArray(oiHistJson);
            if (arr == null || arr.size() < 2) return 0;
            double oldOi = arr.getJSONObject(0).getDoubleValue("sumOpenInterest");
            double newOi = arr.getJSONObject(arr.size() - 1).getDoubleValue("sumOpenInterest");
            if (oldOi <= 0) return 0;
            return (newOi - oldOi) / oldOi;
        } catch (Exception e) { return 0; }
    }

    // ==================== 情绪指标 ====================

    private double calcLsrExtreme(String lsrJson) {
        if (lsrJson == null) return 0;
        try {
            JSONArray arr = JSON.parseArray(lsrJson);
            if (arr == null || arr.isEmpty()) return 0;
            double ratio = arr.getJSONObject(arr.size() - 1).getDoubleValue("longShortRatio");
            if (ratio > 2.0) return 1.0;
            if (ratio < 0.5) return -1.0;
            return ratio - 1.0;
        } catch (Exception e) { return 0; }
    }

    private double[] calcLiquidationPressure(String forceOrdersJson) {
        if (forceOrdersJson == null || forceOrdersJson.isBlank()) return new double[]{0, 0};
        try {
            JSONArray arr = JSON.parseArray(forceOrdersJson);
            if (arr == null || arr.isEmpty()) return new double[]{0, 0};
            double longLiqVol = 0, shortLiqVol = 0;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject order = arr.getJSONObject(i);
                double vol = order.getDoubleValue("price") * order.getDoubleValue("origQty");
                if ("SELL".equals(order.getString("side"))) longLiqVol += vol;
                else shortLiqVol += vol;
            }
            double total = longLiqVol + shortLiqVol;
            if (total < 1) return new double[]{0, 0};
            return new double[]{Math.clamp((longLiqVol - shortLiqVol) / total, -1, 1), total};
        } catch (Exception e) { return new double[]{0, 0}; }
    }

    private double calcTrendBias(String json, String fieldName, double divisor) {
        if (json == null || json.isBlank()) return 0;
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null || arr.size() < 4) return 0;
            int mid = arr.size() / 2;
            double olderAvg = 0, recentAvg = 0;
            for (int i = 0; i < mid; i++) olderAvg += arr.getJSONObject(i).getDoubleValue(fieldName);
            olderAvg /= mid;
            for (int i = mid; i < arr.size(); i++) recentAvg += arr.getJSONObject(i).getDoubleValue(fieldName);
            recentAvg /= (arr.size() - mid);
            return Math.clamp((recentAvg - olderAvg) / divisor, -1, 1);
        } catch (Exception e) { return 0; }
    }

    private int calcFearGreed(String fearGreedJson) {
        if (fearGreedJson == null || fearGreedJson.isBlank() || "{}".equals(fearGreedJson)) return -1;
        try {
            JSONArray data = JSON.parseObject(fearGreedJson).getJSONArray("data");
            if (data == null || data.isEmpty()) return -1;
            return data.getJSONObject(0).getIntValue("value");
        } catch (Exception e) { return -1; }
    }

    private String mapFearGreedLabel(int index) {
        if (index < 0) return "UNKNOWN";
        if (index <= 24) return "EXTREME_FEAR";
        if (index <= 44) return "FEAR";
        if (index <= 55) return "NEUTRAL";
        if (index <= 74) return "GREED";
        return "EXTREME_GREED";
    }

    // ==================== 订单流 ====================

    private double calcTradeDeltaFallback(Map<String, List<BigDecimal[]>> parsedKlines) {
        List<BigDecimal[]> k1m = parsedKlines.get("1m");
        if (k1m != null && k1m.size() >= 10) {
            double ratio = CryptoIndicatorCalculator.takerBuyRatio(k1m, 10);
            return (ratio - 0.5) * 2.0;
        }
        List<BigDecimal[]> k5m = parsedKlines.get("5m");
        if (k5m != null && k5m.size() >= 3) {
            double ratio = CryptoIndicatorCalculator.takerBuyRatio(k5m, 3);
            return (ratio - 0.5) * 2.0;
        }
        return 0;
    }

    // ==================== 现货联动 ====================

    private BigDecimal extractLastPrice(String tickerJson, Map<String, List<BigDecimal>> closes) {
        if (tickerJson != null) {
            try {
                JSONObject t = JSON.parseObject(tickerJson);
                String p = t.getString("lastPrice");
                if (p == null || p.isBlank()) p = t.getString("price");
                if (p != null) return new BigDecimal(p);
            } catch (Exception ignored) {}
        }
        List<BigDecimal> c1m = closes.get("1m");
        return (c1m != null && !c1m.isEmpty()) ? c1m.getLast() : null;
    }

    private BigDecimal calcRecentPctChange(List<BigDecimal> closes, int bars) {
        if (closes == null || closes.size() <= bars) return null;
        return CryptoIndicatorCalculator.pctChange(closes.get(closes.size() - 1 - bars), closes.getLast());
    }

    private double calcBasisBps(BigDecimal futuresPrice, BigDecimal spotPrice) {
        if (futuresPrice == null || spotPrice == null || spotPrice.signum() <= 0) return 0;
        return futuresPrice.subtract(spotPrice)
                .multiply(BigDecimal.valueOf(10000))
                .divide(spotPrice, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double calcSpotLeadLagScore(Map<String, List<BigDecimal[]>> spotKlines,
                                         Map<String, List<BigDecimal[]>> futuresKlines) {
        Double fast = calcLeadLagWindowScore(spotKlines.get("1m"), futuresKlines.get("1m"), 3, 0.08);
        if (fast != null) return fast;
        Double slow = calcLeadLagWindowScore(spotKlines.get("5m"), futuresKlines.get("5m"), 1, 0.12);
        return slow != null ? slow : 0;
    }

    private Double calcLeadLagWindowScore(List<BigDecimal[]> spot, List<BigDecimal[]> futures,
                                          int bars, double scalePct) {
        if (spot == null || futures == null || spot.size() <= bars || futures.size() <= bars) return null;
        BigDecimal spotChange = CryptoIndicatorCalculator.pctChange(
                spot.get(spot.size() - 1 - bars)[2], spot.getLast()[2]);
        BigDecimal futuresChange = CryptoIndicatorCalculator.pctChange(
                futures.get(futures.size() - 1 - bars)[2], futures.getLast()[2]);
        if (spotChange == null || futuresChange == null || scalePct <= 0) return null;
        return Math.clamp((spotChange.subtract(futuresChange)).doubleValue() / scalePct, -1, 1);
    }

    // ==================== 期权IV ====================

    private double parseDvol(String dvolJson) {
        if (dvolJson == null || dvolJson.isBlank()) return 0;
        try {
            JSONObject root = JSON.parseObject(dvolJson);
            JSONArray data = root.getJSONObject("result").getJSONArray("data");
            if (data == null || data.isEmpty()) return 0;
            return data.getJSONArray(data.size() - 1).getDoubleValue(4);
        } catch (Exception e) {
            log.debug("[AI-Trader] DVOL解析跳过: {}", e.getMessage());
            return 0;
        }
    }

    private double[] parseOptionIv(String bookSummaryJson, BigDecimal lastPrice) {
        double[] empty = {0, 0, 0};
        if (bookSummaryJson == null || bookSummaryJson.isBlank() || lastPrice == null) return empty;
        try {
            JSONObject root = JSON.parseObject(bookSummaryJson);
            JSONArray results = root.getJSONArray("result");
            if (results == null || results.isEmpty()) return empty;

            double spot = lastPrice.doubleValue();
            if (spot <= 0) return empty;

            record OptionInfo(String expiry, double strike, String type, double markIv) {}
            Map<String, List<OptionInfo>> byExpiry = new TreeMap<>(
                    Comparator.comparing(s -> LocalDate.parse(s, DERIBIT_EXPIRY_FMT)));

            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                double markIv = item.getDoubleValue("mark_iv");
                if (markIv <= 0) continue;
                String name = item.getString("instrument_name");
                if (name == null) continue;
                String[] parts = name.split("-");
                if (parts.length < 4) continue;
                String expiry = parts[1];
                double strike;
                try { strike = Double.parseDouble(parts[2]); } catch (NumberFormatException e) { continue; }
                String type = parts[3];
                byExpiry.computeIfAbsent(expiry, k -> new ArrayList<>())
                        .add(new OptionInfo(expiry, strike, type, markIv));
            }

            List<String> expiries = byExpiry.entrySet().stream()
                    .filter(e -> e.getValue().size() >= 4)
                    .map(Map.Entry::getKey)
                    .toList();
            if (expiries.isEmpty()) return empty;

            String nearExpiry = expiries.getFirst();
            List<OptionInfo> nearOptions = byExpiry.get(nearExpiry);

            double calcAtmIv = nearOptions.stream()
                    .filter(o -> "C".equals(o.type()))
                    .min(Comparator.comparingDouble(o -> Math.abs(o.strike() - spot)))
                    .map(OptionInfo::markIv)
                    .orElse(0.0);

            double otmLow = spot * 1.05, otmHigh = spot * 1.08;
            double otmPutLow = spot * 0.92, otmPutHigh = spot * 0.95;
            double callIv = nearOptions.stream()
                    .filter(o -> "C".equals(o.type()) && o.strike() >= otmLow && o.strike() <= otmHigh)
                    .mapToDouble(OptionInfo::markIv).average().orElse(0);
            double putIv = nearOptions.stream()
                    .filter(o -> "P".equals(o.type()) && o.strike() >= otmPutLow && o.strike() <= otmPutHigh)
                    .mapToDouble(OptionInfo::markIv).average().orElse(0);
            double calcSkew = (callIv > 0 && putIv > 0) ? callIv - putIv : 0;

            double calcTermSlope = 0;
            if (expiries.size() >= 2) {
                String farExpiry = expiries.get(1);
                List<OptionInfo> farOptions = byExpiry.get(farExpiry);
                double farAtmIv = farOptions.stream()
                        .filter(o -> "C".equals(o.type()))
                        .min(Comparator.comparingDouble(o -> Math.abs(o.strike() - spot)))
                        .map(OptionInfo::markIv)
                        .orElse(0.0);
                if (calcAtmIv > 0 && farAtmIv > 0) calcTermSlope = farAtmIv - calcAtmIv;
            }

            return new double[]{calcAtmIv, calcSkew, calcTermSlope};
        } catch (Exception e) {
            log.debug("[AI-Trader] 期权IV解析跳过: {}", e.getMessage());
            return empty;
        }
    }

    // ==================== 波动率与状态 ====================

    private BigDecimal extractAtr(Map<String, Map<String, Object>> indicators, String tf) {
        Map<String, Object> ind = indicators.get(tf);
        if (ind == null) return null;
        Object v = ind.get("atr14");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private BigDecimal extractBollBandwidth(Map<String, Map<String, Object>> indicators, String tf) {
        Map<String, Object> ind = indicators.get(tf);
        if (ind == null) return null;
        Object v = ind.get("boll_bandwidth");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private String detectRegime(Map<String, Map<String, Object>> indicators,
                                BigDecimal bollBw, Map<String, List<BigDecimal[]>> parsedKlines) {
        Map<String, Object> ind = indicators.getOrDefault("15m", indicators.get("5m"));
        if (ind == null) return "RANGE";

        BigDecimal adx = toBd(ind.get("adx"));
        BigDecimal plusDi = toBd(ind.get("plus_di"));
        BigDecimal minusDi = toBd(ind.get("minus_di"));
        double adxVal = adx != null ? adx.doubleValue() : 15;

        if (isAtrSpike(parsedKlines)) return "SHOCK";
        if (adxVal < 15 && bollBw != null && bollBw.doubleValue() < 1.5) return "SQUEEZE";
        if (adxVal > 25 && plusDi != null && minusDi != null) {
            return plusDi.compareTo(minusDi) > 0 ? "TREND_UP" : "TREND_DOWN";
        }
        if (adxVal < 20) return "RANGE";
        return "RANGE";
    }

    private boolean isAtrSpike(Map<String, List<BigDecimal[]>> parsedKlines) {
        List<BigDecimal[]> k5m = parsedKlines.get("5m");
        if (k5m == null || k5m.size() < 66) return false;
        double recentAtr = avgTrueRange(k5m, k5m.size() - 6, k5m.size());
        double histAtr = avgTrueRange(k5m, k5m.size() - 66, k5m.size() - 6);
        return histAtr > 0 && recentAtr > 2.0 * histAtr;
    }

    private double avgTrueRange(List<BigDecimal[]> klines, int from, int to) {
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, from); i < to; i++) {
            double high = klines.get(i)[0].doubleValue();
            double low = klines.get(i)[1].doubleValue();
            double prevClose = klines.get(i - 1)[2].doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    // ==================== 通用工具 ====================

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static String bdVal(Object v) {
        if (v == null) return "N/A";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return v.toString();
    }

    private static String fmt3(double v) { return String.format("%.3f", v); }
    private static String fmt2(double v) { return String.format("%.2f", v); }
}
