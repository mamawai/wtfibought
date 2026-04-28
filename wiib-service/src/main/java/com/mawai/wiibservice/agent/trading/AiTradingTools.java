package com.mawai.wiibservice.agent.trading;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class AiTradingTools implements TradingOperations {

    private static final int MAX_LEVERAGE = 50;
    private static final int MIN_LEVERAGE = 5;
    private static final BigDecimal MIN_POSITION_VALUE = new BigDecimal("5000");
    // 工具层兜底上限：确定性执行器更严（15%），这里保留给手动/LLM工具调用防越界。
    private static final BigDecimal MAX_POSITION_RATIO = new BigDecimal("0.35");
    private static final BigDecimal MIN_MARGIN_FLOOR = new BigDecimal("100");
    private static final BigDecimal MIN_MARGIN_RATIO = new BigDecimal("0.01"); // 余额的1%
    // 虚拟盘观察档：允许同币种同方向多次开仓，风险由每单SL距离反推仓位 + 保证金上限控制。
    private static final int MAX_OPEN_POSITIONS = 6;
    private static final int MAX_SYMBOL_POSITIONS = 3;
    private static final int ENTRY_COOLDOWN_MINUTES = 10;
    // SL校验容差2bps：匹配执行器adjustForNoiseFloor，防价格漂移/BigDecimal精度误拒
    private static final BigDecimal SL_MIN_TOLERANCE = new BigDecimal("0.0002");

    private final Long aiUserId;
    private final String currentSymbol;
    private final UserMapper userMapper;
    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantSignalDecisionMapper decisionMapper;
    private final CacheService cacheService;
    private final CircuitBreakerService circuitBreakerService;

    public AiTradingTools(Long aiUserId, String currentSymbol,
                          UserMapper userMapper,
                          FuturesTradingService futuresTradingService,
                          FuturesRiskService futuresRiskService,
                          FuturesPositionMapper futuresPositionMapper,
                          QuantForecastCycleMapper cycleMapper,
                          QuantSignalDecisionMapper decisionMapper,
                          CacheService cacheService,
                          CircuitBreakerService circuitBreakerService) {
        this.aiUserId = aiUserId;
        this.currentSymbol = currentSymbol;
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.futuresRiskService = futuresRiskService;
        this.futuresPositionMapper = futuresPositionMapper;
        this.cycleMapper = cycleMapper;
        this.decisionMapper = decisionMapper;
        this.cacheService = cacheService;
        this.circuitBreakerService = circuitBreakerService;
    }

    // ==================== 只读工具 ====================

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
        // 只读最新重周期（含 LLM），轻周期已通过 UPDATE 父重周期 forecast/signal 反映影响
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

    @Tool(description = "查询指定交易对的市场微观数据快照：恐贪指数、资金费率、爆仓压力、大户持仓、盘口失衡、多周期涨跌幅、市场状态、波动率等。用于辅助判断市场情绪和微观结构。")
    public String getMarketSnapshot(@ToolParam(description = "交易对，如BTCUSDT") String symbol) {
        if (symbol == null || symbol.isBlank()) symbol = currentSymbol;
        QuantForecastCycle cycle = cycleMapper.selectLatest(symbol);
        if (cycle == null || cycle.getSnapshotJson() == null) return "暂无市场快照数据";
        try {
            var snap = JSON.parseObject(cycle.getSnapshotJson());
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("snapshotTime", snap.getString("snapshotTime"));
            result.put("lastPrice", snap.get("lastPrice"));
            result.put("spotLastPrice", snap.get("spotLastPrice"));
            // 市场状态
            result.put("regime", snap.getString("regime"));
            result.put("regimeConfidence", snap.get("regimeConfidence"));
            result.put("regimeTransition", snap.getString("regimeTransition"));
            // 恐贪
            result.put("fearGreedIndex", snap.get("fearGreedIndex"));
            result.put("fearGreedLabel", snap.getString("fearGreedLabel"));
            // 资金费率
            result.put("fundingDeviation", snap.get("fundingDeviation"));
            result.put("fundingRateTrend", snap.get("fundingRateTrend"));
            result.put("fundingRateExtreme", snap.get("fundingRateExtreme"));
            // 爆仓
            result.put("liquidationPressure", snap.get("liquidationPressure"));
            result.put("liquidationVolumeUsdt", snap.get("liquidationVolumeUsdt"));
            // 大户/主动买卖
            result.put("topTraderBias", snap.get("topTraderBias"));
            result.put("takerBuySellPressure", snap.get("takerBuySellPressure"));
            // 盘口
            result.put("bidAskImbalance", snap.get("bidAskImbalance"));
            result.put("oiChangeRate", snap.get("oiChangeRate"));
            // 波动率
            result.put("bollSqueeze", snap.get("bollSqueeze"));
            result.put("atr5m", snap.get("atr5m"));
            // 期权IV
            result.put("dvolIndex", snap.get("dvolIndex"));
            result.put("atmIv", snap.get("atmIv"));
            // 多周期涨跌幅
            result.put("priceChanges", snap.get("priceChanges"));
            return JSON.toJSONString(result);
        } catch (Exception e) {
            log.warn("[AI-Trade] snapshotJson解析失败: {}", e.getMessage());
            return "快照数据解析失败";
        }
    }

    // ==================== 交易工具 ====================

    public String openPosition(String side, BigDecimal quantity, Integer leverage,
                               String orderType, BigDecimal limitPrice,
                               BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {
        return openPosition(side, quantity, leverage, orderType, limitPrice, stopLossPrice, takeProfitPrice, null);
    }

    @Override
    @Tool(description = "开仓下单，支持市价和限价。必须设1个止损和1个止盈，各覆盖全部仓位。杠杆5-50倍，单次保证金≥余额1%（最低100USDT）且不超余额35%，全局最多6个持仓，每个symbol最多3个持仓，同symbol开仓间隔≥10分钟，有持仓时不可开反向新仓。限价单会挂单等待成交。注意：交易对由系统自动绑定，无需指定。")
    public String openPosition(
            @ToolParam(description = "方向：LONG或SHORT") String side,
            @ToolParam(description = "数量（币的数量，如0.01个BTC）") BigDecimal quantity,
            @ToolParam(description = "杠杆倍数，5-50") Integer leverage,
            @ToolParam(description = "订单类型：MARKET(市价，立即成交) 或 LIMIT(限价，挂单等待)，默认MARKET") String orderType,
            @ToolParam(description = "限价价格，仅orderType=LIMIT时必填，市价单忽略此参数") BigDecimal limitPrice,
            @ToolParam(description = "止损价格，必填，覆盖全部仓位") BigDecimal stopLossPrice,
            @ToolParam(description = "止盈价格，必填，覆盖全部仓位") BigDecimal takeProfitPrice,
            @ToolParam(description = "策略标签，内部使用，LLM无需传递") String memo) {

        String symbol = currentSymbol;
        if (!"LONG".equals(side) && !"SHORT".equals(side)) {
            return "错误：side必须是LONG或SHORT";
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return "错误：quantity必须大于0";
        }
        if (leverage == null || leverage < MIN_LEVERAGE) {
            return "错误：杠杆最低" + MIN_LEVERAGE + "倍，你传了" + leverage + "，请用5-50倍";
        }
        if (leverage > MAX_LEVERAGE) {
            return "错误：杠杆上限" + MAX_LEVERAGE + "倍，你传了" + leverage;
        }
        if (stopLossPrice == null || stopLossPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "错误：必须设置止损价格";
        }
        if (takeProfitPrice == null || takeProfitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "错误：必须设置止盈价格";
        }

        boolean isLimit = "LIMIT".equalsIgnoreCase(orderType);
        if (isLimit && (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            return "错误：限价单必须设置limitPrice";
        }

        CircuitBreakerService.OpenDecision breaker = circuitBreakerService.allowOpen(aiUserId, memo);
        if (!breaker.allowed()) {
            log.warn("[AI-Trade] 开仓被熔断拦截 symbol={} memo={} reason={}", symbol, memo, breaker.reason());
            return "错误：熔断中，" + breaker.reason();
        }

        // 持仓数 + 同向检查（单次查询）
        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, aiUserId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        if (openPositions.size() >= MAX_OPEN_POSITIONS) {
            return "错误：已有" + openPositions.size() + "个持仓，上限" + MAX_OPEN_POSITIONS;
        }

        // ===== symbol 级别限制 =====
        List<FuturesPosition> symbolOpenPositions = openPositions.stream()
                .filter(p -> symbol.equals(p.getSymbol()))
                .toList();

        // 3. 有持仓时禁止开反向新仓
        if (!symbolOpenPositions.isEmpty()) {
            boolean hasOpposite = symbolOpenPositions.stream()
                    .anyMatch(p -> !side.equals(p.getSide()));
            if (hasOpposite) {
                String existingSide = symbolOpenPositions.getFirst().getSide();
                return "错误：" + symbol + "已有" + existingSide + "持仓，暂不允许开反向新仓，请先平仓";
            }
        }

        // 1. 每个 symbol 持仓最多 3 个
        if (symbolOpenPositions.size() >= MAX_SYMBOL_POSITIONS) {
            return "错误：" + symbol + "已有" + symbolOpenPositions.size() + "个持仓，上限" + MAX_SYMBOL_POSITIONS;
        }

        // 2. 每个 symbol 开仓间隔至少 10 分钟
        FuturesPosition lastPosition = futuresPositionMapper.selectOne(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, aiUserId)
                        .eq(FuturesPosition::getSymbol, symbol)
                        .orderByDesc(FuturesPosition::getCreatedAt)
                        .last("LIMIT 1"));
        if (lastPosition != null && lastPosition.getCreatedAt() != null) {
            long minutesSinceLast = Duration.between(lastPosition.getCreatedAt(), LocalDateTime.now()).toMinutes();
            if (minutesSinceLast < ENTRY_COOLDOWN_MINUTES) {
                return "错误：" + symbol + "距上次开仓仅" + minutesSinceLast + "分钟，需间隔" + ENTRY_COOLDOWN_MINUTES + "分钟";
            }
        }

        // 保证金比例检查
        User user = userMapper.selectById(aiUserId);
        if (user == null) return "错误：AI账户不存在";
        BigDecimal price = isLimit ? limitPrice : cacheService.getFuturesPrice(symbol);
        if (price == null) price = cacheService.getMarkPrice(symbol);
        if (price == null) return "错误：无法获取" + symbol + "价格";

        // 止损距离校验（按币种差异化）
        SymbolProfile profile = SymbolProfile.of(symbol);
        BigDecimal slMinPct = BigDecimal.valueOf(profile.slMinPct());
        BigDecimal slMaxPct = BigDecimal.valueOf(profile.slMaxPct());
        BigDecimal slDistance = stopLossPrice.subtract(price).abs();
        BigDecimal slPct = slDistance.divide(price, 6, RoundingMode.HALF_UP);
        // 2bps容差：执行器adjustForNoiseFloor已通过时，因cycle→tools间价格漂移/BigDecimal精度损失避免误拒
        BigDecimal slMinWithTolerance = slMinPct.subtract(SL_MIN_TOLERANCE);
        if (slPct.compareTo(slMinWithTolerance) < 0) {
            return "错误：止损距当前价仅" + slPct.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%，太近（噪音区，最低" + slMinPct.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%）";
        }
        if (slPct.compareTo(slMaxPct) > 0) {
            return "错误：止损距当前价" + slPct.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%，太远（>" + slMaxPct.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%），风险过大";
        }

        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        if (positionValue.compareTo(MIN_POSITION_VALUE) < 0) {
            return "错误：名义价值" + positionValue + " USDT太小，最低" + MIN_POSITION_VALUE + " USDT，请加大仓位";
        }
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        // 动态最低保证金：max(100, 余额×1%)
        BigDecimal dynamicMinMargin = user.getBalance().multiply(MIN_MARGIN_RATIO)
                .max(MIN_MARGIN_FLOOR).setScale(2, RoundingMode.HALF_UP);
        if (margin.compareTo(dynamicMinMargin) < 0 && user.getBalance().compareTo(dynamicMinMargin) >= 0) {
            return "错误：保证金" + margin + " USDT低于最低要求" + dynamicMinMargin + " USDT，请加大仓位或降低杠杆";
        }
        BigDecimal maxMargin = user.getBalance().multiply(MAX_POSITION_RATIO);
        if (margin.compareTo(maxMargin) > 0) {
            return "错误：保证金" + margin + "超过余额35%上限" + maxMargin.setScale(2, RoundingMode.HALF_UP);
        }

        // 构造请求
        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol(symbol);
        req.setSide(side);
        req.setQuantity(quantity);
        req.setLeverage(leverage);
        req.setOrderType(isLimit ? "LIMIT" : "MARKET");
        req.setMemo(memo);
        if (isLimit) req.setLimitPrice(limitPrice);

        // 止损：1个覆盖全部仓位
        FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
        sl.setPrice(stopLossPrice);
        sl.setQuantity(quantity);
        req.setStopLosses(List.of(sl));

        // 止盈：1个覆盖全部仓位
        FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
        tp.setPrice(takeProfitPrice);
        tp.setQuantity(quantity);
        req.setTakeProfits(List.of(tp));

        try {
            FuturesOrderResponse resp = futuresTradingService.openPosition(aiUserId, req);
            log.info("[AI-Trade] 开仓成功 {} {} {} qty={} lev={} sl={}", symbol, side, req.getOrderType(), quantity, leverage, stopLossPrice);
            return "开仓成功：" + JSON.toJSONString(resp);
        } catch (Exception e) {
            log.warn("[AI-Trade] 开仓失败 {} {} : {}", symbol, side, e.getMessage());
            return "开仓失败：" + e.getMessage();
        }
    }

    @Override
    @Tool(description = "市价平仓（全部或部分）")
    public String closePosition(
            @ToolParam(description = "仓位ID") Long positionId,
            @ToolParam(description = "平仓数量") BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：quantity必须大于0";

        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(positionId);
        req.setQuantity(quantity);
        req.setOrderType("MARKET");

        try {
            FuturesOrderResponse resp = futuresTradingService.closePosition(aiUserId, req);
            log.info("[AI-Trade] 平仓成功 posId={} qty={} pnl={}", positionId, quantity, resp.getRealizedPnl());
            return "平仓成功：" + JSON.toJSONString(resp);
        } catch (Exception e) {
            log.warn("[AI-Trade] 平仓失败 posId={} : {}", positionId, e.getMessage());
            return "平仓失败：" + e.getMessage();
        }
    }

    @Tool(description = "市价加仓")
    public String increasePosition(
            @ToolParam(description = "仓位ID") Long positionId,
            @ToolParam(description = "加仓数量") BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：quantity必须大于0";

        FuturesIncreaseRequest req = new FuturesIncreaseRequest();
        req.setPositionId(positionId);
        req.setQuantity(quantity);
        req.setOrderType("MARKET");

        try {
            FuturesOrderResponse resp = futuresTradingService.increasePosition(aiUserId, req);
            log.info("[AI-Trade] 加仓成功 posId={} qty={}", positionId, quantity);
            return "加仓成功：" + JSON.toJSONString(resp);
        } catch (Exception e) {
            log.warn("[AI-Trade] 加仓失败 posId={} : {}", positionId, e.getMessage());
            return "加仓失败：" + e.getMessage();
        }
    }

    @Tool(description = "追加保证金，降低强平风险")
    public String addMargin(
            @ToolParam(description = "仓位ID") Long positionId,
            @ToolParam(description = "追加金额(USDT)") BigDecimal amount) {
        if (positionId == null) return "错误：positionId不能为空";
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return "错误：amount必须大于0";

        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(positionId);
        req.setAmount(amount);

        try {
            futuresTradingService.addMargin(aiUserId, req);
            log.info("[AI-Trade] 追加保证金成功 posId={} amount={}", positionId, amount);
            return "追加保证金成功：positionId=" + positionId + " amount=" + amount;
        } catch (Exception e) {
            log.warn("[AI-Trade] 追加保证金失败 posId={} : {}", positionId, e.getMessage());
            return "追加保证金失败：" + e.getMessage();
        }
    }

    @Override
    @Tool(description = "修改已有持仓的止损价格，可根据行情动态调整（移动止损、保本止损等）")
    public String setStopLoss(
            @ToolParam(description = "仓位ID") Long positionId,
            @ToolParam(description = "新止损价格") BigDecimal stopLossPrice,
            @ToolParam(description = "止损数量") BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (stopLossPrice == null || stopLossPrice.compareTo(BigDecimal.ZERO) <= 0) return "错误：止损价格必须大于0";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：数量必须大于0";

        FuturesStopLossRequest req = new FuturesStopLossRequest();
        req.setPositionId(positionId);
        FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
        item.setPrice(stopLossPrice);
        item.setQuantity(quantity);
        req.setStopLosses(List.of(item));

        try {
            futuresRiskService.setStopLoss(aiUserId, req);
            log.info("[AI-Trade] 修改止损成功 posId={} sl={} qty={}", positionId, stopLossPrice, quantity);
            return "修改止损成功：positionId=" + positionId + " stopLoss=" + stopLossPrice;
        } catch (Exception e) {
            log.warn("[AI-Trade] 修改止损失败 posId={} : {}", positionId, e.getMessage());
            return "修改止损失败：" + e.getMessage();
        }
    }

    @Override
    @Tool(description = "修改已有持仓的止盈价格，可根据行情动态调整")
    public String setTakeProfit(
            @ToolParam(description = "仓位ID") Long positionId,
            @ToolParam(description = "新止盈价格") BigDecimal takeProfitPrice,
            @ToolParam(description = "止盈数量") BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (takeProfitPrice == null || takeProfitPrice.compareTo(BigDecimal.ZERO) <= 0) return "错误：止盈价格必须大于0";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：数量必须大于0";

        FuturesTakeProfitRequest req = new FuturesTakeProfitRequest();
        req.setPositionId(positionId);
        FuturesTakeProfitRequest.TakeProfitItem item = new FuturesTakeProfitRequest.TakeProfitItem();
        item.setPrice(takeProfitPrice);
        item.setQuantity(quantity);
        req.setTakeProfits(List.of(item));

        try {
            futuresRiskService.setTakeProfit(aiUserId, req);
            log.info("[AI-Trade] 修改止盈成功 posId={} tp={} qty={}", positionId, takeProfitPrice, quantity);
            return "修改止盈成功：positionId=" + positionId + " takeProfit=" + takeProfitPrice;
        } catch (Exception e) {
            log.warn("[AI-Trade] 修改止盈失败 posId={} : {}", positionId, e.getMessage());
            return "修改止盈失败：" + e.getMessage();
        }
    }

    @Tool(description = "撤销未成交的限价订单")
    public String cancelOrder(
            @ToolParam(description = "订单ID") Long orderId) {
        if (orderId == null) return "错误：orderId不能为空";

        try {
            FuturesOrderResponse resp = futuresTradingService.cancelOrder(aiUserId, orderId);
            log.info("[AI-Trade] 撤单成功 orderId={}", orderId);
            return "撤单成功：" + JSON.toJSONString(resp);
        } catch (Exception e) {
            log.warn("[AI-Trade] 撤单失败 orderId={} : {}", orderId, e.getMessage());
            return "撤单失败：" + e.getMessage();
        }
    }
}
