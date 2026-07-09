package com.mawai.wiibquant.agent.strategy.monitor;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.agent.strategy.execution.StrategyAccountRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 三策略独立模拟盘账户监控（quant-FIBO / quant-LIQFADE / quant-SQZMOM）。
 *
 * <p>账户体系与 {@link com.mawai.wiibquant.agent.strategy.execution.SimExecutionService} 同源：
 * 共用 {@link StrategyAccountRegistry}（启动预建 + quant-&lt;ID&gt; 幂等解析 userId）。
 * 数据全部实时拉 sim internal API 组装（TestnetMonitorService 同哲学：无中间落库即无偏差源）；
 * 收益曲线/交易记录都源自已平仓列表，一个 overview 端点带全，前端自行累加画线。</p>
 */
@Slf4j
@Service
public class StrategyAccountService {

    /** 已平仓拉取上限：5m 级一次一仓的节奏够覆盖数月；触顶时曲线只画最近 N 笔。 */
    private static final int CLOSED_LIMIT = 200;

    private final SimTradeClient client;
    private final StrategyAccountRegistry accounts;
    private final List<TradingStrategySpi> strategies;

    public StrategyAccountService(SimTradeClient client, StrategyAccountRegistry accounts,
                                  List<TradingStrategySpi> strategies) {
        this.client = client;
        this.accounts = accounts;
        this.strategies = strategies;
    }

    /** 单策略账户全景：余额/权益/累计盈亏/胜率 + 持仓 + 已平仓历史。 */
    public record StrategyAccountView(String strategyId, Long accountUserId, boolean available,
                                      BigDecimal balance, BigDecimal unrealizedPnl, BigDecimal equity,
                                      BigDecimal cumPnl, int tradeCount, int winCount, double winRate,
                                      List<FuturesPositionDTO> positions,
                                      List<FuturesPositionDTO> closedPositions) {

        static StrategyAccountView unavailable(String strategyId) {
            return new StrategyAccountView(strategyId, null, false,
                    null, null, null, null, 0, 0, 0,
                    List.of(), List.of());
        }
    }

    public List<StrategyAccountView> overview() {
        return strategies.stream().map(s -> {
            String id = s.id();
            try {
                return assemble(id);
            } catch (Exception e) {
                // 单策略账户失败（sim 未启动/账户未建）不拖垮整页，前端按 available 渲染空态
                log.warn("[StrategyAccount] overview 失败 strategyId={} msg={}", id, e.toString());
                return StrategyAccountView.unavailable(id);
            }
        }).toList();
    }

    private StrategyAccountView assemble(String strategyId) {
        Long userId = account(strategyId);
        BigDecimal balance = client.getBalance(userId);
        List<FuturesPositionDTO> positions = client.getAllPositions(userId);
        List<FuturesPositionDTO> closed = client.getClosedPositions(userId, CLOSED_LIMIT);

        BigDecimal unrealized = positions.stream()
                .map(p -> nz(p.getUnrealizedPnl()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumPnl = closed.stream()
                .map(p -> nz(p.getClosedPnl()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int wins = (int) closed.stream().filter(p -> nz(p.getClosedPnl()).signum() > 0).count();
        double winRate = closed.isEmpty() ? 0 : (double) wins / closed.size();
        // 权益=可用余额+持仓保证金+浮盈（sim 的 balance 开仓时已扣走保证金）
        BigDecimal marginInUse = positions.stream()
                .map(p -> nz(p.getMargin()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal equity = balance.add(marginInUse).add(unrealized);

        return new StrategyAccountView(strategyId, userId, true,
                balance, unrealized, equity, cumPnl, closed.size(), wins, winRate,
                positions, closed);
    }

    /**
     * 受控整仓市价平（调用方已完成 userId==1 校验）：服务端实时拉仓位确认 OPEN 并取当前数量，
     * 不信前端传的 qty。平掉后策略状态机由 SimExecutionService 的对账轮询自动回 FLAT，无需通知。
     */
    public void closePosition(String strategyId, Long positionId) {
        Long userId = account(strategyId);
        FuturesPositionDTO pos = client.getAllPositions(userId).stream()
                .filter(p -> positionId.equals(p.getId()) && "OPEN".equals(p.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("仓位不存在或已平仓"));
        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(positionId);
        req.setQuantity(pos.getQuantity());
        req.setOrderType("MARKET");
        client.closePosition(userId, req);
        log.info("[StrategyAccount] 手动平仓 strategyId={} posId={} qty={}", strategyId, positionId, pos.getQuantity());
    }

    private Long account(String strategyId) {
        return accounts.userId(strategyId);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
