package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 策略信号 → 本平台模拟盘执行（strategy.execution.target=sim 时由 ExecutionRoutingConfig 选用）。
 *
 * <p>状态机每 strategyId:symbol 一份：FLAT →(LIMIT 挂单)WORKING →(成交)POSITION →(sim 侧平仓)FLAT；
 * MARKET 信号 FLAT→POSITION 一步到位（sim 市价单同步成交）。撮合/SL/TP/强平全在 sim 既有链路
 * 由 feed 价格驱动——决策价与撮合价同源，quant 只下指令和轮询状态，quant 挂掉保护单照常生效。</p>
 *
 * <p>与 testnet 版的结构性差异：
 * ① SL/TP 随开仓单一并提交、成交自动转入仓位——无"成交后补挂保护单失败裸奔"缺口；
 * ② 仓位定量=风险定量（riskPerTradePct×权益/止损距离，15% 保证金上限，杠杆钳到策略 maxLeverage），
 *    与回测引擎同公式——回测口径即实盘口径；
 * ③ 重启对账：首次触达某 strategyId:symbol 时查 sim——有持仓收养为 POSITION、残留挂单一律撤
 *    （策略腿状态重启后也是新的，等新信号重挂即可）；
 * ④ 每策略独立量化账户 quant-&lt;strategyId&gt;（ensure-account 幂等创建），盈亏归因互不污染。</p>
 *
 * <p>已知口径差：sim 无 GTX/post-only，挂单价已被穿越时按 taker 费成交而非拒单。
 * LiqFade 时间出场待二期（feed premium/taker 实时化后随策略一起接）。</p>
 */
@Slf4j
@Component
public class SimExecutionService implements StrategyExecutionPort {

    private static final long BAR_MILLIS = 300_000L; // 5m
    private static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.15");   // 与回测引擎一致
    private static final String OPEN_STATUS = "OPEN";

    private enum State { FLAT, WORKING, POSITION }

    /** 单 strategyId:symbol 执行状态；所有读写都在 synchronized(st) 下。 */
    private static final class ExecState {
        State state = State.FLAT;
        TradingStrategySpi strategy;
        StrategySignal signal;
        String legKey;               // 腿指纹：换腿才撤旧挂新，同腿 reaffirm 不动
        Long orderId;
        long placedBarCloseTime;     // 挂单所在 bar 收盘时刻，算超时
        Long positionId;
        boolean recovered;           // 重启对账每 key 只做一次（成功才置位，失败下次重试）

        void reset() {
            state = State.FLAT;
            strategy = null;
            signal = null;
            legKey = null;
            orderId = null;
            placedBarCloseTime = 0L;
            positionId = null;
        }
    }

    private final SimTradeClient client;
    private final ConcurrentMap<String, ExecState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> accountByStrategy = new ConcurrentHashMap<>();

    @Value("${strategy.execution.enabled:false}")
    boolean enabled;
    @Value("${strategy.execution.symbols:ETHUSDT}")
    String symbolsCsv;
    @Value("${strategy.execution.sim.initial-balance:10000}")
    BigDecimal initialBalance;
    @Value("${strategy.execution.sim.leverage:5}")
    int leverage;
    @Value("${strategy.execution.order-timeout-bars:12}")
    int orderTimeoutBars;

    public SimExecutionService(SimTradeClient client) {
        this.client = client;
    }

    private boolean active(String symbol) {
        return enabled && symbolSet().contains(symbol);
    }

