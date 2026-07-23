import { fmtNum, fmtDateTime } from '../lib/utils';
import { useEffect, useRef, useState, useCallback } from 'react';
import {
  createChart, CrosshairMode, CandlestickSeries, HistogramSeries, LineSeries, LineStyle,
  type IChartApi, type ISeriesApi, type UTCTimestamp, type MouseEventParams,
} from 'lightweight-charts';
import { futuresApi } from '../api';
import { useKlineStream } from '../hooks/useKlineStream';
import { useIsDark } from '../hooks/useIsDark';
import { getCoinPriceDecimals } from '../lib/coinConfig';
import { bollSeries, emaSeries, macdSeries, maSeries, rsiSeries } from '../lib/indicators';

/** 一根 K：series 只用 OHLC，量/额留给气泡和成交量柱。 */
interface Bar { time: number; openMs: number; open: number; high: number; low: number; close: number; volume: number; quote: number; }

const TZ = -8 * 3600;                                       // 固定 UTC+8 偏移(秒)：横轴统一显示新加坡时间且边界对齐
const toBarTime = (ms: number) => Math.floor(ms / 1000) - TZ;
const barDate = (t: number) => new Date((t + TZ) * 1000);   // 反算真实时刻用于格式化
const fmtVol = (n: number) => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(2) + 'K' : n.toFixed(2);
const VOL_UP = 'rgba(8,153,129,.5)', VOL_DOWN = 'rgba(242,54,69,.5)';

/** 气泡 HTML（固定深色，亮/暗主题下都清晰）：时间·开高低收·涨跌·涨跌幅·振幅·量·额。 */
function tooltipHtml(bar: Bar, bars: Bar[], idx: Map<number, number>, d: number, base: string): string {
  const i = idx.get(bar.time);
  const prevClose = (i != null && i > 0) ? bars[i - 1].close : bar.open;   // 昨收=前一根收盘
  const chg = bar.close - prevClose;
  const chgPct = prevClose ? chg / prevClose * 100 : 0;
  const amp = prevClose ? (bar.high - bar.low) / prevClose * 100 : 0;
  const up = chg >= 0, col = up ? '#0abf95' : '#ff5a68', sign = up ? '+' : '';
  const tStr = fmtDateTime(barDate(bar.time));
  const row = (k: string, v: string, c = '#d1d4dc') =>
    `<div style="display:flex;justify-content:space-between;gap:18px"><span style="color:#6b7280">${k}</span><span style="color:${c};font-weight:700">${v}</span></div>`;
  return `<div style="color:#878b96;font-weight:700;margin-bottom:5px;padding-bottom:5px;border-bottom:1px solid #23262e">${tStr}</div>`
    + row('开', fmtNum(bar.open, d)) + row('高', fmtNum(bar.high, d)) + row('低', fmtNum(bar.low, d)) + row('收', fmtNum(bar.close, d))
    + row('涨跌', sign + fmtNum(chg, d), col) + row('涨跌幅', sign + chgPct.toFixed(2) + '%', col)
    + row('振幅', amp.toFixed(2) + '%')
    + row('量', fmtVol(bar.volume) + ' ' + base) + row('额', fmtVol(bar.quote) + ' USDT');
}

// ========== 副图指标 (MACD / RSI) ==========

const RSI_PERIODS = [6, 12, 24] as const;              // 国内常用的三档，短中长各一条
const RSI_COLORS = ['#ff9800', '#2196f3', '#9c27b0'];
const DIF_COLOR = '#f0b90b', DEA_COLOR = '#2962ff';
const REF_LINE = 'rgba(128,128,128,.4)';               // RSI 70/30 灰色虚线
const LEGEND_DIM = '#8a8f9a';                          // legend 里指标名那截的灰

/**
 * MACD 柱四色：浓色=动能增强，淡色=动能衰减。
 * 等价于国内软件"实心柱/空心柱"的语义 —— lightweight-charts 的 histogram
 * 每根柱只能给一个填充色，做不出描边空心，而 TradingView 官方 MACD 也是这套四色。
 */
const HIST_UP_S = 'rgba(38,166,154,.9)', HIST_UP_W = 'rgba(38,166,154,.28)';
const HIST_DN_S = 'rgba(239,83,80,.9)', HIST_DN_W = 'rgba(239,83,80,.28)';

