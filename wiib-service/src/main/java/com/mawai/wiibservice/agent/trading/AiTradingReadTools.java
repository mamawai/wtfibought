package com.mawai.wiibservice.agent.trading;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * LLM 可用的 AI 交易只读工具。
 * 不提供开仓、平仓、改止盈止损等写操作。
 */
@Slf4j
public class AiTradingReadTools {

    private final Long aiUserId;
    private final String currentSymbol;
    private final UserMapper userMapper;
    private final FuturesTradingService futuresTradingService;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final CacheService cacheService;

    public AiTradingReadTools(Long aiUserId, String currentSymbol,
                              UserMapper userMapper,
                              FuturesTradingService futuresTradingService,
                              QuantForecastCycleMapper cycleMapper,
                              QuantSignalDecisionMapper decisionMapper,
                              CacheService cacheService) {
        this.aiUserId = aiUserId;
        this.currentSymbol = currentSymbol;
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.cacheService = cacheService;
    }

    @Tool(description = "查询AI交易账户信息：可用余额、冻结余额、是否破产")
    public String getAccountInfo() {
        User user = userMapper.selectById(aiUserId);
        if (user == null) return "AI账户不存在";
        return JSON.toJSONString(new Object() {
            public final BigDecimal balance = user.getBalance();
            public final BigDecimal frozenBalance = user.getFrozenBalance();
            public final boolean isBankrupt = Boolean.TRUE.equals(user.getIsBankrupt());
        });
    }

    @Tool(description = "查询AI当前持仓列表，含未实现盈亏、强平价等")
    public String getPositions(@ToolParam(description = "交易对，如BTCUSDT，传空查全部") String symbol) {
        String s = (symbol != null && !symbol.isBlank()) ? symbol : null;
        List<FuturesPositionDTO> positions = futuresTradingService.getUserPositions(aiUserId, s);
        if (positions.isEmpty()) return "当前无持仓";
        return JSON.toJSONString(positions);
    }

    @Tool(description = "查询AI最近的合约订单记录")
    public String getRecentOrders(@ToolParam(description = "交易对，如BTCUSDT，传空查全部") String symbol) {
        String s = (symbol != null && !symbol.isBlank()) ? symbol : null;
        var page = futuresTradingService.getUserOrders(aiUserId, null, 1, 10, s);
        if (page.getRecords().isEmpty()) return "无历史订单";
        return JSON.toJSONString(page.getRecords());
    }

    @Tool(description = "查询指定交易对的最新量化分析结果：方向、置信度、杠杆建议、风控状态")
    public String getLatestForecast(@ToolParam(description = "交易对，如BTCUSDT") String symbol) {
        if (symbol == null || symbol.isBlank()) symbol = currentSymbol;
        QuantForecastCycle cycle = cycleMapper.selectLatestHeavy(symbol);
        if (cycle == null) return "暂无量化分析数据";

        List<QuantSignalDecision> signals = decisionMapper.selectLatestHeavyBySymbol(symbol);
        return JSON.toJSONString(new Object() {
            public final String cycleId = cycle.getCycleId();
            public final String forecastTime = cycle.getForecastTime() != null ? cycle.getForecastTime().toString() : null;
            public final String overallDecision = cycle.getOverallDecision();
            public final String riskStatus = cycle.getRiskStatus();
            public final Object signalDecisions = signals;
        });
    }

    @Tool(description = "查询指定交易对的当前价格（合约价和标记价）")
    public String getMarketPrice(@ToolParam(description = "交易对，如BTCUSDT") String symbol) {
        if (symbol == null || symbol.isBlank()) return "symbol不能为空";
        BigDecimal futuresPrice = cacheService.getFuturesPrice(symbol);
        BigDecimal markPrice = cacheService.getMarkPrice(symbol);
        return JSON.toJSONString(new Object() {
            public final String sym = symbol;
            public final BigDecimal price = futuresPrice;
            public final BigDecimal mark = markPrice;
        });
    }

    @Tool(description = "查询指定交易对的市场微观数据快照：恐贪指数、资金费率、爆仓压力、大户持仓、盘口失衡、多周期涨跌幅、市场状态、波动率等。用于辅助解释交易环境。")
    public String getMarketSnapshot(@ToolParam(description = "交易对，如BTCUSDT") String symbol) {
        if (symbol == null || symbol.isBlank()) symbol = currentSymbol;
        QuantForecastCycle cycle = cycleMapper.selectLatest(symbol);
        if (cycle == null || cycle.getSnapshotJson() == null) return "暂无市场快照数据";
        try {
            var snap = JSON.parseObject(cycle.getSnapshotJson());
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("snapshotTime", snap.getString("snapshotTime"));
            result.put("lastPrice", snap.get("lastPrice"));
            result.put("barHigh", snap.get("barHigh"));
            result.put("barLow", snap.get("barLow"));
            result.put("spotLastPrice", snap.get("spotLastPrice"));
            result.put("regime", snap.getString("regime"));
            result.put("regimeConfidence", snap.get("regimeConfidence"));
            result.put("regimeTransition", snap.getString("regimeTransition"));
            result.put("fearGreedIndex", snap.get("fearGreedIndex"));
            result.put("fearGreedLabel", snap.getString("fearGreedLabel"));
            result.put("fundingDeviation", snap.get("fundingDeviation"));
            result.put("fundingRateTrend", snap.get("fundingRateTrend"));
            result.put("fundingRateExtreme", snap.get("fundingRateExtreme"));
            result.put("liquidationPressure", snap.get("liquidationPressure"));
            result.put("liquidationVolumeUsdt", snap.get("liquidationVolumeUsdt"));
            result.put("topTraderBias", snap.get("topTraderBias"));
            result.put("takerBuySellPressure", snap.get("takerBuySellPressure"));
            result.put("bidAskImbalance", snap.get("bidAskImbalance"));
            result.put("oiChangeRate", snap.get("oiChangeRate"));
            result.put("bollSqueeze", snap.get("bollSqueeze"));
            result.put("atr5m", snap.get("atr5m"));
            result.put("dvolIndex", snap.get("dvolIndex"));
            result.put("atmIv", snap.get("atmIv"));
            result.put("priceChanges", snap.get("priceChanges"));
            return JSON.toJSONString(result);
        } catch (Exception e) {
            log.warn("[AI-TradeRead] snapshotJson解析失败: {}", e.getMessage());
            return "快照数据解析失败";
        }
    }
}
