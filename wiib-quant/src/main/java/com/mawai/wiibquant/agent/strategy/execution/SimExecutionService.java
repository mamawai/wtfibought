package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.PositionSizer;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * MARKET 信号 FLAT→POSITION 一步到位（sim 市价单同步成交）；STOP 信号 FLAT→ARMED（quant 侧虚拟
 * 触价单，见 {@link ExecutionPriceConsumer}），实时价穿越触发价→市价进场→POSITION。撮合/SL/TP/强平
 * 全在 sim 既有链路由 feed 价格驱动——决策价与撮合价同源，quant 只下指令和轮询状态，quant 挂掉
 * 保护单照常生效（例外：ARMED 是 quant 内存态，quant 挂掉触价单失效，重启后策略 reaffirm 自愈）。</p>
 *
 * <p>与 testnet 版的结构性差异：
 * ① SL/TP 随开仓单一并提交、成交自动转入仓位——无"成交后补挂保护单失败裸奔"缺口；
 * ② 仓位定量=风险定量（riskPerTradePct×权益/止损距离，15% 保证金上限，杠杆钳到策略 maxLeverage），
 *    与回测引擎同公式——回测口径即实盘口径；
 * ③ 重启对账：首次触达某 strategyId:symbol 时查 sim——有持仓收养为 POSITION、残留挂单一律撤
 *    （策略腿状态重启后也是新的，等新信号重挂即可）；
 * ④ 每策略独立量化账户 quant-&lt;strategyId&gt;（{@link StrategyAccountRegistry} 启动预建+幂等），
 *    盈亏归因互不污染。</p>
 *
 * <p>已知口径差：sim 无 GTX/post-only，挂单价已被穿越时按 taker 费成交而非拒单。
 * 持仓期钩子 onPositionBarClosed 已接（LiqFade 时间出场）；平仓归因 reason 只落 quant 日志，
 * sim 侧 FuturesCloseRequest 无此字段不扩。</p>
 */
@Slf4j
@Component
public class SimExecutionService implements StrategyExecutionPort {

    private static final long BAR_MILLIS = 300_000L; // 5m
    private static final String OPEN_STATUS = "OPEN";

    private enum State { FLAT, ARMED, WORKING, POSITION }   // ARMED=quant侧虚拟触价单(STOP)，不落sim挂单

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
    private final StrategyAccountRegistry accounts;
    private final ConcurrentMap<String, ExecState> states = new ConcurrentHashMap<>();

    @Value("${strategy.execution.enabled:false}")
    boolean enabled;
    @Value("${strategy.execution.symbols:ETHUSDT}")
    String symbolsCsv;
    /** sim 轨请求杠杆；实际下单 = min(此值, 各策略 riskPolicy.maxLeverage)，风险定量下只影响保证金占用与强平距离 */
    @Value("${strategy.execution.sim.leverage:20}")
    int leverage;
    @Value("${strategy.execution.order-timeout-bars:12}")
    int orderTimeoutBars;