/** 比前一根更远离零轴 = 动能还在增强 = 浓色(实心)；往零轴回收 = 淡色(空心) */
function histColor(cur: number, prev: number | null): string {
  const strong = prev === null || (cur >= 0 ? cur >= prev : cur <= prev);
  if (cur >= 0) return strong ? HIST_UP_S : HIST_UP_W;
  return strong ? HIST_DN_S : HIST_DN_W;
}

/** 副图 series 句柄 + 读数条；indicators=false 时压根不建，整个为 null */
interface IndSeries {
  chart: IChartApi;
  hist: ISeriesApi<'Histogram'>;
  dif: ISeriesApi<'Line'>;
  dea: ISeriesApi<'Line'>;
  rsi: ISeriesApi<'Line'>[];                 // 与 RSI_PERIODS 同序
  macdLegend: HTMLDivElement | null;
  rsiLegend: HTMLDivElement | null;
  decimals: number;
  /** 最新一根的值，鼠标没悬停时 legend 常驻显示这个 */
  last: { dif?: number; dea?: number; hist?: number; rsi: (number | undefined)[] };
}

/** null 段跳过不画（预热期指标无值），返回 LWC 要的点数组 */
const toLine = (bars: Bar[], s: (number | null)[]) =>
  bars.flatMap((b, i) => s[i] === null ? [] : [{ time: b.time as UTCTimestamp, value: s[i] as number }]);

/** 读数条里的一格：`DIF:2.61`，无值显示 -- */
const legendCell = (color: string, label: string, v: number | undefined, digits: number) =>
  `<span style="color:${color};margin-right:9px">${label}:${v === undefined ? '--' : v.toFixed(digits)}</span>`;

/**
 * 手机竖屏（图表宽 < 380）走紧凑读数：字号降一档、砍掉指标参数前缀。
 * 按图表实际宽度判断而不是视口——同一台机器横屏、或 PC 上窗口拖窄，都该跟着缩。
 */
const isCompact = (chart: IChartApi) => chart.options().width < 380;

// ========== 主图叠加指标 (MA / EMA / BOLL) ==========

/** MA 和 EMA 共用这组周期 */
const MA_PERIODS = [7, 25, 99] as const;
const MA_COLORS = ['#f0b90b', '#e91e63', '#26c6da'];    // 7黄 25粉 99青
const EMA_COLORS = ['#ff9800', '#ab47bc', '#66bb6a'];   // 同周期错开色相，免得跟 MA 混
const BOLL_PERIOD = 20, BOLL_MULT = 2;
const BOLL_MID_COLOR = '#90a4ae', BOLL_BAND_COLOR = 'rgba(144,164,174,.65)';

export type OverlayKey = 'ma' | 'ema' | 'boll';

interface OverlaySeries {
  ma: ISeriesApi<'Line'>[];
  ema: ISeriesApi<'Line'>[];
  boll: ISeriesApi<'Line'>[];                // [upper, mid, lower]
  /** 最新一根的值，没悬停时读数条显示它 */
  last: { ma: (number | undefined)[]; ema: (number | undefined)[]; boll: (number | undefined)[] };
}

const lastOfSeries = (s: (number | null)[]) => {
  const v = s[s.length - 1];
  return v === null || v === undefined ? undefined : v;
};

/** 主图三组指标一次算齐 */
function computeOverlay(bars: Bar[]) {
  const closes = bars.map(b => b.close);
  const b = bollSeries(closes, BOLL_PERIOD, BOLL_MULT);
  return {
    ma: MA_PERIODS.map(p => maSeries(closes, p)),
    ema: MA_PERIODS.map(p => emaSeries(closes, p)),
    boll: [b.upper, b.mid, b.lower],
  };
}

function cacheOverlayLast(ov: OverlaySeries, c: ReturnType<typeof computeOverlay>) {
  ov.last = { ma: c.ma.map(lastOfSeries), ema: c.ema.map(lastOfSeries), boll: c.boll.map(lastOfSeries) };
}

function setOverlayData(ov: OverlaySeries, bars: Bar[]) {
  const c = computeOverlay(bars);
  c.ma.forEach((s, k) => ov.ma[k].setData(toLine(bars, s)));
  c.ema.forEach((s, k) => ov.ema[k].setData(toLine(bars, s)));
  c.boll.forEach((s, k) => ov.boll[k].setData(toLine(bars, s)));
  cacheOverlayLast(ov, c);
}

