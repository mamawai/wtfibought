package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetClient;
import com.mawai.wiibquant.agent.binance.model.OrderResponse;
import com.mawai.wiibquant.agent.binance.model.PlaceOrderRequest;
import com.mawai.wiibquant.agent.binance.model.PositionRisk;
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
 * Fibo 信号 → Binance Testnet 实盘执行（P0：单币最小闭环）。
 *
 * <p>每 symbol 一个订单状态机：FLAT(无单) → WORKING(LIMIT 挂着等回踩) → POSITION(已成交、SL+TP 挂着) → 回 FLAT。
 * 进场 LIMIT 用 GTX(POST_ONLY) 强制 maker，复刻回测"挂回撤位被动成交"；成交后挂 SL(止损价) + TP(0.4R 触发价)
 * 两个 closePosition 条件单，任一触发平仓后撤掉另一个。腿失效(无信号)或挂单超时则撤单。</p>
 *
 * <p>P0 用轮询(tick 由每根 5m 收盘驱动)查成交/平仓；不做重启对账(重启前需手动清 testnet 残留)。
 * 默认关闭(strategy.execution.enabled=false)，且只接 testnet、白名单内 symbol。</p>
 */
@Slf4j
@Component
public class TestnetExecutionService implements StrategyExecutionPort {

    private static final long BAR_MILLIS = 300_000L; // 5m

    /** testnet 下单精度（P0 硬编码 BTC/ETH；后续可换 exchangeInfo 拉取）。 */
    private record SymbolSpec(BigDecimal qtyStep, int pricePrecision) {}

    private static final Map<String, SymbolSpec> SPECS = Map.of(
            "ETHUSDT", new SymbolSpec(new BigDecimal("0.001"), 2),
            "BTCUSDT", new SymbolSpec(new BigDecimal("0.001"), 1));

    private enum State { FLAT, WORKING, POSITION }

    /** 单 symbol 的执行状态；所有读写都在 synchronized(st) 下。 */
    private static final class ExecState {
        State state = State.FLAT;
        TradingStrategySpi strategy;
        StrategySignal signal;
        String legKey;               // 腿指纹：换腿才撤旧挂新，同腿 reaffirm 不动
        Long entryOrderId;
        long placedBarCloseTime;     // 挂单所在 bar 收盘时刻，算超时
        BigDecimal filledEntryPrice;

        void reset() {
            state = State.FLAT;
            strategy = null;
            signal = null;
            legKey = null;
            entryOrderId = null;
            placedBarCloseTime = 0L;
            filledEntryPrice = null;
        }
    }

    private final BinanceFuturesTestnetClient client;
    private final ConcurrentMap<String, ExecState> states = new ConcurrentHashMap<>();

    @Value("${strategy.execution.enabled:false}")
    boolean enabled;
    @Value("${strategy.execution.symbols:ETHUSDT}")
    String symbolsCsv;
    @Value("${strategy.execution.fixed-margin-usdt:50}")
    double fixedMarginUsdt;
    @Value("${strategy.execution.leverage:20}")
    int leverage;
    @Value("${strategy.execution.order-timeout-bars:12}")
    int orderTimeoutBars;

    public TestnetExecutionService(BinanceFuturesTestnetClient client) {
        this.client = client;
    }

    private boolean active(String symbol) {
        return enabled && SPECS.containsKey(symbol) && symbolSet().contains(symbol);
    }

