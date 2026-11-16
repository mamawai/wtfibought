package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.KlineFetcher;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.market.indicator.CryptoIndicatorCalculator;
import com.mawai.wiibquant.market.indicator.KlineStructureCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * K线与技术指标工具（AI Trader 数据面核心）：原始 OHLCV 是 AI 的眼睛，指标由
 * {@link CryptoIndicatorCalculator} 全套现算，整段路径与关键位由
 * {@link KlineStructureCalculator} 出。三个工具的分工：
 * <ul>
 *   <li>{@code klines} —— 原始 K 线，含还在走的末根（它的 close 就是最新价），让它自己看形态</li>
 *   <li>{@code indicators} —— 刚收盘那根的点状态（RSI/MACD/BOLL/ATR…）</li>
 *   <li>{@code kline_structure} —— 整段的路径：摆动点、分段量价、量能密集带</li>
 * </ul>
 * 后两个只按已收盘的 K 线算：唤醒时在走的那根才开了几秒，量比、金叉死叉、*_trend 掺进它全是噪声。
 * 三个工具共用 {@link #KLINE_BARS} 同一个窗口，一次网络喂三家（见 {@link KlineFetcher}）。
 * 窗口统一是硬要求，不只是省事：klines 与 kline_structure 的 idx 得能互相索引；
 * 而 ATR 走 Wilder RMA、记忆很长，同一个符号喂不同根数会算出不同的值——
 * 窗口一致，indicators 与 kline_structure 的 atr14 才逐位相同。
 * interval 由 AI 自选——交易 15m 的 trader 可以主动查 1h/4h 做多周期确认；根数不给它选。
 * <p>
 * 这里只出中性的原始事实与公开标准指标，不替 trader 做任何形态/结构判读——
 * 用户的交易思路是从提示词灌进来的（缠论、道氏、量价各家都有），
 * 代码每多算一层结论就等于替它选了一派，把别派的人挡在门外。
 */
@Component
@RequiredArgsConstructor
public class IndicatorToolkit {

    private static final Set<String> INTERVALS = Set.of("5m", "15m", "1h", "4h", "1d");
    /**
     * 原始K线固定 192 根，不让模型自己报数。两个原因：
     * 一是模型面对"max=N"的描述只会顶格要，报多少全凭当轮心情；
     * 二是根数进缓存键，各报各的就等于各存各的——10 个 trader 同一时刻醒来全部 miss，
     * 而且键都不一样，连"只放一个去拉、其余等它"都并不到一块。
     * 192 覆盖 5m=16h / 15m=2d / 1h=8d / 4h=32d / 1d=192d，够装下市面上大多数体系要看的窗口；
     * 且落在 Binance klines 权重 2 那档（100~499），再往上到 500 跳 5，不划算。
     */
    private static final int KLINE_BARS = 192;
    /** swing 半窗上限：window*2+1 得装得下，192 根理论到 95；卡 50 是不让模型把窗口开到没有 swing 可出 */
    private static final int MAX_SWING_WINDOW = 50;
    /** 截止时间参数的格式，按北京时间读 */
    /** 三个工具共用的截止时间参数说明 */
    private static final String END_TIME_DESC = """
            Optional window end, "yyyy-MM-dd HH:mm" in UTC+8 (Beijing time): the 192 bars then end at the
            bar containing that moment instead of now. Use it for a past stretch (a specific day, around an
            event). Omit for the latest bars ending now.""";

    private final KlineFetcher klineFetcher;
    /** 墙钟注入点：末根走没走完要测得了 */
    LongSupplier nowMs = System::currentTimeMillis;

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）
    @Tool(name = "klines")
    public String klines(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                         @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval,
                         @ToolParam(required = false, description = END_TIME_DESC) String endTime) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        List<KlineBar> bars = klineFetcher.fetch(sym, interval, KLINE_BARS, endTimeMs(endTime));
        return bars.isEmpty() ? error("kline data unavailable") : toCompactRows(bars);
    }

    @Tool(name = "indicators")
    public String indicators(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                             @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval,
                             @ToolParam(required = false, description = END_TIME_DESC) String endTime) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        List<KlineBar> bars = KlineBar.closed(klineFetcher.fetch(sym, interval, KLINE_BARS, endTimeMs(endTime)), nowMs.getAsLong());
        if (bars.isEmpty()) {
            return error("kline data unavailable");
        }
        return MAPPER.writeValueAsString(CryptoIndicatorCalculator.calcAll(toCalcRows(bars)));
    }

    @Tool(name = "kline_structure")
    public String klineStructure(
            @ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
            @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval,
            @ToolParam(required = false, description = """
                    Swing half-window in bars (default 6). Smaller = more and finer turns
                    (3 suits scalping); larger = only the major ones (12+ suits swing trading).""")
            Integer swingWindow,
            @ToolParam(required = false, description = """
                    Recent-window size (default 20): drives the volume/volatility ratios and how many
                    candles focus_bars.last carries.""")
            Integer lastN,
            @ToolParam(required = false, description = """
                    Include focus_bars — raw candles for the last N plus those around the high /
                    low / max-volume bar (default true). Pass false if you already called klines.""")
            Boolean includeBars,
            @ToolParam(required = false, description = """
                    Include segments_swing — per-leg price and volume between consecutive swing points
                    (default false). It is the largest block of the output, a little under half.""")
            Boolean includeSwingSegments,
            @ToolParam(required = false, description = END_TIME_DESC) String endTime) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        KlineStructureCalculator.Params d = KlineStructureCalculator.Params.defaults();
        // 模型漏传就用默认；传了也要夹取——它有权选流派，没权把窗口开到比数据还长
        int w = swingWindow == null ? d.swingWindow() : Math.clamp(swingWindow, 1, MAX_SWING_WINDOW);
        int ln = lastN == null ? d.lastN() : Math.clamp(lastN, 1, KLINE_BARS);
        boolean withBars = includeBars == null || includeBars;
        // 与 klines 同一个 KLINE_BARS 窗口：两边 idx 才对得上，
        // 模型看到"最大量在 idx=128"能直接回 klines 那份数据里翻第 128 行
        List<KlineBar> bars = KlineBar.closed(klineFetcher.fetch(sym, interval, KLINE_BARS, endTimeMs(endTime)), nowMs.getAsLong());
        if (bars.isEmpty()) {
            return error("kline data unavailable");
        }
        Map<String, Object> out = KlineStructureCalculator.compute(bars,
                new KlineStructureCalculator.Params(w, d.equalSegments(), ln, d.focusRadius(),
                        d.topVolumeBars(), d.maPeriods(), d.atrPeriod(), d.volBinCount(),
                        d.volBinTop(), d.recentSwingLevels()));
        if (!withBars) {
            out.remove("focus_bars");
        }
        // 按摆动点切的逐段量价占整份输出四成多，要了才带
        if (!Boolean.TRUE.equals(includeSwingSegments)) {
            out.remove("segments_swing");
        }
        // 默认不写 null：字段缺席本身就是"算不出"，warnings 里另有说明，省下的是实打实的 token
        return MAPPER.writeValueAsString(out);
    }

    /** → calcAll 的契约行 [high, low, close, volume]。 */
    public static List<BigDecimal[]> toCalcRows(List<KlineBar> bars) {
        List<BigDecimal[]> rows = new ArrayList<>(bars.size());
        for (KlineBar b : bars) {
            rows.add(new BigDecimal[]{b.high(), b.low(), b.close(), b.volume()});
        }
        return rows;
    }

    /** → 紧凑行 [openTime,open,high,low,close,volume]（数值不带引号省 token）。 */
    static String toCompactRows(List<KlineBar> bars) {
        ArrayNode out = MAPPER.createArrayNode();
        for (KlineBar b : bars) {
            ArrayNode row = out.addArray();
            row.add(b.openTime());
            for (BigDecimal v : List.of(b.open(), b.high(), b.low(), b.close(), b.volume())) {
                // stripTrailingZeros 会产生 1E+2 科学计数法，过一遍 toPlainString 恢复普通标度
                row.add(new BigDecimal(v.stripTrailingZeros().toPlainString()));
            }
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 模型传的截止时间 → 毫秒；没传就是截止现在。格式不对抛出，由 FailureAsResult 原样报给模型 */
    private static Long endTimeMs(String endTime) {
        return endTime == null || endTime.isBlank() ? null : BeijingTime.parse(endTime);
    }

    /** 返回 null=合法；否则给模型看的错误说明。 */
    static String validateInterval(String interval) {
        return interval != null && INTERVALS.contains(interval) ? null
                : "invalid interval, allowed: 5m/15m/1h/4h/1d";
    }

    private static String error(String reason) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("available", false);
        o.put("reason", reason);
        return MAPPER.writeValueAsString(o);
    }
}
