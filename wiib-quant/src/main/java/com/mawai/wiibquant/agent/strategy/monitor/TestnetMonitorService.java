package com.mawai.wiibquant.agent.strategy.monitor;

import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetClient;
import com.mawai.wiibquant.agent.binance.BinanceFuturesTestnetProperties;
import com.mawai.wiibquant.agent.binance.model.AccountInfo;
import com.mawai.wiibquant.agent.binance.model.IncomeRecord;
import com.mawai.wiibquant.agent.binance.model.OrderResponse;
import com.mawai.wiibquant.agent.binance.model.PositionRisk;
import com.mawai.wiibquant.agent.binance.model.UserTrade;
import com.mawai.wiibquant.agent.strategy.monitor.dto.DailyCell;
import com.mawai.wiibquant.agent.strategy.monitor.dto.EquityPoint;
import com.mawai.wiibquant.agent.strategy.monitor.dto.FillStats;
import com.mawai.wiibquant.agent.strategy.monitor.dto.OverviewView;
import com.mawai.wiibquant.agent.strategy.monitor.dto.OverviewView.AccountView;
import com.mawai.wiibquant.agent.strategy.monitor.dto.OverviewView.OpenOrderView;
import com.mawai.wiibquant.agent.strategy.monitor.dto.OverviewView.PositionView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 监测看板数据组装：纯实时直拉 testnet（方案A），不落库、不缓存——无中间环节即无偏差源。
 *
 * <p>所有金额/价格用 testnet 原始字段，仅做"累计/分组/计数"这类无歧义聚合。
 * 盈亏口径 = REALIZED_PNL + COMMISSION + FUNDING_FEE（与钱包净变化一致），剔除本金转账。
 * testnet 历史接口单窗口 ≤7 天，故按周滚动分段拉取后合并（fibo 交易稀疏，单段远不及 limit 1000）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestnetMonitorService {

    private static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 计入策略盈亏的流水类型；TRANSFER/WELCOME_BONUS 等本金类剔除。 */
    private static final Set<String> PNL_TYPES = Set.of("REALIZED_PNL", "COMMISSION", "FUNDING_FEE");

    private final BinanceFuturesTestnetClient client;
    private final BinanceFuturesTestnetProperties props;

    // ==================== 实时总览 ====================

    /** 账户 + 持仓 + 挂单当下快照。未配置/异常时返回空结构（看板显示空态，不崩）。 */
    public OverviewView overview() {
        try {
            AccountInfo acc = client.getAccount();
            AccountView account = new AccountView(
                    acc.getTotalWalletBalance(), acc.getTotalMarginBalance(),
                    acc.getTotalUnrealizedProfit(), acc.getAvailableBalance());

            List<PositionView> positions = client.getPositionRisk(null).stream()
                    .filter(p -> p.getPositionAmt() != null && p.getPositionAmt().signum() != 0)
                    .map(this::toPositionView)
                    .toList();

            List<OpenOrderView> openOrders = new ArrayList<>();
            for (String sym : symbols()) {
                client.getOpenOrders(sym).forEach(o -> openOrders.add(new OpenOrderView(
                        o.getSymbol(), o.getOrderId(), o.getClientOrderId(), o.getSide(), o.getType(),
                        o.getPrice(), o.getStopPrice(), o.getOrigQty(), o.getStatus(), o.getTime())));
            }
            return new OverviewView(account, positions, openOrders);
        } catch (Exception e) {
            log.warn("[TestnetMonitor] overview 失败（key未配置或网络）: {}", e.toString());
            return new OverviewView(new AccountView(null, null, null, null), List.of(), List.of());
        }
    }

    private PositionView toPositionView(PositionRisk p) {
        return new PositionView(p.getSymbol(),
                p.getPositionAmt().signum() > 0 ? "LONG" : "SHORT",
                p.getPositionAmt().abs(), p.getEntryPrice(), p.getMarkPrice(),
                p.getUnRealizedProfit(), p.getLiquidationPrice());
    }

    // ==================== 交易记录 ====================

    /** 近 days 天全部真实成交，按时间倒序。 */
    public List<UserTrade> trades(String symbol, int days) {
        long to = System.currentTimeMillis();
        long from = to - days * 86_400_000L;
        List<UserTrade> all = new ArrayList<>();
        try {
            for (String sym : targets(symbol)) {
                all.addAll(windowed(from, to, (a, b) -> client.getUserTrades(sym, a, b, null, 1000)));
            }
        } catch (Exception e) {
            log.warn("[TestnetMonitor] trades 失败: {}", e.toString());
        }
        all.sort(Comparator.comparingLong(UserTrade::getTime).reversed());
        return all;
    }

    // ==================== 日交易网格 ====================

    /** 近 days 天按东八区切日聚合：pnl=当天净盈亏(含手续费)，tradeCount=平仓笔数。 */
    public List<DailyCell> dailyGrid(String symbol, int days) {
        Map<String, BigDecimal> pnlByDay = new TreeMap<>();   // 日期升序
        Map<String, Integer> cntByDay = new TreeMap<>();
        for (IncomeRecord i : pnlIncome(symbol, days)) {
            String d = day(i.getTime());
            pnlByDay.merge(d, i.getIncome(), BigDecimal::add);
            if ("REALIZED_PNL".equals(i.getIncomeType())) cntByDay.merge(d, 1, Integer::sum);
        }
        return pnlByDay.entrySet().stream()
                .map(e -> new DailyCell(e.getKey(), e.getValue(), cntByDay.getOrDefault(e.getKey(), 0)))
                .toList();
    }

    // ==================== 权益曲线 ====================

    /** 近 days 天累计已实现盈亏曲线（从0起，含手续费）。 */
    public List<EquityPoint> equity(String symbol, int days) {
        List<IncomeRecord> incomes = new ArrayList<>(pnlIncome(symbol, days));
        incomes.sort(Comparator.comparingLong(IncomeRecord::getTime));
        List<EquityPoint> pts = new ArrayList<>(incomes.size());
        BigDecimal cum = BigDecimal.ZERO;
        for (IncomeRecord i : incomes) {
            cum = cum.add(i.getIncome());
            pts.add(new EquityPoint(i.getTime(), cum));
        }
        return pts;
    }

    // ==================== fill 对账 ====================

    /** 近 days 天进场 LIMIT 单成交质量：成交率/超时率/成交时长/零滑点确认。 */
    public FillStats fillStats(String symbol, int days) {
        long to = System.currentTimeMillis();
        long from = to - days * 86_400_000L;
        List<OrderResponse> entries = new ArrayList<>();
        try {
            for (String sym : targets(symbol)) {
                windowed(from, to, (a, b) -> client.getAllOrders(sym, a, b, null, 1000)).stream()
                        // 进场单：FIBO- 前缀的 LIMIT（条件单是 STOP_MARKET/TAKE_PROFIT_MARKET，type 不同）
                        .filter(o -> "LIMIT".equals(o.getType())
                                && o.getClientOrderId() != null && o.getClientOrderId().startsWith("FIBO-"))
                        .forEach(entries::add);
            }
        } catch (Exception e) {
            log.warn("[TestnetMonitor] fillStats 失败: {}", e.toString());
        }

        int placed = entries.size();
        int filled = 0, expired = 0, makerConfirmed = 0;
        long fillMsSum = 0;
        for (OrderResponse o : entries) {
            if ("FILLED".equals(o.getStatus())) {
                filled++;
                if (o.getTime() != null && o.getUpdateTime() != null) fillMsSum += o.getUpdateTime() - o.getTime();
                // GTX maker 成交价应=挂单价，验证零滑点
                if (o.getPrice() != null && o.getAvgPrice() != null && o.getAvgPrice().compareTo(o.getPrice()) == 0) {
                    makerConfirmed++;
                }
            } else if ("CANCELED".equals(o.getStatus()) || "EXPIRED".equals(o.getStatus())) {
                expired++;
            }
        }
        double fillRate = placed > 0 ? (double) filled / placed : 0;
        double avgFillSec = filled > 0 ? fillMsSum / 1000.0 / filled : 0;
        return new FillStats(placed, filled, expired, fillRate, avgFillSec, makerConfirmed);
    }

    // ==================== 内部 ====================

    /** 拉 income 并只留策略盈亏类型。 */
    private List<IncomeRecord> pnlIncome(String symbol, int days) {
        long to = System.currentTimeMillis();
        long from = to - days * 86_400_000L;
        List<IncomeRecord> all = new ArrayList<>();
        try {
            for (String sym : targets(symbol)) {
                all.addAll(windowed(from, to, (a, b) -> client.getIncome(sym, null, a, b, 1000)));
            }
        } catch (Exception e) {
            log.warn("[TestnetMonitor] income 失败: {}", e.toString());
        }
        return all.stream().filter(i -> PNL_TYPES.contains(i.getIncomeType())).toList();
    }

    /** 按 ≤7 天窗口滚动分段拉取后合并；单段满 1000 告警（理论上 fibo 稀疏不会触及）。 */
    private <T> List<T> windowed(long from, long to, WindowFetcher<T> fetcher) {
        List<T> all = new ArrayList<>();
        long cursor = from;
        while (cursor < to) {
            long end = Math.min(cursor + WEEK_MS, to);
            List<T> batch = fetcher.fetch(cursor, end);
            if (batch != null && !batch.isEmpty()) {
                all.addAll(batch);
                if (batch.size() >= 1000) {
                    log.warn("[TestnetMonitor] 单窗口满1000，可能漏数据 [{}, {}]", cursor, end);
                }
            }
            cursor = end + 1;
        }
        return all;
    }

    @FunctionalInterface
    private interface WindowFetcher<T> {
        List<T> fetch(long from, long to);
    }

    /** symbol 指定则单币，否则全白名单。 */
    private List<String> targets(String symbol) {
        if (symbol != null && !symbol.isBlank()) return List.of(symbol.trim().toUpperCase());
        return symbols();
    }

    private List<String> symbols() {
        return props.getSymbols() == null ? List.of() : props.getSymbols();
    }

    private String day(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate().toString();
    }
}