/** 同副图的道理：EMA/MA/BOLL 都只有末端一个值会随未收盘那根变，重算全量后只 update 末点 */
function updateOverlayLast(ov: OverlaySeries, bars: Bar[]) {
  const n = bars.length - 1;
  if (n < 0) return;
  const c = computeOverlay(bars);
  const time = bars[n].time as UTCTimestamp;
  const push = (arr: ISeriesApi<'Line'>[], series: (number | null)[][]) =>
    series.forEach((s, k) => { if (s[n] !== null) arr[k].update({ time, value: s[n] as number }); });
  push(ov.ma, c.ma);
  push(ov.ema, c.ema);
  push(ov.boll, c.boll);
  cacheOverlayLast(ov, c);
}

const lastOf = (s: (number | null)[]) => {
  const v = s[s.length - 1];
  return v === null || v === undefined ? undefined : v;
};

/** 一次算齐副图要的所有序列，setData 和实时 update 共用 */
function computeAll(bars: Bar[]) {
  const closes = bars.map(b => b.close);
  const { dif, dea, hist } = macdSeries(closes);
  return { dif, dea, hist, rsis: RSI_PERIODS.map(p => rsiSeries(closes, p)) };
}

type Computed = ReturnType<typeof computeAll>;

function cacheLast(ind: IndSeries, c: Computed) {
  ind.last = { dif: lastOf(c.dif), dea: lastOf(c.dea), hist: lastOf(c.hist), rsi: c.rsis.map(lastOf) };
}

/** 在 pane 左上角挂一条读数。v5 暴露 getHTMLElement 正是为了这种叠加内容 */
function makeLegend(host: HTMLElement | null): HTMLDivElement | null {
  if (!host) return null;
  // pane 元素默认 static，不改成 relative 的话 legend 会飞到更外层的定位祖先上
  if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
  const el = document.createElement('div');
  el.style.cssText = 'position:absolute;left:10px;top:2px;z-index:3;pointer-events:none;'
    + 'font:11px/1.6 ui-monospace,Consolas,monospace;font-weight:700;white-space:nowrap';
  host.appendChild(el);
  return el;
}

/**
 * 副图 legend 的懒创建。
 *
 * 不能在 addSeries 之后立刻建：pane 的 DOM(paneWidget) 是渲染流程里 _syncGuiWithModel
 * 才创建的，addSeries 只建了 pane 的 model，此刻 getHTMLElement() 还返回 null。
 * 所以每次刷读数时补一次，第一次拉到历史数据时图表早已 paint 过，必然建得上。
 */
function ensureLegends(ind: IndSeries) {
  const panes = ind.chart.panes();
  if (!ind.macdLegend) ind.macdLegend = makeLegend(panes[1]?.getHTMLElement() ?? null);
  if (!ind.rsiLegend) ind.rsiLegend = makeLegend(panes[2]?.getHTMLElement() ?? null);
}

/**
 * 刷新两条读数。param 落在某根 bar 上就显示那根的值（跟随十字线），
 * 否则回落到最新值 —— 不悬停时读数不能空着。
 */
function renderLegends(ind: IndSeries, param: MouseEventParams | null) {
  ensureLegends(ind);
  const compact = isCompact(ind.chart);
  const hovering = param?.time != null;
  const pick = (s: ISeriesApi<'Line'> | ISeriesApi<'Histogram'>, fallback: number | undefined) => {
    if (!hovering) return fallback;
    const d = param?.seriesData.get(s);
    return d && 'value' in d ? (d.value as number) : undefined;
  };

  if (ind.macdLegend) {
    const h = pick(ind.hist, ind.last.hist);
    const hc = h === undefined ? LEGEND_DIM : (h >= 0 ? '#089981' : '#f23645');
    ind.macdLegend.style.fontSize = compact ? '10px' : '11px';
    // 窄屏砍掉参数前缀：那截占 88px，手机上留着会把 MACD 值挤出可视区（nowrap 直接裁掉）
    ind.macdLegend.innerHTML =
      (compact ? '' : `<span style="color:${LEGEND_DIM};margin-right:10px">MACD(12,26,9)</span>`)
      + legendCell(DIF_COLOR, 'DIF', pick(ind.dif, ind.last.dif), ind.decimals)
      + legendCell(DEA_COLOR, 'DEA', pick(ind.dea, ind.last.dea), ind.decimals)
      + legendCell(hc, 'MACD', h, ind.decimals);
  }
  if (ind.rsiLegend) {
    ind.rsiLegend.style.fontSize = compact ? '10px' : '11px';
    ind.rsiLegend.innerHTML = RSI_PERIODS
      .map((p, k) => legendCell(RSI_COLORS[k], `RSI(${p})`, pick(ind.rsi[k], ind.last.rsi[k]), 2))
      .join('');
  }
}