    public SimExecutionService(SimTradeClient client, StrategyAccountRegistry accounts) {
        this.client = client;
        this.accounts = accounts;
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
                    case ARMED -> {
                        if ("STOP".equals(signal.orderType()) && legKey(signal).equals(st.legKey)) {
                            st.signal = signal;                            // 同腿 reaffirm：刷新SL(随ATR漂移)
                            st.strategy = strategy;
                            st.placedBarCloseTime = signal.barCloseTime();
                        } else {
                            st.reset();                                    // 换腿/换单型：虚拟单直接弃，重下
                            place(symbol, signal, strategy, st);
                        }
                    }
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
            if (st.state == State.ARMED) {
                st.reset();   // 虚拟触价单直接撤，无交易所交互
            } else if (st.state == State.WORKING && cancelQuiet(strategyId, st)) {
                st.reset();   // 撤失败(可能已成交)保持 WORKING，下一 tick 定夺
            }
        }
    }

    /**
     * 实时价 tick：ARMED 的虚拟触价单在价格穿越触发价时立即市价进场（复用 MARKET 通路，
     * 数量在触发时按当时权益定量，SL/TP 随单）。电平式判断——只要价在触发价之外任一 tick 即触发，
     * Pub/Sub 偶发丢 tick 不影响持续性突破。与 5m 收盘线程并发，同一把 st 锁保证不双开。
     */
    @Override
    public void onPriceTick(String symbol, BigDecimal price) {
        if (!active(symbol) || price == null || price.signum() <= 0) return;
        String suffix = ":" + symbol;
        for (Map.Entry<String, ExecState> entry : states.entrySet()) {
            if (!entry.getKey().endsWith(suffix)) continue;
            ExecState st = entry.getValue();
            if (st.state != State.ARMED) continue;   // 无锁粗筛，命中再加锁复核
            synchronized (st) {
                if (st.state != State.ARMED) continue;
                StrategySignal s = st.signal;
                boolean crossed = s.isLong()
                        ? price.compareTo(s.entryRefPrice()) >= 0
                        : price.compareTo(s.entryRefPrice()) <= 0;
                if (!crossed) continue;
                try {
                    log.info("[SimExec] 触价触发 {}:{} {} trigger={} tick={}", s.strategyId(), symbol,
                            s.side(), s.entryRefPrice().toPlainString(), price.toPlainString());
                    TradingStrategySpi strategy = st.strategy;
                    // 以 MARKET 形态复用 place：开仓失败(余额不足等)state留FLAT，下一根5m reaffirm重新arm
                    StrategySignal marketSig = new StrategySignal(s.strategyId(), s.symbol(), s.side(),
                            s.isLong(), s.entryRefPrice(), s.stopLossPrice(), s.takeProfitPrice(),
                            s.score(), s.reason(), s.barCloseTime(), "MARKET");
                    st.reset();
                    place(symbol, marketSig, strategy, st);
                } catch (Exception e) {
                    log.warn("[SimExec] 触价开仓异常 {}:{} msg={}", s.strategyId(), symbol, e.toString());
                }
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

    /**
     * 持仓期钩子：strategy 实例必须由 runtime 传入——对账收养的持仓 st.strategy 为 null，不能依赖。
     * 钩子退出后立刻复核仓位状态：时间出场平掉的仓当根即回 FLAT，同 bar 新信号可进场（对齐回测语义）。
     */
    @Override
    public void onPositionBarClosed(String symbol, TradingStrategySpi strategy, StrategyMarketView view) {
        if (!active(symbol)) return;
        ExecState st = states.computeIfAbsent(key(strategy.id(), symbol), k -> new ExecState());
        synchronized (st) {
            try {
                recoverIfNeeded(st, strategy.id(), symbol);
                if (st.state != State.POSITION) return;
                Long userId = account(strategy.id());
                client.getPositions(userId, symbol).stream()
                        .filter(p -> Objects.equals(p.getId(), st.positionId) && OPEN_STATUS.equals(p.getStatus()))
                        .findFirst()
                        .ifPresent(p -> {
                            // sim 的 createdAt 是本机时区钟面，策略按 UTC 解释（回测口径）——不转持仓时长差8h，时间出场报废
                            p.setCreatedAt(p.getCreatedAt().atZone(ZoneId.systemDefault())
                                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
                            strategy.onPositionBarClosed(symbol, p, view, hookOps(userId));
                        });
                checkPosition(strategy.id(), symbol, st);
            } catch (Exception e) {
                log.warn("[SimExec] onPositionBarClosed 异常 {}:{} msg={}", strategy.id(), symbol, e.toString());
            }
        }
    }

    /** 持仓钩子专用交易工具：只支持市价平仓（时间出场）；sim internal API 无改保护单端点，误用即抛快速暴露。 */
    private TradingOperations hookOps(Long userId) {
        return new TradingOperations() {
            @Override
            public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                       BigDecimal limitPrice, BigDecimal stopLossPrice,
                                       BigDecimal takeProfitPrice, String memo) {
                throw new UnsupportedOperationException("持仓钩子不支持开仓");
            }

            @Override
            public String closePosition(Long positionId, BigDecimal quantity) {
                FuturesCloseRequest req = new FuturesCloseRequest();
                req.setPositionId(positionId);
                req.setQuantity(quantity);
                req.setOrderType("MARKET");
                client.closePosition(userId, req);
                return "平仓成功";
            }

            @Override
            public String closePositionWithReason(Long positionId, BigDecimal quantity, String reason) {
                log.info("[SimExec] 钩子市价平仓 posId={} reason={}", positionId, reason);
                return closePosition(positionId, quantity);
            }

            @Override
            public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
                throw new UnsupportedOperationException("sim internal API 无改止损端点");
            }

            @Override
            public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
                throw new UnsupportedOperationException("sim internal API 无改止盈端点");
            }
        };
    }

    // ==================== 下单 ====================

    private void place(String symbol, StrategySignal signal, TradingStrategySpi strategy, ExecState st) {
        // STOP=虚拟触价单：只在 quant 内存持状态，价格 tick 穿越触发价才真正下市价单（onPriceTick）
        if ("STOP".equals(signal.orderType())) {
            st.state = State.ARMED;
            st.strategy = strategy;
            st.signal = signal;
            st.legKey = legKey(signal);
            st.placedBarCloseTime = signal.barCloseTime();
            log.debug("[SimExec] 虚拟触价单 armed {}:{} {} trigger={}", signal.strategyId(), symbol,
                    signal.side(), signal.entryRefPrice().toPlainString());
            return;
        }
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
        // 显式逐仓：sim 端缺省已改为全仓(CROSS)，机器人保持逐仓的独立风险隔离不受影响
        req.setMarginMode("ISOLATED");
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

    /** 风险定量：与回测引擎共用 PositionSizer 单一公式（含交易过滤器落地对齐）。 */
    private BigDecimal riskSizedQty(StrategySignal signal, TradingStrategySpi strategy, BigDecimal equity) {
        return PositionSizer.riskSizedQty(equity, signal.entryRefPrice(), signal.stopLossPrice(),
                policy(strategy), effectiveLeverage(strategy), TradeFilterDefaults.futures(signal.symbol()));
    }

    private int effectiveLeverage(TradingStrategySpi strategy) {
        return PositionSizer.effectiveLeverage(leverage, policy(strategy));
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

    private Long account(String strategyId) {
        return accounts.userId(strategyId);
    }

    private static String key(String strategyId, String symbol) {
        return strategyId + ":" + symbol;
    }

    /**
     * 腿指纹：方向+挂单价，据此判 reaffirm 还是换腿。刻意不含 SL——SL 带 ATR 缓冲每根 bar 尾数微漂，
     * 含入会把同价挂单每根 bar 撤了重挂（真盘丢排队位）；真换腿挂单价必变，语义不丢。
     * SL/TP 随挂单时刻值固定，滞留被超时撤单封顶，重挂自然刷新。
     */
    private static String legKey(StrategySignal s) {
        return s.side() + ":" + s.entryRefPrice().stripTrailingZeros().toPlainString();
    }
}
