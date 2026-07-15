package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingOperations;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SimExecutionServiceTest {

    private static final String SYM = "ETHUSDT";
    private static final long T0 = 1_000_000L;
    private static final long BAR = 300_000L;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** 可控 stub：记录 open/cancel 调用，orderQuery/positions/balance 可设；不真发 HTTP。 */
    private static final class StubClient extends SimTradeClient {
        final List<FuturesOpenRequest> opened = new ArrayList<>();
        final List<Long> canceled = new ArrayList<>();
        final List<FuturesCloseRequest> closed = new ArrayList<>();
        FuturesOrderResponse openResp;
        FuturesOrderResponse orderQuery;
        List<FuturesPositionDTO> positions = new ArrayList<>();
        List<FuturesOrderResponse> pending = new ArrayList<>();
        BigDecimal balance = bd("10000");
        boolean cancelFails;

        StubClient() {
            super("http://localhost:1", "t");
        }

        @Override
        public Long ensureAccount(String username, BigDecimal initialBalance) {
            return 42L;
        }

        @Override
        public BigDecimal getBalance(Long userId) {
            return balance;
        }

        @Override
        public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
            opened.add(request);
            return openResp;
        }

        @Override
        public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
            closed.add(request);
            positions.removeIf(p -> Objects.equals(p.getId(), request.getPositionId()));   // 模拟 sim 平仓后仓位消失
            FuturesOrderResponse r = new FuturesOrderResponse();
            r.setStatus("FILLED");
            return r;
        }

        @Override
        public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
            if (cancelFails) throw new IllegalStateException("cannot cancel");
            canceled.add(orderId);
            FuturesOrderResponse r = new FuturesOrderResponse();
            r.setStatus("CANCELLED");
            return r;
        }

        @Override
        public FuturesOrderResponse getOrder(Long userId, Long orderId) {
            return orderQuery;
        }

        @Override
        public List<FuturesPositionDTO> getPositions(Long userId, String symbol) {
            return positions;
        }

        @Override
        public List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol) {
            return pending;
        }
    }

    /** 记录 onPositionOpened 的最小策略桩（riskPolicy=defaults: 1%风险/lev5/minRR1.2）。 */
    private static final class RecStrategy implements TradingStrategySpi {
        Long openedPositionId;
        BigDecimal openedFill;

        @Override
        public String id() {
            return "T";
        }

        @Override
        public List<String> symbols() {
            return List.of(SYM);
        }

        @Override
        public StrategyRiskPolicy riskPolicy() {
            return StrategyRiskPolicy.defaults();
        }

        @Override
        public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
            return Optional.empty();
        }

        @Override
        public void onPositionOpened(String symbol, StrategySignal signal, Long positionId,
                                     BigDecimal actualEntryPrice, StrategyMarketView view, TradingOperations tools) {
            openedPositionId = positionId;
            openedFill = actualEntryPrice;
        }
    }

    private SimExecutionService service(StubClient client) {
        StrategyAccountRegistry accounts = new StrategyAccountRegistry(client, List.of());
        accounts.initialBalance = bd("10000");
        SimExecutionService svc = new SimExecutionService(client, accounts);
        svc.enabled = true;
        svc.symbolsCsv = SYM;
        svc.leverage = 5;
        svc.orderTimeoutBars = 12;
        return svc;
    }

    private static FuturesOrderResponse resp(String status, Long orderId, Long positionId, String fill) {
        FuturesOrderResponse r = new FuturesOrderResponse();
        r.setStatus(status);
        r.setOrderId(orderId);
        r.setPositionId(positionId);
        if (fill != null) r.setFilledPrice(bd(fill));
        return r;
    }

    private static FuturesPositionDTO pos(long id) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setStatus("OPEN");
        return p;
    }

    private static StrategySignal market(String entry, String sl, String tp) {
        return new StrategySignal("T", SYM, "LONG", true, bd(entry), bd(sl), bd(tp), 0.5, "t", T0, "MARKET");
    }

    private static StrategySignal limit(String entry, String sl, long barClose) {
        return new StrategySignal("T", SYM, "LONG", true, bd(entry), bd(sl),
                bd(entry).add(bd("100")), 0.5, "t", barClose, "LIMIT");
    }

    private static StrategySignal stop(String side, String trigger, String sl, long barClose) {
        boolean isLong = "LONG".equals(side);
        return new StrategySignal("T", SYM, side, isLong, bd(trigger), bd(sl), null, 0.5, "t", barClose, "STOP");
    }

    @Test
    void 市价信号_风险定量开仓_SLTP随单_转POSITION() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 100L, 7L, "100");
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();

        svc.onSignal(SYM, market("100", "92", "110"), strategy);

        assertThat(client.opened).hasSize(1);
        FuturesOpenRequest req = client.opened.getFirst();
        assertThat(req.getOrderType()).isEqualTo("MARKET");
        assertThat(req.getLeverage()).isEqualTo(5);
        // 风险定量: 10000×1% / (100-92) = 12.5; 保证金 12.5×100/5=250 ≤ 15%上限 ✓
        assertThat(req.getQuantity()).isEqualByComparingTo("12.5");
        assertThat(req.getStopLosses().getFirst().getPrice()).isEqualByComparingTo("92");
        assertThat(req.getStopLosses().getFirst().getQuantity()).isEqualByComparingTo("12.5");
        assertThat(req.getTakeProfits().getFirst().getPrice()).isEqualByComparingTo("110");
        assertThat(strategy.openedPositionId).isEqualTo(7L);
        assertThat(strategy.openedFill).isEqualByComparingTo("100");

        // 持仓中忽略新信号
        svc.onSignal(SYM, market("100", "92", "110"), strategy);
        assertThat(client.opened).hasSize(1);

        // sim 侧平仓(SL/TP/强平) → tick 后回 FLAT, 可再次进场
        client.positions = List.of();
        svc.tick(SYM, T0 + BAR);
        svc.onSignal(SYM, market("100", "92", "110"), strategy);
        assertThat(client.opened).hasSize(2);
    }

    @Test
    void 止损距离过近_保证金15pct上限钳制数量() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 100L, 7L, "100");
        SimExecutionService svc = service(client);

        svc.onSignal(SYM, market("100", "99.5", "110"), new RecStrategy());

        // 原始 qty=10000×1%/0.5=200 → 保证金 200×100/5=4000 > 1500 → 钳到 1500×5/100=75
        assertThat(client.opened.getFirst().getQuantity()).isEqualByComparingTo("75");
    }

    @Test
    void 限价流_挂单_同腿reaffirm不动_换腿撤旧挂新_无信号撤单() {
        StubClient client = new StubClient();
        client.openResp = resp("PENDING", 1000L, null, null);
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();

        svc.onSignal(SYM, limit("3000", "2950", T0), strategy);
        assertThat(client.opened).hasSize(1);
        assertThat(client.opened.getFirst().getOrderType()).isEqualTo("LIMIT");
        assertThat(client.opened.getFirst().getLimitPrice()).isEqualByComparingTo("3000");

        svc.onSignal(SYM, limit("3000", "2950", T0 + BAR), strategy);   // 同腿 reaffirm
        assertThat(client.opened).hasSize(1);

        svc.onSignal(SYM, limit("3100", "3050", T0 + 2 * BAR), strategy);   // 换腿
        assertThat(client.canceled).containsExactly(1000L);
        assertThat(client.opened).hasSize(2);

        svc.noSignal(SYM, "T");   // 腿失效撤单
        assertThat(client.canceled).hasSize(2);
    }

    @Test
    void 限价成交_通知策略消费腿_平仓后回FLAT() {
        StubClient client = new StubClient();
        client.openResp = resp("PENDING", 1000L, null, null);
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();
        svc.onSignal(SYM, limit("3000", "2950", T0), strategy);

        client.orderQuery = resp("FILLED", 1000L, 9L, "3000");
        client.positions = List.of(pos(9L));
        svc.tick(SYM, T0 + BAR);
        assertThat(strategy.openedPositionId).isEqualTo(9L);

        client.positions = List.of();   // sim 平仓
        svc.tick(SYM, T0 + 2 * BAR);
        svc.onSignal(SYM, limit("3200", "3150", T0 + 3 * BAR), strategy);   // FLAT 后可重新挂
        assertThat(client.opened).hasSize(2);
    }

    @Test
    void 挂单超时_撤单回FLAT() {
        StubClient client = new StubClient();
        client.openResp = resp("PENDING", 1000L, null, null);
        SimExecutionService svc = service(client);
        svc.onSignal(SYM, limit("3000", "2950", T0), new RecStrategy());

        client.orderQuery = resp("PENDING", 1000L, null, null);
        svc.tick(SYM, T0 + 11 * BAR);
        assertThat(client.canceled).isEmpty();          // 未到 12 根
        svc.tick(SYM, T0 + 12 * BAR);
        assertThat(client.canceled).containsExactly(1000L);
    }

    @Test
    void 撤单失败保持WORKING_下一tick按成交收养_防双开() {
        StubClient client = new StubClient();
        client.openResp = resp("PENDING", 1000L, null, null);
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();
        svc.onSignal(SYM, limit("3000", "2950", T0), strategy);

        client.cancelFails = true;                       // 撤单被 CAS 拒(已成交竞态)
        client.orderQuery = resp("PENDING", 1000L, null, null);
        svc.tick(SYM, T0 + 12 * BAR);
        svc.onSignal(SYM, limit("3100", "3050", T0 + 13 * BAR), strategy);   // 换腿也因撤失败不重挂
        assertThat(client.opened).hasSize(1);

        client.orderQuery = resp("FILLED", 1000L, 9L, "3000");   // 真相: 其实已成交
        svc.tick(SYM, T0 + 13 * BAR);
        assertThat(strategy.openedPositionId).isEqualTo(9L);     // 收养为 POSITION, 未双开
    }

    @Test
    void 触价信号_ARMED不落单_tick穿越才市价开仓_持仓后不再触发() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 100L, 7L, "3001");
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();

        svc.onSignal(SYM, stop("LONG", "3000", "2900", T0), strategy);
        assertThat(client.opened).isEmpty();                 // 虚拟单，不落 sim

        svc.onPriceTick(SYM, bd("2995"));                    // 未穿越
        assertThat(client.opened).isEmpty();

        svc.onPriceTick(SYM, bd("3001"));                    // 穿越 → 市价进场，SL 随单
        assertThat(client.opened).hasSize(1);
        FuturesOpenRequest req = client.opened.getFirst();
        assertThat(req.getOrderType()).isEqualTo("MARKET");
        // 风险定量按触发价：10000×1%/(3000-2900)=1
        assertThat(req.getQuantity()).isEqualByComparingTo("1");
        assertThat(req.getStopLosses().getFirst().getPrice()).isEqualByComparingTo("2900");
        assertThat(strategy.openedPositionId).isEqualTo(7L);

        svc.onPriceTick(SYM, bd("3002"));                    // POSITION 后 tick 不再触发
        assertThat(client.opened).hasSize(1);
    }

    @Test
    void 触价信号_同腿reaffirm刷新SL_换腿替换_无信号撤虚拟单() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 100L, 7L, "3051");
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();

        svc.onSignal(SYM, stop("LONG", "3000", "2900", T0), strategy);
        svc.onSignal(SYM, stop("LONG", "3000", "2910", T0 + BAR), strategy);    // 同腿 reaffirm(SL漂移)
        svc.onSignal(SYM, stop("LONG", "3050", "2950", T0 + 2 * BAR), strategy); // 换腿(触发价变)
        assertThat(client.opened).isEmpty();                 // 全程无 sim 交互

        svc.onPriceTick(SYM, bd("3050"));                    // 按新腿触发，SL 是换腿后的 2950
        assertThat(client.opened).hasSize(1);
        assertThat(client.opened.getFirst().getStopLosses().getFirst().getPrice()).isEqualByComparingTo("2950");

        // 平仓回 FLAT → 重新 arm → noSignal 撤虚拟单 → tick 不触发
        client.positions = List.of();
        svc.tick(SYM, T0 + 3 * BAR);
        svc.onSignal(SYM, stop("LONG", "3100", "3000", T0 + 4 * BAR), strategy);
        svc.noSignal(SYM, "T");
        svc.onPriceTick(SYM, bd("3200"));
        assertThat(client.opened).hasSize(1);                // 未新增
    }

    @Test
    void 触价信号_空头下穿触发() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 101L, 8L, "2799");
        SimExecutionService svc = service(client);
        RecStrategy strategy = new RecStrategy();

        svc.onSignal(SYM, stop("SHORT", "2800", "2900", T0), strategy);
        svc.onPriceTick(SYM, bd("2850"));                    // 高于触发价，不触发
        assertThat(client.opened).isEmpty();
        svc.onPriceTick(SYM, bd("2799"));                    // 下穿触发
        assertThat(client.opened).hasSize(1);
        assertThat(client.opened.getFirst().getSide()).isEqualTo("SHORT");
        assertThat(strategy.openedPositionId).isEqualTo(8L);
    }

    /** 时间出场桩：按 LiqFade 同款公式（createdAt 按 UTC 解释）持满 holdMs 即市价平。 */
    private static final class HookStrategy implements TradingStrategySpi {
        FuturesPositionDTO seen;
        long nowMs;
        long holdMs;

        @Override
        public String id() {
            return "T";
        }

        @Override
        public List<String> symbols() {
            return List.of(SYM);
        }

        @Override
        public StrategyRiskPolicy riskPolicy() {
            return StrategyRiskPolicy.defaults();
        }

        @Override
        public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
            return Optional.empty();
        }

        @Override
        public void onPositionBarClosed(String symbol, FuturesPositionDTO position,
                                        StrategyMarketView view, TradingOperations tools) {
            seen = position;
            long openedMs = position.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            if (nowMs - openedMs >= holdMs) {
                tools.closePositionWithReason(position.getId(), position.getQuantity(), "TIME_EXIT");
            }
        }
    }

    /** sim 口径的持仓 DTO：createdAt=本机时区钟面（MetaObjectHandler LocalDateTime.now() 同款）。 */
    private static FuturesPositionDTO simPos(long id, String qty, long openedEpochMs) {
        FuturesPositionDTO p = pos(id);
        p.setQuantity(bd(qty));
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(openedEpochMs), ZoneId.systemDefault()));
        return p;
    }

    @Test
    void 持仓钩子_createdAt时区对齐_持满时间市价平_当根回FLAT可再进场() {
        StubClient client = new StubClient();
        client.openResp = resp("FILLED", 100L, 7L, "100");
        SimExecutionService svc = service(client);
        HookStrategy strategy = new HookStrategy();
        strategy.holdMs = 3_600_000L;

        svc.onSignal(SYM, market("100", "92", "110"), strategy);   // FLAT→POSITION posId=7

        // 差1秒不满1h：不平；策略按UTC解释转换后的createdAt须还原出正确epoch（差8h即时区口径bug）
        client.positions = new ArrayList<>(List.of(simPos(7L, "12.5", T0)));
        strategy.nowMs = T0 + 3_599_000L;
        svc.onPositionBarClosed(SYM, strategy, null);
        assertThat(strategy.seen.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()).isEqualTo(T0);
        assertThat(client.closed).isEmpty();

        // 满1h：市价整仓平（每次重新fetch，换新DTO模拟真实HTTP反序列化）
        client.positions = new ArrayList<>(List.of(simPos(7L, "12.5", T0)));
        strategy.nowMs = T0 + 3_600_000L;
        svc.onPositionBarClosed(SYM, strategy, null);
        assertThat(client.closed).hasSize(1);
        assertThat(client.closed.getFirst().getPositionId()).isEqualTo(7L);
        assertThat(client.closed.getFirst().getQuantity()).isEqualByComparingTo("12.5");
        assertThat(client.closed.getFirst().getOrderType()).isEqualTo("MARKET");

        // 钩子平仓后当根即回 FLAT：同 bar 新信号可进场（对齐回测：出场→再评估）
        svc.onSignal(SYM, market("100", "92", "110"), strategy);
        assertThat(client.opened).hasSize(2);
    }

    @Test
    void 持仓钩子_重启对账收养后仍驱动时间出场() {
        StubClient client = new StubClient();
        client.positions = new ArrayList<>(List.of(simPos(5L, "3", T0)));
        SimExecutionService svc = service(client);
        HookStrategy strategy = new HookStrategy();
        strategy.holdMs = 3_600_000L;
        strategy.nowMs = T0 + 7_200_000L;   // 重启回来已超时

        svc.onPositionBarClosed(SYM, strategy, null);   // 首次触达：收养→钩子→平仓

        assertThat(client.closed).hasSize(1);
        assertThat(client.closed.getFirst().getPositionId()).isEqualTo(5L);
    }

    @Test
    void 重启对账_收养持仓并撤残留挂单() {
        StubClient client = new StubClient();
        client.positions = new ArrayList<>(List.of(pos(5L)));
        FuturesOrderResponse stray = resp("PENDING", 77L, null, null);
        client.pending = List.of(stray);
        SimExecutionService svc = service(client);

        svc.onSignal(SYM, market("100", "92", "110"), new RecStrategy());

        assertThat(client.canceled).containsExactly(77L);   // 残留挂单撤掉
        assertThat(client.opened).isEmpty();                // 已收养持仓, 新信号被 POSITION 挡下
    }
}
