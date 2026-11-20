package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.strategy.core.TradingStrategySpi;
import com.mawai.wiibquant.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.strategy.fibo.FiboParams;
import com.mawai.wiibquant.strategy.fibo.FiboRetracementStrategy;
import com.mawai.wiibquant.strategy.sqzmom.SqueezeMomentumStrategy;
import com.mawai.wiibquant.strategy.sqzmom.SqzMomParams;
import com.mawai.wiibquant.strategy.turtle.TurtleParams;
import com.mawai.wiibquant.strategy.turtle.TurtleStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回测编排：策略注册表（工厂 + warmup 公式，数值照抄各 DbRun 现值）+ 数据装载 + fail-fast 校验。
 * 从 StrategyBacktestController.runFibo 与各 DbRun 抽出的公共骨架；只读本地 kline_history，不触发回填。
 */
@Service
@RequiredArgsConstructor
public class BacktestOrchestrator {

    /**
     * 可回测的策略 id，与 {@link #warmupMs} / {@link #prepare} 两个 switch 同一批。
     * 展示用的名字与说明全在前端词表里（见 wiib-web 的 lib/strategyCatalog），这一层只认 id。
     */
    private static final Set<String> STRATEGY_IDS = Set.of("FIBO", "TURTLE", "SQZMOM");

    /** 引擎开跑所需的全部输入。bars 含预热段，任务服务直接持有同一份供 K 线接口切片。 */
    public record Prepared(TradingStrategySpi strategy, List<KlineBar> bars, int warmupBars) {
    }

    /**
     * 明确给用户看的失败（参数/数据问题），与代码 bug 区分。
     * <p>
     * 带的是词表 key 不是文案：这异常可能抛在 worker 线程上（{@code BacktestTaskService.runTask}），
     * 那里没有请求语言可查；而且一个任务会被指纹去重共享给不同语言的用户，抛的时候成文只能对一个人是对的。
     * {@code getMessage()} 拿到的就是 key，给日志与堆栈用，成文在读的那一侧。
     */
    public static class BacktestSetupException extends RuntimeException {
        private final Map<String, Object> vars;

        public BacktestSetupException(String msgKey) {
            this(msgKey, Map.of());
        }

        public BacktestSetupException(String msgKey, Map<String, Object> vars) {
            super(msgKey);
            this.vars = vars;
        }

        public String msgKey() {
            return getMessage();
        }

        public Map<String, Object> vars() {
            return vars;
        }
    }

    private final KlineHistoryStore klineHistoryStore;

    public boolean knownStrategy(String strategyId) {
        return STRATEGY_IDS.contains(strategyId);
    }

    /** 预热毫秒（照抄各 DbRun 现值；FIBO 含 SMA200 趋势闸预热，比老 /fibo 端点的取值更足）。 */
    public long warmupMs(String strategyId) {
        return switch (strategyId) {
            case "FIBO" -> {
                FiboParams p = FiboParams.defaults();
                long legWarmup = (long) (p.swingLookbackBars() + p.atrPeriod() + 16) * p.swingTfMillis();
                long trendWarmup = 201L * 4 * p.swingTfMillis();
                yield Math.max(legWarmup, trendWarmup);
            }
            case "TURTLE" -> {
                TurtleParams p = TurtleParams.defaults();
                yield (long) (Math.max(p.entryLookback(), p.atrPeriod()) + 4) * p.decisionTfMillis();
            }
            case "SQZMOM" -> {
                SqzMomParams p = SqzMomParams.defaults();
                yield (2L * p.length() + p.squeezeMinBars() + 40) * p.decisionTfMillis();
            }
            default -> throw new BacktestSetupException("quant.backtest.unknownStrategy",
                    Map.of("id", String.valueOf(strategyId)));
        };
    }

    /**
     * 装载数据并构建策略实例。[tradingStartMs, tradingEndMs) 为交易窗（含/不含口径同引擎），
     * 预热段自动前推。交易窗里一根 K 线都没有、数据缺口 → BacktestSetupException fail-fast。
     */
    public Prepared prepare(String strategyId, String symbol, long tradingStartMs, long tradingEndMs) {
        List<KlineBar> bars = klineHistoryStore.load(
                symbol, KlineHistoryStore.DEFAULT_INTERVAL, tradingStartMs - warmupMs(strategyId), tradingEndMs);
        // 交易窗里有一根就照常跑；按收盘时间算，同引擎口径
        if (bars.stream().noneMatch(b -> b.closeTime() >= tradingStartMs && b.closeTime() < tradingEndMs)) {
            throw new BacktestSetupException("quant.backtest.noKlineInRange", Map.of("symbol", symbol));
        }
        WindowedMarketView.firstBaseGapDescription(bars).ifPresent(gap -> {
            throw new BacktestSetupException("quant.backtest.klineGap", Map.of("symbol", symbol, "gap", gap));
        });
        int warmupBars = (int) bars.stream().filter(b -> b.closeTime() < tradingStartMs).count();

        TradingStrategySpi strategy = switch (strategyId) {
            case "FIBO" -> new FiboRetracementStrategy(FiboParams.defaults(), List.of(symbol));
            case "TURTLE" -> new TurtleStrategy(TurtleParams.defaults(), List.of(symbol));
            case "SQZMOM" -> new SqueezeMomentumStrategy(SqzMomParams.defaults(), List.of(symbol));
            default -> throw new BacktestSetupException("quant.backtest.unknownStrategy",
                    Map.of("id", String.valueOf(strategyId)));
        };
        return new Prepared(strategy, bars, warmupBars);
    }
}