    private Set<String> symbolSet() {
        if (symbolsCsv == null || symbolsCsv.isBlank()) return Set.of();
        return Arrays.stream(symbolsCsv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 新信号：FLAT→挂 LIMIT；WORKING→换腿撤旧挂新(同腿 reaffirm 不动)；POSITION→忽略(一次一仓)。 */
    public void onSignal(String symbol, StrategySignal signal, TradingStrategySpi strategy) {
        if (!active(symbol) || signal == null || !"LIMIT".equals(signal.orderType())) return;
        ExecState st = states.computeIfAbsent(symbol, k -> new ExecState());
        synchronized (st) {
            switch (st.state) {
                case FLAT -> placeEntry(symbol, signal, strategy, st);
                case WORKING -> {
                    if (!legKey(signal).equals(st.legKey)) {  // 换腿：撤旧挂新
                        cancelEntry(symbol, st);
                        placeEntry(symbol, signal, strategy, st);
                    }
                }
                case POSITION -> { /* 持仓中，忽略新信号 */ }
            }
        }
    }

    /** 端口适配：testnet P0 单策略，状态按 symbol 键，strategyId 不参与。 */
    @Override
    public void noSignal(String symbol, String strategyId) {
        noSignal(symbol);
    }

    /** 无信号=腿失效：WORKING 撤挂单回 FLAT。 */
    public void noSignal(String symbol) {
        ExecState st = states.get(symbol);
        if (st == null) return;
        synchronized (st) {
            if (st.state == State.WORKING) {
                cancelEntry(symbol, st);
                st.reset();
            }
        }
    }

    /** 每根 5m 收盘驱动：WORKING 查成交/超时；POSITION 查平仓。nowMs=当前 bar 收盘时刻。 */
    public void tick(String symbol, long nowMs) {
        ExecState st = states.get(symbol);
        if (st == null || !active(symbol)) return;
        synchronized (st) {
            try {
                if (st.state == State.WORKING) checkWorking(symbol, nowMs, st);
                else if (st.state == State.POSITION) checkPosition(symbol, st);
            } catch (Exception e) {
                log.warn("[TestnetExec] tick 异常 symbol={} state={} msg={}", symbol, st.state, e.toString());
            }
        }
    }

    private void placeEntry(String symbol, StrategySignal signal, TradingStrategySpi strategy, ExecState st) {
        SymbolSpec spec = SPECS.get(symbol);
        BigDecimal price = signal.entryRefPrice().setScale(spec.pricePrecision(), RoundingMode.HALF_UP);
        BigDecimal qty = sizeQuantity(spec, price);
        if (qty.signum() <= 0) {
            log.warn("[TestnetExec] 数量为0跳过 symbol={} price={} margin={} lev={}", symbol, price, fixedMarginUsdt, leverage);
            return;
        }
        try {
            client.setLeverage(symbol, leverage);
            PlaceOrderRequest req = PlaceOrderRequest.builder()
                    .symbol(symbol)
                    .side(signal.isLong() ? "BUY" : "SELL")
                    .type("LIMIT")
                    .timeInForce("GTX")            // POST_ONLY：会立即成交则拒，强制 maker
                    .price(price)
                    .quantity(qty)
                    .newClientOrderId("FIBO-" + symbol + "-" + signal.barCloseTime())
                    .newOrderRespType("RESULT")
                    .build();
            OrderResponse resp = client.placeOrder(req);
            if ("EXPIRED".equals(resp.getStatus()) || "REJECTED".equals(resp.getStatus())) {
                log.info("[TestnetExec] LIMIT 被拒(GTX 即成交) status={} symbol={}", resp.getStatus(), symbol);
                return;  // 保持 FLAT
            }
            st.state = State.WORKING;
            st.strategy = strategy;
            st.signal = signal;
            st.legKey = legKey(signal);
            st.entryOrderId = resp.getOrderId();
            st.placedBarCloseTime = signal.barCloseTime();
            log.info("[TestnetExec] 挂LIMIT symbol={} {} qty={} @ {} orderId={}",
                    symbol, req.getSide(), qty, price, resp.getOrderId());
        } catch (Exception e) {
            log.warn("[TestnetExec] 挂单失败 symbol={} msg={}", symbol, e.toString());  // 保持 FLAT，下根重试
        }
    }

    private void cancelEntry(String symbol, ExecState st) {
        if (st.entryOrderId == null) return;
        try {
            client.cancelOrder(symbol, st.entryOrderId, null);
            log.info("[TestnetExec] 撤LIMIT symbol={} orderId={}", symbol, st.entryOrderId);
        } catch (Exception e) {
            log.warn("[TestnetExec] 撤单失败 symbol={} orderId={} msg={}", symbol, st.entryOrderId, e.toString());
        }
    }

    private void checkWorking(String symbol, long nowMs, ExecState st) {
        OrderResponse order = client.queryOrder(symbol, st.entryOrderId, null);
        String status = order.getStatus();
        if ("FILLED".equals(status)) {
            onFilled(symbol, order, st);
        } else if ("CANCELED".equals(status) || "EXPIRED".equals(status) || "REJECTED".equals(status)) {
            log.info("[TestnetExec] LIMIT 终态={} symbol={} 回 FLAT", status, symbol);
            st.reset();
        } else {  // NEW / PARTIALLY_FILLED：超时则撤
            long barsHeld = (nowMs - st.placedBarCloseTime) / BAR_MILLIS;
            if (barsHeld >= orderTimeoutBars) {
                log.info("[TestnetExec] LIMIT 超时({}根)撤单 symbol={}", barsHeld, symbol);
                cancelEntry(symbol, st);
                st.reset();
            }
        }
    }

    /** 成交建仓：通知策略消费当前腿(一腿只交易一次)，挂 SL + TP 两个 closePosition 条件单，转 POSITION。 */
    private void onFilled(String symbol, OrderResponse entryOrder, ExecState st) {
        SymbolSpec spec = SPECS.get(symbol);
        BigDecimal fillPrice = entryOrder.getAvgPrice() != null && entryOrder.getAvgPrice().signum() > 0
                ? entryOrder.getAvgPrice()
                : st.signal.entryRefPrice();
        st.filledEntryPrice = fillPrice;
        String exitSide = st.signal.isLong() ? "SELL" : "BUY";

        // 策略侧登记开仓事实(fibo 消费当前腿)；执行层直管 SL/TP，不经 TradingOperations
        try {
            st.strategy.onPositionOpened(symbol, st.signal, st.entryOrderId, fillPrice, null, null);
        } catch (Exception e) {
            log.warn("[TestnetExec] onPositionOpened 异常 symbol={} msg={}", symbol, e.toString());
        }

        placeCloseConditional(symbol, exitSide, "STOP_MARKET",
                st.signal.stopLossPrice().setScale(spec.pricePrecision(), RoundingMode.HALF_UP), st, "SL");
        BigDecimal tpTrigger = st.signal.takeProfitPrice();
        if (tpTrigger != null) {
            placeCloseConditional(symbol, exitSide, "TAKE_PROFIT_MARKET",
                    tpTrigger.setScale(spec.pricePrecision(), RoundingMode.HALF_UP), st, "TP");
        }
        st.state = State.POSITION;
        log.info("[TestnetExec] 成交建仓 symbol={} fill={} 已挂SL+TP→POSITION", symbol, fillPrice);
    }

    private void placeCloseConditional(String symbol, String exitSide, String type,
                                       BigDecimal stopPrice, ExecState st, String tag) {
        try {
            PlaceOrderRequest req = PlaceOrderRequest.builder()
                    .symbol(symbol)
                    .side(exitSide)
                    .type(type)
                    .stopPrice(stopPrice)
                    .closePosition(true)           // 全平，不需 quantity
                    .workingType("CONTRACT_PRICE")
                    .newClientOrderId("FIBO-" + symbol + "-" + st.placedBarCloseTime + "-" + tag)
                    .build();
            OrderResponse resp = client.placeOrder(req);
            log.info("[TestnetExec] 挂{} symbol={} {} @ {} orderId={}", tag, symbol, type, stopPrice, resp.getOrderId());
        } catch (Exception e) {
            log.warn("[TestnetExec] 挂{}失败 symbol={} msg={}", tag, symbol, e.toString());
        }
    }

    /** 持仓查平：positionAmt==0 表示 SL/TP 已触发平仓，撤掉未触发的另一个条件单，回 FLAT。 */
    private void checkPosition(String symbol, ExecState st) {
        List<PositionRisk> risks = client.getPositionRisk(symbol);
        BigDecimal amt = risks == null ? BigDecimal.ZERO : risks.stream()
                .filter(r -> symbol.equals(r.getSymbol()))
                .map(PositionRisk::getPositionAmt)
                .filter(Objects::nonNull)
                .findFirst().orElse(BigDecimal.ZERO);
        if (amt.signum() == 0) {
            try {
                client.cancelAllOpenOrders(symbol);  // 撤掉未触发的那个条件单
            } catch (Exception e) {
                log.warn("[TestnetExec] 平仓后撤剩余单失败 symbol={} msg={}", symbol, e.toString());
            }
            log.info("[TestnetExec] 持仓已平 symbol={} 回 FLAT", symbol);
            st.reset();
        }
    }

    /** 固定保证金口径：名义=保证金×杠杆，数量=名义/价，向下取整到 qtyStep。 */
    private BigDecimal sizeQuantity(SymbolSpec spec, BigDecimal price) {
        BigDecimal notional = BigDecimal.valueOf(fixedMarginUsdt).multiply(BigDecimal.valueOf(leverage));
        BigDecimal rawQty = notional.divide(price, 8, RoundingMode.DOWN);
        BigDecimal steps = rawQty.divide(spec.qtyStep(), 0, RoundingMode.DOWN);
        return steps.multiply(spec.qtyStep());
    }

    /**
     * 腿指纹：方向+挂单价，据此判 reaffirm 还是换腿。刻意不含 SL——SL 带 ATR 缓冲每根 bar 尾数微漂，
     * 含入会把同价挂单每根 bar 撤了重挂（GTX 重挂丢排队位+烧 API 权重）；真换腿挂单价必变，语义不丢。
     * 成交后补挂的 SL/TP 用挂单时刻值，滞留被超时撤单封顶，重挂自然刷新。
     */
    private String legKey(StrategySignal s) {
        return s.side() + ":" + s.entryRefPrice().stripTrailingZeros().toPlainString();
    }
}
