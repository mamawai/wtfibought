package com.mawai.wiibservice.task;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.AiTradingDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.trading.AiTradingTools;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class AiTradingScheduler {

    private static final String AI_LINUX_DO_ID = "AI_TRADER";
    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    private final AtomicLong aiUserId = new AtomicLong(0);
    private final AtomicInteger cycleCounter = new AtomicInteger(0);
    private final Set<String> runningSymbols = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastEventTriggerTime = new ConcurrentHashMap<>();
    private static final long EVENT_GUARD_SECONDS = 30; // 30秒技术去重

    private final UserMapper userMapper;
    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final AiTradingDecisionMapper tradingDecisionMapper;
    private final CacheService cacheService;

    public AiTradingScheduler(UserMapper userMapper,
                              FuturesTradingService futuresTradingService,
                              FuturesRiskService futuresRiskService,
                              FuturesPositionMapper futuresPositionMapper,
                              QuantForecastCycleMapper cycleMapper,
                              QuantSignalDecisionMapper decisionMapper,
                              AiTradingDecisionMapper tradingDecisionMapper,
                              CacheService cacheService) {
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.futuresRiskService = futuresRiskService;
        this.futuresPositionMapper = futuresPositionMapper;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.tradingDecisionMapper = tradingDecisionMapper;
        this.cacheService = cacheService;
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

    @EventListener
    public void onQuantCycleComplete(QuantCycleCompleteEvent event) {
        if (aiUserId.get() == 0) return;
        String symbol = event.getSymbol();
        lastEventTriggerTime.put(symbol, Instant.now());
        int cycleNo = cycleCounter.incrementAndGet();
        log.info("[AI-Trader] 量化{}周期完成→触发交易 symbol={} cycleNo={}",
                event.getCycleType(), symbol, cycleNo);
        Thread.startVirtualThread(() -> runTradingCycle(symbol, cycleNo));
    }

    @Scheduled(cron = "0 */10 * * * *") // 兜底：量化分析失败时仍按计划执行（盯盘持仓管理）
    public void tradingCycle() {
        if (aiUserId.get() == 0) return;
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            Instant lastEvent = lastEventTriggerTime.get(symbol);
            if (lastEvent != null
                    && Instant.now().getEpochSecond() - lastEvent.getEpochSecond() < EVENT_GUARD_SECONDS) {
                log.debug("[AI-Trader] {} 近期已由事件触发，cron跳过", symbol);
                continue;
            }
            int cycleNo = cycleCounter.incrementAndGet();
            log.info("[AI-Trader] cron兜底触发 symbol={} cycleNo={}", symbol, cycleNo);
            Thread.startVirtualThread(() -> runTradingCycle(symbol, cycleNo));
        }
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
        log.info("[AI-Trader] 交易周期开始 symbol={} cycleNo={}", symbol, cycleNo);

        try {
            User userBefore = userMapper.selectById(userId);
            var allPositionsBefore = futuresTradingService.getUserPositions(userId, null);
            BigDecimal equityBefore = calcTotalEquity(userBefore, allPositionsBefore);

            var positions = futuresTradingService.getUserPositions(userId, symbol);
            String positionSnapshot = JSON.toJSONString(positions);

            var forecast = cycleMapper.selectLatest(symbol);
            var signals = decisionMapper.selectLatestBySymbol(symbol);
            List<AiTradingDecision> recentDecisions = tradingDecisionMapper.selectRecentBySymbol(symbol, 10);

            BigDecimal futuresPrice = cacheService.getFuturesPrice(symbol);
            BigDecimal markPrice = cacheService.getMarkPrice(symbol);

            AiTradingTools tools = new AiTradingTools(userId, symbol, userMapper, futuresTradingService,
                    futuresRiskService, futuresPositionMapper, cycleMapper, decisionMapper, cacheService);

            // 确定性执行器决策
            DeterministicTradingExecutor.ExecutionResult result =
                    DeterministicTradingExecutor.execute(
                            symbol, userBefore, positions, forecast, signals,
                            recentDecisions, futuresPrice, markPrice, equityBefore, tools);

            log.info("[AI-Trader] 决策完成 symbol={} action={} reasoning={}",
                    symbol, result.action(), result.reasoning());

            User userAfter = userMapper.selectById(userId);
            var allPositionsAfter = futuresTradingService.getUserPositions(userId, null);
            BigDecimal equityAfter = calcTotalEquity(userAfter, allPositionsAfter);

            AiTradingDecision decision = new AiTradingDecision();
            decision.setCycleNo(cycleNo);
            decision.setSymbol(symbol);
            decision.setAction(result.action());
            decision.setReasoning(result.reasoning());
            decision.setMarketContext(String.format("price=%s mark=%s", futuresPrice, markPrice));
            decision.setPositionSnapshot(positionSnapshot);
            decision.setExecutionResult(result.executionLog().length() > 4000
                    ? result.executionLog().substring(result.executionLog().length() - 4000)
                    : result.executionLog());
            decision.setBalanceBefore(equityBefore);
            decision.setBalanceAfter(equityAfter);
            tradingDecisionMapper.insert(decision);

            log.info("[AI-Trader] 交易周期完成 symbol={} action={} equityBefore={} equityAfter={}",
                    symbol, result.action(), equityBefore, equityAfter);

        } catch (Exception e) {
            log.error("[AI-Trader] 交易周期异常 symbol={}", symbol, e);
        } finally {
            runningSymbols.remove(symbol);
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