    private Set<String> symbolSet() {
        if (symbolsCsv == null || symbolsCsv.isBlank()) return Set.of();
        return Arrays.stream(symbolsCsv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void onSignal(String symbol, StrategySignal signal, TradingStrategySpi strategy) {
        if (!active(symbol) || signal == null) return;
        ExecState st = states.computeIfAbsent(key(signal.strategyId(), symbol), k -> new ExecState());
        synchronized (st) {
            try {
                recoverIfNeeded(st, signal.strategyId(), symbol);
                switch (st.state) {
                    case FLAT -> place(symbol, signal, strategy, st);
                    case WORKING -> {
                        if (!"LIMIT".equals(signal.orderType())) return;   // WORKING 只可能是 LIMIT 范式
                        if (!legKey(signal).equals(st.legKey) && cancelQuiet(signal.strategyId(), st)) {
                            st.reset();                                    // 换腿：撤旧成功才挂新，防双单
                            place(symbol, signal, strategy, st);
                        }
                    }
                    case POSITION -> { /* 一次一仓，持仓中忽略新信号 */ }
                }
            } catch (Exception e) {
                log.warn("[SimExec] onSignal 异常 {}:{} msg={}", signal.strategyId(), symbol, e.toString());
            }
        }
    }

    @Override
    public void noSignal(String symbol, String strategyId) {
        ExecState st = states.get(key(strategyId, symbol));
        if (st == null) return;
        synchronized (st) {
            if (st.state == State.WORKING && cancelQuiet(strategyId, st)) {
                st.reset();   // 撤失败(可能已成交)保持 WORKING，下一 tick 定夺
            }
        }
    }

    @Override
    public void tick(String symbol, long nowMs) {
        if (!active(symbol)) return;
        String suffix = ":" + symbol;
        for (Map.Entry<String, ExecState> entry : states.entrySet()) {
            if (!entry.getKey().endsWith(suffix)) continue;
            String strategyId = entry.getKey().substring(0, entry.getKey().length() - suffix.length());
            ExecState st = entry.getValue();
            synchronized (st) {
                try {
                    recoverIfNeeded(st, strategyId, symbol);
                    if (st.state == State.WORKING) checkWorking(strategyId, symbol, nowMs, st);
                    else if (st.state == State.POSITION) checkPosition(strategyId, symbol, st);
                } catch (Exception e) {
                    log.warn("[SimExec] tick 异常 {}:{} state={} msg={}", strategyId, symbol, st.state, e.toString());
                }
            }
        }
    }

    // ==================== 下单 ====================

    private void place(String symbol, StrategySignal signal, TradingStrategySpi strategy, ExecState st) {
        Long userId = account(signal.strategyId());
        BigDecimal equity = client.getBalance(userId);   // 进场只在 FLAT，余额≈权益
        BigDecimal qty = riskSizedQty(signal, strategy, equity);
        if (qty.signum() <= 0) {
            log.warn("[SimExec] 数量为0跳过 {}:{} equity={}", signal.strategyId(), symbol, equity);
            return;
        }

        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol(symbol);
        req.setSide(signal.side());
        req.setQuantity(qty);
        req.setLeverage(effectiveLeverage(strategy));
        req.setMemo(signal.strategyId());
        // SL/TP 随开仓单提交(整仓单档)，成交由 sim 自动转入仓位并由 feed 价格触发
        if (signal.stopLossPrice() != null) {
            FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
            sl.setPrice(signal.stopLossPrice());
            sl.setQuantity(qty);
            req.setStopLosses(List.of(sl));
        }
        if (signal.takeProfitPrice() != null) {
            FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
            tp.setPrice(signal.takeProfitPrice());
            tp.setQuantity(qty);
            req.setTakeProfits(List.of(tp));
        }

        if ("LIMIT".equals(signal.orderType())) {
            req.setOrderType("LIMIT");
            req.setLimitPrice(signal.entryRefPrice());
            FuturesOrderResponse resp = client.openPosition(userId, req);
            st.state = State.WORKING;
            st.strategy = strategy;
            st.signal = signal;
            st.legKey = legKey(signal);
            st.orderId = resp.getOrderId();
            st.placedBarCloseTime = signal.barCloseTime();
            log.info("[SimExec] 挂LIMIT {}:{} {} qty={} @ {} orderId={}", signal.strategyId(), symbol,
                    signal.side(), qty, signal.entryRefPrice(), resp.getOrderId());
        } else {
            // MARKET(含策略侧其他即时单型)：sim 市价单同步成交，直接进 POSITION
            req.setOrderType("MARKET");
            FuturesOrderResponse resp = client.openPosition(userId, req);
            st.state = State.POSITION;
            st.strategy = strategy;
            st.signal = signal;
            st.positionId = resp.getPositionId();
            notifyOpened(symbol, st, resp.getFilledPrice() != null ? resp.getFilledPrice() : signal.entryRefPrice());
            log.info("[SimExec] 市价开仓 {}:{} {} qty={} fill={} posId={}", signal.strategyId(), symbol,
                    signal.side(), qty, resp.getFilledPrice(), resp.getPositionId());
        }
    }

    /** 风险定量（与回测引擎同公式）：qty=权益×每笔风险%/止损距离，保证金钳 15% 权益。 */
    private BigDecimal riskSizedQty(StrategySignal signal, TradingStrategySpi strategy, BigDecimal equity) {
        BigDecimal entry = signal.entryRefPrice();
        BigDecimal sl = signal.stopLossPrice();
        if (entry == null || entry.signum() <= 0 || sl == null || equity == null || equity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskDistance = entry.subtract(sl).abs();
        if (riskDistance.signum() <= 0) return BigDecimal.ZERO;
        StrategyRiskPolicy policy = policy(strategy);
        BigDecimal riskPct = BigDecimal.valueOf(policy.riskPerTradePct() > 0
                ? policy.riskPerTradePct()
                : StrategyRiskPolicy.defaults().riskPerTradePct());
        BigDecimal qty = equity.multiply(riskPct).divide(riskDistance, 8, RoundingMode.DOWN);
        int lev = effectiveLeverage(strategy);
        BigDecimal margin = qty.multiply(entry).divide(BigDecimal.valueOf(lev), 8, RoundingMode.CEILING);
        BigDecimal maxMargin = equity.multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            qty = maxMargin.multiply(BigDecimal.valueOf(lev)).divide(entry, 8, RoundingMode.DOWN);
        }
        return qty;
    }

    private int effectiveLeverage(TradingStrategySpi strategy) {
        return Math.max(1, Math.min(leverage, Math.max(1, policy(strategy).maxLeverage())));
    }

    private StrategyRiskPolicy policy(TradingStrategySpi strategy) {
        StrategyRiskPolicy p = strategy == null ? null : strategy.riskPolicy();
        return p == null ? StrategyRiskPolicy.defaults() : p;
    }

    // ==================== 轮询 ====================

    private void checkWorking(String strategyId, String symbol, long nowMs, ExecState st) {
        FuturesOrderResponse order = client.getOrder(account(strategyId), st.orderId);
        String status = order.getStatus();
        switch (status) {
            case "FILLED" -> {
                st.positionId = order.getPositionId();
                st.state = State.POSITION;
                notifyOpened(symbol, st, order.getFilledPrice() != null
                        ? order.getFilledPrice() : st.signal.entryRefPrice());
                log.info("[SimExec] 限价成交 {}:{} posId={} fill={}", strategyId, symbol,
                        order.getPositionId(), order.getFilledPrice());
            }
            case "CANCELLED", "EXPIRED" -> {
                log.info("[SimExec] LIMIT 终态={} {}:{} 回FLAT", status, strategyId, symbol);
                st.reset();
            }
            default -> {   // PENDING / TRIGGERED / PROCESSING：超时则撤
                long barsHeld = (nowMs - st.placedBarCloseTime) / BAR_MILLIS;
                if (barsHeld >= orderTimeoutBars && cancelQuiet(strategyId, st)) {
                    // 撤单与成交竞态：已成交的撤单被 sim CAS 拒掉→保持 WORKING，下一 tick 看到 FILLED
                    log.info("[SimExec] LIMIT 超时({}根)撤单 {}:{}", barsHeld, strategyId, symbol);
                    st.reset();
                }
            }
        }
    }

    private void checkPosition(String strategyId, String symbol, ExecState st) {
        boolean stillOpen = client.getPositions(account(strategyId), symbol).stream()
                .anyMatch(p -> Objects.equals(p.getId(), st.positionId) && OPEN_STATUS.equals(p.getStatus()));
        if (!stillOpen) {
            log.info("[SimExec] 仓位已平 {}:{} posId={} 回FLAT", strategyId, symbol, st.positionId);
            st.reset();
        }
    }

    // ==================== 对账与工具 ====================

    /** 重启对账（每 key 一次，全部成功才置位）：有持仓收养为 POSITION；残留挂单一律撤掉。 */
    private void recoverIfNeeded(ExecState st, String strategyId, String symbol) {
        if (st.recovered) return;
        Long userId = account(strategyId);
        for (FuturesOrderResponse order : client.getPendingOrders(userId, symbol)) {
            client.cancelOrder(userId, order.getOrderId());
            log.info("[SimExec] 对账撤残留挂单 {}:{} orderId={}", strategyId, symbol, order.getOrderId());
        }
        client.getPositions(userId, symbol).stream()
                .filter(p -> OPEN_STATUS.equals(p.getStatus()))
                .findFirst()
                .ifPresent(p -> {
                    st.state = State.POSITION;
                    st.positionId = p.getId();
                    log.info("[SimExec] 对账收养持仓 {}:{} posId={}", strategyId, symbol, p.getId());
                });
        st.recovered = true;
    }

    private void notifyOpened(String symbol, ExecState st, BigDecimal fillPrice) {
        try {
            st.strategy.onPositionOpened(symbol, st.signal, st.positionId, fillPrice, null, null);
        } catch (Exception e) {
            log.warn("[SimExec] onPositionOpened 异常 symbol={} msg={}", symbol, e.toString());
        }
    }

    /** 撤单；true=撤成功。失败（多半已成交被 CAS 拒）返回 false，调用方保持原状态等下一 tick 定夺。 */
    private boolean cancelQuiet(String strategyId, ExecState st) {
        if (st.orderId == null) return true;
        try {
            client.cancelOrder(account(strategyId), st.orderId);
            log.info("[SimExec] 撤LIMIT {} orderId={}", strategyId, st.orderId);
            return true;
        } catch (Exception e) {
            log.warn("[SimExec] 撤单失败(可能已成交) {} orderId={} msg={}", strategyId, st.orderId, e.toString());
            return false;
        }
    }

    /** 每策略独立量化账户 quant-&lt;ID&gt;，首次取用时幂等创建并缓存 userId（失败不缓存，下次重试）。 */
    private Long account(String strategyId) {
        return accountByStrategy.computeIfAbsent(strategyId,
                id -> client.ensureAccount("quant-" + id, initialBalance));
    }

    private static String key(String strategyId, String symbol) {
        return strategyId + ":" + symbol;
    }

    /** 腿指纹：方向+挂单价+止损价（同腿三者不变，据此判 reaffirm 还是换腿）。 */
    private static String legKey(StrategySignal s) {
        return s.side() + ":" + s.entryRefPrice().stripTrailingZeros().toPlainString()
                + ":" + s.stopLossPrice().stripTrailingZeros().toPlainString();
    }
}