const BOLL_LABELS = ['UP', 'MID', 'LOW'];
const OVERLAY_COLORS: Record<OverlayKey, string[]> = {
  ma: MA_COLORS, ema: EMA_COLORS, boll: [BOLL_BAND_COLOR, BOLL_MID_COLOR, BOLL_BAND_COLOR],
};

/**
 * 图表上方工具条里的读数区。开着的组摊开显示三个值（悬停跟随十字线，不悬停显示最新值），
 * 关着的留空——开关是工具条上的按钮，不再兼任显示入口。
 */
function renderOverlayLegend(
  ov: OverlaySeries,
  refs: Record<OverlayKey, HTMLSpanElement | null>,
  on: Record<OverlayKey, boolean>,
  param: MouseEventParams | null,
  decimals: number,
  compact = false,
) {
  const hovering = param?.time != null;
  const pick = (s: ISeriesApi<'Line'>, fallback: number | undefined) => {
    if (!hovering) return fallback;
    const d = param?.seriesData.get(s);
    return d && 'value' in d ? (d.value as number) : undefined;
  };
  const labels: Record<OverlayKey, string[]> = {
    ma: MA_PERIODS.map(p => `MA${p}`),
    ema: MA_PERIODS.map(p => `EMA${p}`),
    boll: BOLL_LABELS,
  };

  for (const key of ['ma', 'ema', 'boll'] as OverlayKey[]) {
    const el = refs[key];
    if (!el) continue;
    el.style.fontSize = compact ? '10px' : '11px';
    if (!on[key]) { el.innerHTML = ''; continue; }
    el.innerHTML = ov[key]
      .map((s, k) => legendCell(OVERLAY_COLORS[key][k], labels[key][k], pick(s, ov.last[key][k]), decimals))
      .join('');
  }
}

/** 历史全量灌入副图 */
function setIndicators(ind: IndSeries, bars: Bar[]) {
  const c = computeAll(bars);
  ind.hist.setData(bars.flatMap((b, i) => c.hist[i] === null ? []
    : [{
      time: b.time as UTCTimestamp,
      value: c.hist[i] as number,
      color: histColor(c.hist[i] as number, i > 0 ? c.hist[i - 1] : null),
    }]));
  ind.dif.setData(toLine(bars, c.dif));
  ind.dea.setData(toLine(bars, c.dea));
  c.rsis.forEach((s, k) => ind.rsi[k].setData(toLine(bars, s)));
  cacheLast(ind, c);
}

/**
 * 只刷新最后一根的指标值。
 * 未收盘那根 close 在变，但 EMA/RSI 都是从历史向前递推、前面的点早已定型，
 * close 变化只波及递推链末端那一个值 —— 所以全量重算后只 update 末点是精确的，不是近似。
 * (500 根纯算术 <1ms；不做增量状态维护，那反而在同一根 bar 被反复重写时会算错。)
 */
function updateIndicatorsLast(ind: IndSeries, bars: Bar[]) {
  const n = bars.length - 1;
  if (n < 0) return;
  const c = computeAll(bars);
  const time = bars[n].time as UTCTimestamp;
  if (c.hist[n] !== null) {
    ind.hist.update({
      time, value: c.hist[n] as number,
      color: histColor(c.hist[n] as number, n > 0 ? c.hist[n - 1] : null),
    });
  }
  if (c.dif[n] !== null) ind.dif.update({ time, value: c.dif[n] as number });
  if (c.dea[n] !== null) ind.dea.update({ time, value: c.dea[n] as number });
  c.rsis.forEach((s, k) => { if (s[n] !== null) ind.rsi[k].update({ time, value: s[n] as number }); });
  cacheLast(ind, c);
}

