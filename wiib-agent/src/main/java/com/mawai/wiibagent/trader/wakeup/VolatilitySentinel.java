package com.mawai.wiibagent.trader.wakeup;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 波动哨兵（探测层）：feed markPrice tick（~1s）→ 每币 5 分钟滚动窗口振幅，超过
 * "币基准阈值 × trader 灵敏度系数"且该 trader（1h/4h 档）持有该币仓位/挂单时，
 * 经 {@link TraderScheduler#tryAlertWake} 准入（冷静期/互斥/预算）触发一次警报唤醒。
 * 例行 K 线唤醒的补充而非替代——只认"世界变了"级别的波动，空仓者不惊动。
 * <p>
 * 基准阈值 180 天历史校准（2026-08-08，kline_history 5m bar 振幅口径），
 * 目标包络：波动日 10~20 次/日、平日 <10 次/日。市场性状变了可重跑校准：
 * <pre>
 * WITH amp AS (SELECT symbol, to_timestamp(open_time/1000)::date d, (high-low)/low*100 a
 *   FROM kline_history WHERE interval_code='5m' AND symbol IN (白名单) AND open_time > 近180天ms),
 * daily AS (SELECT symbol, d, count(*) FILTER (WHERE a>=候选阈值) c FROM amp GROUP BY symbol, d)
 * SELECT symbol, percentile_cont(0.5) WITHIN GROUP (ORDER BY c) 平日,
 *        percentile_cont(0.95) WITHIN GROUP (ORDER BY c) 波动日 FROM daily GROUP BY symbol;
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolatilitySentinel implements MessageListener {

    /** 每币基准阈值%（即平台下限，用户只能经灵敏度系数调高）：BTC平日3次/波动日18次量级 */
    static final Map<String, BigDecimal> BASE_THRESHOLD_PCT = Map.of(
            "BTCUSDT", new BigDecimal("0.6"),
            "ETHUSDT", new BigDecimal("0.8"),
            "XRPUSDT", new BigDecimal("0.8"),
            "SOLUSDT", new BigDecimal("0.9"),
            "DOGEUSDT", new BigDecimal("1.0"));

    /** 警报只服务 1h/4h：5m/15m 自身节奏已够密，被惊醒的边际价值撑不起被打断的成本 */
    static final Set<String> ALERT_INTERVALS = Set.of("1h", "4h");

    static final long WINDOW_MS = 5 * 60_000L;
    private static final long SAMPLE_INTERVAL_MS = 1_000L;
    /** 每币触发后的重臂间隔：纯技术防抖（防同一段行情每秒扫库），业务节流在调度器冷静期 */
    private static final long REARM_MS = 60_000L;

    private final RedisMessageListenerContainer listenerContainer;
    private final AiTraderMapper traderMapper;
    private final SimTradeClient simTradeClient;
    private final TraderScheduler scheduler;

    /**
     * 全都要并发安全：Redis 容器是线程池分发（见 RedisMessageConfig，4~16 线程）、所有币又共用
     * 一个 PRICE channel，同一 symbol 的两个 tick 会真并发进来。1s 采样门是非原子的
     * get-then-put，挡不住，所以窗口自己 synchronized 守（见 PriceWindow）
     */
    private final Map<String, PriceWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSampleAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFiredAt = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[Sentinel] 订阅 {} 启动(type=markprice) 档位={} 基准={}",
                MarketStreamChannels.PRICE, ALERT_INTERVALS, BASE_THRESHOLD_PCT);
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        try {
            JSONObject obj = JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8));
            if (!"markprice".equals(obj.getString("type"))) {
                return;
            }
            String symbol = obj.getString("symbol");
            String price = obj.getString("price");
            if (symbol == null || price == null) {
                return;
            }
            onPriceTick(symbol.trim().toUpperCase(Locale.ROOT), new BigDecimal(price), System.currentTimeMillis());
        } catch (Exception e) {
            // 单条解析失败只丢本 tick，下一 tick 补上
            log.debug("[Sentinel] tick解析失败: {}", e.toString());
        }
    }

    void onPriceTick(String symbol, BigDecimal price, long now) {
        BigDecimal base = BASE_THRESHOLD_PCT.get(symbol);
        if (base == null) {
            return;
        }
        Long lastSample = lastSampleAt.get(symbol);
        if (lastSample != null && now - lastSample < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleAt.put(symbol, now);
        PriceWindow window = windows.computeIfAbsent(symbol, k -> new PriceWindow());
        window.add(now, price);
        BigDecimal amp = window.amplitudePct();
        // 基准即最低生效阈值（系数≥1）：先挡住绝大多数 tick，不达标不碰 DB
        if (amp.compareTo(base) < 0) {
            return;
        }
        Long fired = lastFiredAt.get(symbol);
        if (fired != null && now - fired < REARM_MS) {
            return;
        }
        lastFiredAt.put(symbol, now);
        dispatch(symbol, amp, price, window.direction(), now);
    }

    /** 触发面收窄：档位 1h/4h + 开关开 + 币在白名单 + 阈值×系数达标 + 持有该币仓位/挂单。 */
    private void dispatch(String symbol, BigDecimal amplitudePct, BigDecimal price, String direction, long now) {
        List<AiTrader> candidates = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .in(AiTrader::getIntervalCode, ALERT_INTERVALS));
        for (AiTrader t : candidates) {
            if (Boolean.FALSE.equals(t.getAlertEnabled())) {
                continue;
            }
            if (t.getSymbols() == null || !Arrays.asList(t.getSymbols().split(",")).contains(symbol)) {
                continue;
            }
            if (amplitudePct.compareTo(effectiveThreshold(symbol, t.getAlertThresholdMult())) < 0) {
                continue;
            }
            if (!engaged(t, symbol)) {
                continue;
            }
            scheduler.tryAlertWake(t, new AlertTrigger(symbol, amplitudePct, price, direction, now));
        }
    }

    /** 生效阈值 = 币基准 × 灵敏度系数；null/低于 1 回落 1.0（入口有校验，这里兜手工插库）。 */
    static BigDecimal effectiveThreshold(String symbol, BigDecimal mult) {
        BigDecimal m = mult == null || mult.compareTo(BigDecimal.ONE) < 0 ? BigDecimal.ONE : mult;
        return BASE_THRESHOLD_PCT.get(symbol).multiply(m);
    }

    /** 持有该币仓位/挂单才惊动：空仓者被惊醒只有追单一个动作可做，正撞纪律枪口。 */
    private boolean engaged(AiTrader t, String symbol) {
        try {
            boolean holdsPosition = simTradeClient.getAllPositions(t.getSimUserId()).stream()
                    .anyMatch(p -> symbol.equals(p.getSymbol()));
            return holdsPosition || !simTradeClient.getPendingOrders(t.getSimUserId(), symbol).isEmpty();
        } catch (Exception e) {
            // 查不到就不惊动——警报是补充不是义务
            log.warn("[Sentinel] 持仓查询失败 traderId={} msg={}", t.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 5 分钟滚动窗口（1s 采样）：振幅 (max−min)/min 与方向（窗口首价 vs 现价）。
     * 全部方法 synchronized——tick 由线程池并发喂进来，裸 ArrayDeque 会被写坏：
     * 迭代撞到被 poll 空的槽抛 CME、驱逐时 peekFirst 被别的线程 poll 走抛 NPE，
     * 而这些异常在 onMessage 里被吞成 debug 日志，表面无事、实则窗口静默损坏。
     * 窗口至多 300 个 tick，锁开销可忽略，不值得上并发结构。
     */
    static final class PriceWindow {
        private record Tick(long at, BigDecimal price) {
        }

        private final ArrayDeque<Tick> ticks = new ArrayDeque<>();

        synchronized void add(long now, BigDecimal price) {
            ticks.addLast(new Tick(now, price));
            while (!ticks.isEmpty() && ticks.peekFirst().at() < now - WINDOW_MS) {
                ticks.pollFirst();
            }
        }

        synchronized BigDecimal amplitudePct() {
            if (ticks.size() < 2) {
                return BigDecimal.ZERO;
            }
            BigDecimal max = null;
            BigDecimal min = null;
            for (Tick t : ticks) {
                max = max == null ? t.price() : max.max(t.price());
                min = min == null ? t.price() : min.min(t.price());
            }
            if (min.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return max.subtract(min).multiply(new BigDecimal("100"))
                    .divide(min, 4, RoundingMode.HALF_UP);
        }

        /** 语言无关的方向码：文案在 AlertTrigger 的注释里说明，由开场白按语言取 */
        synchronized String direction() {
            if (ticks.size() < 2) {
                return AlertTrigger.FLAT;
            }
            int cmp = ticks.peekLast().price().compareTo(ticks.peekFirst().price());
            return cmp > 0 ? AlertTrigger.UP : cmp < 0 ? AlertTrigger.DOWN : AlertTrigger.FLAT;
        }
    }
}
