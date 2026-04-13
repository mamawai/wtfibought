package com.mawai.wiibservice.task;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.trading.AiTradingTools;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    public AiTradingScheduler(UserMapper userMapper,
                              FuturesTradingService futuresTradingService,
                              FuturesRiskService futuresRiskService,
                              FuturesPositionMapper futuresPositionMapper,
                              QuantForecastCycleMapper cycleMapper,
                              QuantSignalDecisionMapper decisionMapper,
                              AiTradingDecisionMapper tradingDecisionMapper,
                              CacheService cacheService,
                              AiAgentRuntimeManager runtimeManager) {
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.futuresRiskService = futuresRiskService;
        this.futuresPositionMapper = futuresPositionMapper;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.tradingDecisionMapper = tradingDecisionMapper;
        this.cacheService = cacheService;
        this.runtimeManager = runtimeManager;
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

    @Scheduled(cron = "0 10,20,40,50 * * * *")
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
            var allPositionsBefore = futuresTradingService.getUserPositions(userId, null);
            BigDecimal equityBefore = calcTotalEquity(userBefore, allPositionsBefore);

            var positions = futuresTradingService.getUserPositions(userId, symbol);
            String positionSnapshot = JSON.toJSONString(positions);

            var forecast = cycleMapper.selectLatest(symbol);
            var signals = decisionMapper.selectLatestBySymbol(symbol);

            List<AiTradingDecision> recentDecisions = tradingDecisionMapper.selectRecentBySymbol(symbol, 5);

            BigDecimal futuresPrice = cacheService.getFuturesPrice(symbol);
            BigDecimal markPrice = cacheService.getMarkPrice(symbol);

            String userPrompt = buildUserPrompt(symbol, userBefore, positions, forecast, signals,
                    recentDecisions, futuresPrice, markPrice);
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
                            action = "HOLD";
                            reasoning = "JSON数组中未匹配到symbol=" + symbol + "，默认HOLD";
                            log.info("[AI-Trader] JSON数组中未精确匹配symbol={}，fallback到HOLD", symbol);
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
            var allPositionsAfter = futuresTradingService.getUserPositions(userId, null);
            BigDecimal equityAfter = calcTotalEquity(userAfter, allPositionsAfter);

            AiTradingDecision decision = new AiTradingDecision();
            decision.setCycleNo(cycleNo);
            decision.setSymbol(symbol);
            decision.setAction(action != null ? action : "HOLD");
            decision.setReasoning(reasoning);
            decision.setMarketContext(String.format("price=%s mark=%s", futuresPrice, markPrice));
            decision.setPositionSnapshot(positionSnapshot);
            decision.setExecutionResult(fullResponse.length() > 4000
                    ? fullResponse.substring(fullResponse.length() - 4000) : fullResponse);
            decision.setBalanceBefore(equityBefore);
            decision.setBalanceAfter(equityAfter);
            tradingDecisionMapper.insert(decision);

            log.info("[AI-Trader] 交易周期完成 symbol={} action={} equityBefore={} equityAfter={}",
                    symbol, action, equityBefore, equityAfter);

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
                                   BigDecimal futuresPrice, BigDecimal markPrice) {
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
            sb.append("当前无持仓。\n");
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
            sb.append("注：置信度=方向一致性评分（投票有多整齐），非开仓强度；仓位大小由风控层独立控制。\n");
            for (Object sig : signals) {
                if (sig instanceof QuantSignalDecision s) {
                    sb.append(String.format("- [%s] 方向=%s 置信度=%.0f%% 建议杠杆≤%dx 仓位≤%.0f%%\n",
                            s.getHorizon(), s.getDirection(),
                            s.getConfidence().doubleValue() * 100,
                            s.getMaxLeverage(),
                            s.getMaxPositionPct().doubleValue() * 100));
                }
            }
        }

        if (!recentDecisions.isEmpty()) {
            sb.append("\n### 最近").append(recentDecisions.size()).append("次决策\n");
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
            if (confidence != null) sb.append("- 系统置信度: ").append(confidence)
                    .append("%（方向一致性评分，非开仓强度；仓位大小由风控层独立控制）\n");

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

    private BigDecimal calcTotalEquity(User user, List<?> allPositions) {
        BigDecimal balance = user != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        BigDecimal unpnl = BigDecimal.ZERO;
        if (allPositions != null) {
            for (Object p : allPositions) {
                if (p instanceof com.mawai.wiibcommon.dto.FuturesPositionDTO dto) {
                    if (dto.getMargin() != null) margin = margin.add(dto.getMargin());
                    if (dto.getUnrealizedPnl() != null) unpnl = unpnl.add(dto.getUnrealizedPnl());
                }
            }
        }
        return balance.add(frozen).add(margin).add(unpnl);
    }

}