/**
 * 实时蜡烛图 + 成交量柱 + 悬停气泡。
 * 历史走 REST(含量/额)；当前根两种驱动二选一：
 * - streamLive=true（默认，合约）：走 {@link useKlineStream}(后端实时广播 o/h/l/c/v/q)
 * - streamLive=false（现货/bstock，后端不广播其K线）：由外部 tick(价格流)更新最后一根的 c/h/l
 */
const BUCKET_MS = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000 } as const;
type Interval = keyof typeof BUCKET_MS;

export function CandleChart({ symbol, interval, limit = 300, visibleBars = 110, klinesFn = futuresApi.klines, streamLive = true, tick = null, indicators = false }: { symbol: string; interval: Interval; limit?: number; visibleBars?: number; klinesFn?: (symbol: string, interval: string, limit: number) => Promise<number[][]>; streamLive?: boolean; tick?: { price: number; ts: number } | null; indicators?: boolean }) {
  const isDark = useIsDark();
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartDivRef = useRef<HTMLDivElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const indRef = useRef<IndSeries | null>(null);
  const ovRef = useRef<OverlaySeries | null>(null);
  // 默认全关走裸K：三组九条线画满会糊成一团，要看哪组点图表上方工具条的按钮开
  const [overlays, setOverlays] = useState<Record<OverlayKey, boolean>>({ ma: false, ema: false, boll: false });
  const overlaysRef = useRef(overlays);
  const maLegendRef = useRef<HTMLSpanElement>(null);
  const emaLegendRef = useRef<HTMLSpanElement>(null);
  const bollLegendRef = useRef<HTMLSpanElement>(null);
  const barsRef = useRef<Bar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const readyRef = useRef(false);
  const hoverRef = useRef<{ time: number | null; x: number; y: number }>({ time: null, x: 0, y: 0 });
  const isDarkRef = useRef(isDark);
  const decimals = getCoinPriceDecimals(symbol);
  const base = symbol.replace('USDT', '');

  const live = useKlineStream(symbol, interval);

  // 三个 span 的当前节点。读数高频刷新，走 DOM 直改而不是 setState，免得鼠标一动就整树重渲染
  const legendRefs = useCallback(() => ({
    ma: maLegendRef.current, ema: emaLegendRef.current, boll: bollLegendRef.current,
  }), []);

  // 显示/定位气泡（用 ref，避免闭包读到过期 isDark/props）
  const showTip = (bar: Bar, px: number, py: number) => {
    const tip = tipRef.current, wrap = wrapRef.current; if (!tip || !wrap) return;
    tip.innerHTML = tooltipHtml(bar, barsRef.current, idxRef.current, decimals, base);
    tip.style.display = 'block';
    const W = wrap.clientWidth, H = wrap.clientHeight, tw = tip.offsetWidth, th = tip.offsetHeight;
    let x = px + 16, y = py + 16;
    if (x + tw > W - 4) x = px - tw - 16;     // 贴右→翻左
    if (y + th > H - 4) y = H - th - 6;       // 贴底→上移
    tip.style.left = Math.max(4, x) + 'px';
    tip.style.top = Math.max(4, y) + 'px';
  };
  const showTipRef = useRef(showTip);
  // 事件回调只在交互时读取，提交后同步最新值即可（render 期写 ref 违反 react-hooks/refs）
  useEffect(() => { isDarkRef.current = isDark; showTipRef.current = showTip; });

  // 建图 + 拉历史（symbol/interval 变则重建）
  useEffect(() => {
    const wrap = wrapRef.current, host = chartDivRef.current;
    if (!wrap || !host) return;
    const dark = isDarkRef.current;
    const grid = dark ? '#181b21' : '#f1f1ee', border = dark ? '#23262e' : '#e4e4df', text = dark ? '#878b96' : '#71737b';

    const chart = createChart(host, {
      width: host.clientWidth, height: host.clientHeight,
      // attributionLogo: v5 默认 true 会在右下角画 TradingView logo，v4 没有，关掉保持原样
      // panes.separatorColor: v5 默认 #2B2B43 深蓝，亮色模式下很扎眼，跟着网格线走
      layout: {
        background: { color: 'transparent' }, textColor: text, fontSize: 11, attributionLogo: false,
        panes: { separatorColor: grid, separatorHoverColor: 'rgba(128,128,128,.25)', enableResize: true },
      },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      crosshair: { mode: CrosshairMode.Normal },
      // 手机纵向滑动交还给页面滚动（否则想下滑页面却在拖图表）；横向平移/捏合缩放保留
      handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
      // 读数条已移到图表外的工具条，顶部只留常规呼吸空间
      rightPriceScale: { borderColor: border, scaleMargins: { top: 0.06, bottom: 0.26 } },
      timeScale: { borderColor: border, timeVisible: true, secondsVisible: false, rightOffset: 5 },
    });
    chartRef.current = chart;

    const candle = chart.addSeries(CandlestickSeries, {
      upColor: '#089981', downColor: '#f23645', borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
    });
    candleRef.current = candle;

    const vol = chart.addSeries(HistogramSeries, { priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false });
    vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });   // 量柱压底部 18%
    volRef.current = vol;

    // 主图叠加：MA / EMA / BOLL 都挂 pane 0，跟蜡烛共用价格轴。
    // 三组一律建出来，显不显示走 visible，切换时不用重建 series 重灌数据
    if (indicators) {
      const on = overlaysRef.current;
      const thin = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false };
      const ov: OverlaySeries = {
        ma: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, { ...thin, color: MA_COLORS[k], visible: on.ma })),
        ema: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, { ...thin, color: EMA_COLORS[k], visible: on.ema })),
        boll: [0, 1, 2].map(k => chart.addSeries(LineSeries, {
          ...thin,
          color: k === 1 ? BOLL_MID_COLOR : BOLL_BAND_COLOR,
          lineStyle: k === 1 ? LineStyle.Solid : LineStyle.Dashed,   // 中轨实线，上下轨虚线
          visible: on.boll,
        })),
        last: { ma: [], ema: [], boll: [] },
      };
      ovRef.current = ov;
    }

    // 副图：MACD 挂 pane1、RSI 挂 pane2（addSeries 第三参数就是 pane 序号，v5 自带时间轴/十字线联动）
    if (indicators) {
      const line = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false };
      const hist = chart.addSeries(HistogramSeries, { priceLineVisible: false, lastValueVisible: false }, 1);
      const dif = chart.addSeries(LineSeries, { ...line, color: DIF_COLOR }, 1);
      const dea = chart.addSeries(LineSeries, { ...line, color: DEA_COLOR }, 1);
      // RSI 天然 0-100，锁死纵轴免得自适应缩放把 70/30 线挤出视野
      const rsi = RSI_PERIODS.map((_, k) => chart.addSeries(LineSeries, {
        ...line, color: RSI_COLORS[k],
        autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }),
      }, 2));
      for (const price of [70, 30]) {
        rsi[0].createPriceLine({ price, color: REF_LINE, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: false, title: '' });
      }

      const panes = chart.panes();
      panes[0].setStretchFactor(3);      // 主图:MACD:RSI = 3:1:1
      panes[1].setStretchFactor(1);
      panes[2].setStretchFactor(1);
      // 顶层 rightPriceScale 的 scaleMargins(bottom .26) 是给主图量柱留的，会连累副图；这里按 pane 覆盖掉
      panes[1].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.12 } });  // top 留给 legend
      panes[2].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.08 } });
      indRef.current = {
        chart, hist, dif, dea, rsi, decimals,
        macdLegend: makeLegend(panes[1].getHTMLElement()),
        rsiLegend: makeLegend(panes[2].getHTMLElement()),
        last: { rsi: [] },
      };
    }

    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      // 读数条先更新：它跟气泡不同，移出图表也要留着（回落到最新值），不能被下面的 early return 跳过
      if (indRef.current) renderLegends(indRef.current, param);
      if (ovRef.current) renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, param, decimals, isCompact(chart));
      const tip = tipRef.current; if (!tip) return;
      const t = param.time as number | undefined;
      if (t == null || !param.point) { hoverRef.current.time = null; tip.style.display = 'none'; return; }
      const i = idxRef.current.get(t);
      if (i == null) { tip.style.display = 'none'; return; }
      hoverRef.current = { time: t, x: param.point.x, y: param.point.y };
      showTipRef.current(barsRef.current[i], param.point.x, param.point.y);
    });

    readyRef.current = false;
    klinesFn(symbol, interval, limit).then(raw => {
      const bars: Bar[] = [], idx = new Map<number, number>();
      for (const k of raw) {
        const time = toBarTime(k[0]);
        idx.set(time, bars.length);
        bars.push({ time, openMs: k[0], open: +k[1], high: +k[2], low: +k[3], close: +k[4], volume: +k[5], quote: +k[7] });
      }
      barsRef.current = bars; idxRef.current = idx;
      candle.setData(bars.map(b => ({ time: b.time as UTCTimestamp, open: b.open, high: b.high, low: b.low, close: b.close })));
      vol.setData(bars.map(b => ({ time: b.time as UTCTimestamp, value: b.volume, color: b.close >= b.open ? VOL_UP : VOL_DOWN })));
      if (indRef.current) { setIndicators(indRef.current, bars); renderLegends(indRef.current, null); }
      if (ovRef.current) {
        setOverlayData(ovRef.current, bars);
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
      }
      // 默认只看最近 visibleBars 根（fitContent 会把全量挤进视口，蜡烛小成一条线）；往左拖/缩放仍可看全历史
      if (bars.length > visibleBars) {
        chart.timeScale().setVisibleLogicalRange({ from: bars.length - visibleBars, to: bars.length + 5 });
      } else {
        chart.timeScale().fitContent();
      }
      readyRef.current = true;
    }).catch(() => { /* 历史失败仍可靠实时累积 */ });

    const ro = new ResizeObserver(() => {
      chart.applyOptions({ width: host.clientWidth, height: host.clientHeight });
      // 旋屏/拖窗口会让 compact 判定翻转，读数条得跟着重排，否则要等下一个 tick 才变
      if (indRef.current) renderLegends(indRef.current, null);
      if (ovRef.current) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, isCompact(chart));
      }
    });
    ro.observe(host);

    return () => {
      ro.disconnect(); chart.remove();
      chartRef.current = null; candleRef.current = null; volRef.current = null;
      indRef.current = null; ovRef.current = null;
      readyRef.current = false; barsRef.current = []; idxRef.current = new Map();
    };
  }, [symbol, interval, limit, visibleBars, decimals, klinesFn, indicators, legendRefs]);

  // 指标开关：只切 visible，不重建 series；切完立刻刷读数（展开的组要马上有值）
  useEffect(() => {
    overlaysRef.current = overlays;
    const ov = ovRef.current; if (!ov) return;
    ov.ma.forEach(s => s.applyOptions({ visible: overlays.ma }));
    ov.ema.forEach(s => s.applyOptions({ visible: overlays.ema }));
    ov.boll.forEach(s => s.applyOptions({ visible: overlays.boll }));
    renderOverlayLegend(ov, legendRefs(), overlays, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
  }, [overlays, decimals, legendRefs]);

  // 主题切换：只改颜色，不重建
  useEffect(() => {
    const chart = chartRef.current; if (!chart) return;
    const grid = isDark ? '#181b21' : '#f1f1ee', border = isDark ? '#23262e' : '#e4e4df', text = isDark ? '#878b96' : '#71737b';
    chart.applyOptions({
      layout: { textColor: text, panes: { separatorColor: grid } },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      rightPriceScale: { borderColor: border }, timeScale: { borderColor: border },
    });
  }, [isDark]);

  // 外部价格 tick 驱动（streamLive=false）：桶对齐后更新/追加最后一根，量额保持历史值（价格流无量数据）
  useEffect(() => {
    if (streamLive || !tick || tick.price <= 0 || !readyRef.current) return;
    const candle = candleRef.current, vol = volRef.current; if (!candle || !vol) return;
    const bucketMs = BUCKET_MS[interval];
    const openMs = Math.floor(tick.ts / bucketMs) * bucketMs;
    const time = toBarTime(openMs);
    const last = barsRef.current[barsRef.current.length - 1];
    if (last && time < last.time) return;
    const i = idxRef.current.get(time);
    let bar: Bar;
    if (i == null) {
      bar = { time, openMs, open: tick.price, high: tick.price, low: tick.price, close: tick.price, volume: 0, quote: 0 };
      idxRef.current.set(time, barsRef.current.length);
      barsRef.current.push(bar);
    } else {
      const prev = barsRef.current[i];
      bar = { ...prev, close: tick.price, high: Math.max(prev.high, tick.price), low: Math.min(prev.low, tick.price) };
      barsRef.current[i] = bar;
    }
    candle.update({ time: bar.time as UTCTimestamp, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
    vol.update({ time: bar.time as UTCTimestamp, value: bar.volume, color: bar.close >= bar.open ? VOL_UP : VOL_DOWN });
    if (indRef.current) {
      updateIndicatorsLast(indRef.current, barsRef.current);
      // 没悬停才刷读数，否则会把用户正盯着的那根值冲掉
      if (hoverRef.current.time === null) renderLegends(indRef.current, null);
    }
    if (ovRef.current) {
      updateOverlayLast(ovRef.current, barsRef.current);
      if (hoverRef.current.time === null) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
      }
    }
    if (hoverRef.current.time === bar.time) showTipRef.current(bar, hoverRef.current.x, hoverRef.current.y);
  }, [tick, streamLive, interval, decimals, legendRefs]);

  // 实时：当前根原地更新（蜡烛 + 量柱 + 悬停时刷新气泡）
  useEffect(() => {
    if (!streamLive || !live || !readyRef.current) return;
    const candle = candleRef.current, vol = volRef.current; if (!candle || !vol) return;
    const time = toBarTime(live.t);
    // 防乱序：忽略比最后一根更早的(重连/迟到)消息，否则 LWC update(time<lastTime) 会抛异常
    const last = barsRef.current[barsRef.current.length - 1];
    if (last && time < last.time) return;
    const bar: Bar = { time, openMs: live.t, open: live.o, high: live.h, low: live.l, close: live.c, volume: live.v, quote: live.q };
    const i = idxRef.current.get(time);
    if (i == null) { idxRef.current.set(time, barsRef.current.length); barsRef.current.push(bar); }
    else barsRef.current[i] = bar;
    candle.update({ time: time as UTCTimestamp, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
    vol.update({ time: time as UTCTimestamp, value: bar.volume, color: bar.close >= bar.open ? VOL_UP : VOL_DOWN });
    if (indRef.current) {
      updateIndicatorsLast(indRef.current, barsRef.current);
      // 没悬停才刷读数，否则会把用户正盯着的那根值冲掉
      if (hoverRef.current.time === null) renderLegends(indRef.current, null);
    }
    if (ovRef.current) {
      updateOverlayLast(ovRef.current, barsRef.current);
      if (hoverRef.current.time === null) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
      }
    }
    if (hoverRef.current.time === time) showTipRef.current(bar, hoverRef.current.x, hoverRef.current.y);
  }, [live, streamLive, decimals, legendRefs]);

  const toggle = (k: OverlayKey) => setOverlays(prev => ({ ...prev, [k]: !prev[k] }));

  // 开关按钮样式对齐周期切换组（激活=顶部主色内阴影）
  const chipCls = (on: boolean) =>
    `px-2.5 py-1 text-[11px] font-semibold transition-colors cursor-pointer ${
      on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
         : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;

  return (
    <div className="w-full h-full flex flex-col">
      {/* 指标工具条（图表外）：MA/EMA/BOLL 开关 + 开启组的读数。
          读数 span 由 renderOverlayLegend 走 DOM 直改（悬停跟随十字线），高频刷新不过 React */}
      {indicators && (
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 px-2 md:px-1 pb-1.5">
          <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
            {(['ma', 'ema', 'boll'] as OverlayKey[]).map(k => (
              <button key={k} type="button" onClick={() => toggle(k)} className={chipCls(overlays[k])}>
                {k.toUpperCase()}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
               style={{ font: '700 11px/1.6 ui-monospace, Consolas, monospace' }}>
            <span ref={maLegendRef} />
            <span ref={emaLegendRef} />
            <span ref={bollLegendRef} />
          </div>
        </div>
      )}

      <div ref={wrapRef} className="relative w-full flex-1 min-h-0">
        <div ref={chartDivRef} className="absolute inset-0" />
        <div ref={tipRef} style={{
          position: 'absolute', display: 'none', pointerEvents: 'none', zIndex: 5,
          background: 'rgba(13,14,18,.9)', border: '1px solid #23262e', borderRadius: 8, padding: '8px 11px',
          font: '12px/1.6 ui-monospace, Consolas, monospace', minWidth: 154, boxShadow: '0 6px 22px rgba(0,0,0,.45)',
        }} />
      </div>
    </div>
  );
}
