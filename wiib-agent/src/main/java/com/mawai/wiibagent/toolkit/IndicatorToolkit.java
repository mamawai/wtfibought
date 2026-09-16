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

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * K线与技术指标工具（AI Trader 数据面核心）：原始 OHLCV 是 AI 的眼睛，指标由
 * {@link CryptoIndicatorCalculator} 全套现算，整段路径与关键位由
 * {@link KlineStructureCalculator} 出。三个工具的分工：
 * <ul>
 *   <li>{@code klines} —— 原始 K 线，让它自己看形态</li>
 *   <li>{@code indicators} —— 末根的点状态（RSI/MACD/BOLL/ATR…）</li>
 *   <li>{@code kline_structure} —— 整段的路径：摆动点、分段量价、量能密集带</li>
 * </ul>
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

    private final KlineFetcher klineFetcher;

    @Tool(name = "klines", description = """
            Get raw OHLCV candlesticks for a crypto perpetual symbol from Binance futures.
            Returns a fixed 192 rows [openTime(ms), open, high, low, close, volume], oldest first;
            the last row is the current still-forming candle (its close is the live price,
            its volume is only partial) and the bar that just closed is the row before it. Tell
            whether the last row is still forming by its openTime: openTime plus one interval later
            than now means it is still running.
            interval: 5m/15m/1h/4h/1d — 192 bars span 16h / 2d / 8d / 32d / 192d respectively.
            Call it when your method requires reading the candles bar by bar: verifying candle
            patterns, applying your own swing or structure definition, or inspecting a stretch
            that `kline_structure` pointed you at. This is the only tool that gives you every
            bar — `kline_structure` carries raw candles too, but only the recent ones and those
            around each key point.
            If you also call `kline_structure` on the same symbol+interval, pass
            includeBars=false there so its focus_bars don't repeat these rows.""")
    public String klines(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                         @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        List<KlineBar> bars = klineFetcher.fetch(sym, interval, KLINE_BARS);
        return bars.isEmpty() ? error("kline data unavailable") : toCompactRows(bars);
    }

    @Tool(name = "indicators", description = """
            Get the full classic technical indicator set for a crypto perpetual symbol, computed
            on the requested interval (5m/15m/1h/4h/1d) over the last 192 candles; the last candle is
            still forming. Fields:
            ma7/ma25/ma99 + ema12/ema20/ema26 (moving averages); ma_alignment: 1 = MA7>MA25>MA99 bullish
            stacking, -1 = bearish stacking, 0 = tangled;
            rsi14 + rsi14_trend (>70 overbought, <30 oversold);
            macd_dif/dea/hist + macd_hist_trend (momentum); macd_cross appears only when a golden/death
            cross happened on the last bar, otherwise the field is absent;
            boll_upper/mid/lower + boll_pb (%B on a 0-100 scale: >100 closed above the upper band, <0 below
            the lower band, 50 at the middle) + boll_bandwidth ((upper-lower)/mid*100; shrinking = squeeze,
            expanding = trending);
            atr14 (Wilder RMA volatility; common stop-distance unit);
            kdj_k/d/j; adx + plus_di/minus_di (adx>25 trending, <15 ranging; direction = which DI is larger);
            obv/obv_ma20/obv_trend + volume_ma20/volume_ratio (volume confirmation; volume_ratio = last bar
            volume / 20-bar average);
            close_trend. Every *_trend field is one of rising_5 (5 consecutive rises), falling_5, mostly_up,
            mostly_down, sideways.""")
    public String indicators(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                             @ToolParam(description = "Interval: 5m/15m/1h/4h/1d") String interval) {
        String err = validateInterval(interval);
        if (err != null) {
            return error(err);
        }
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        List<KlineBar> bars = klineFetcher.fetch(sym, interval, KLINE_BARS);
        if (bars.isEmpty()) {
            return error("kline data unavailable");
        }
        // 末根仍在跳动，实盘快照本就该含它
        return MAPPER.writeValueAsString(CryptoIndicatorCalculator.calcAll(toCalcRows(bars), false));
    }

    @Tool(name = "kline_structure", description = """
            Structural summary of the same 192 candles that `klines` returns — path, segments,
            volume-by-price and key levels, computed for you. Neutral facts only: no trend or
            reversal calls, that judgement is yours.
            Read these before using the numbers:
            - atr14 is Wilder RMA — the exact same value the `indicators` tool reports. avg_tr_* is a
              plain arithmetic mean of True Range: shorter memory, tracks current volatility, and is
              NOT ATR. Never mix the two.
            - swings are local extremes over ±swingWindow bars. The last swingWindow bars can never
              qualify (their right side isn't confirmed yet), so a turn that just happened is missing.
            - segments_swing cuts at swing points and adjacent segments SHARE their boundary bar, so
              their volumes add up to more than volume.total. Do not sum them.
            - every idx refers to the same row order as `klines` on this symbol+interval.
            What you get is the computed path plus focus_bars — the last N candles and the ones
            around each key point; `klines` is the tool that hands you every raw bar instead.""")
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
            Boolean includeBars) {
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
        List<KlineBar> bars = klineFetcher.fetch(sym, interval, KLINE_BARS);
        if (bars.isEmpty()) {
            return error("kline data unavailable");
        }
        // last_forming 恒为 true：REST 拉的末根一定还在跳
        Map<String, Object> out = KlineStructureCalculator.compute(bars,
                new KlineStructureCalculator.Params(w, d.equalSegments(), ln, d.focusRadius(),
                        d.topVolumeBars(), d.maPeriods(), d.atrPeriod(), d.volBinCount(),
                        d.volBinTop(), d.recentSwingLevels(), true));
        if (!withBars) {
            out.remove("focus_bars");
        }
        // 默认不写 null：字段缺席本身就是"算不出"，warnings 里另有说明，省下的是实打实的 token
        return MAPPER.writeValueAsString(out);
    }

    /** → calcAll 的契约行 [high, low, close, volume]。 */
    static List<BigDecimal[]> toCalcRows(List<KlineBar> bars) {
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
