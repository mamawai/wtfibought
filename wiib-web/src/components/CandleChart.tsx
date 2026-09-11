import { cn, fmtNum, fmtDateTime } from '../lib/utils';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import i18n, { currentLang } from '../i18n';
import {
  createChart, createSeriesMarkers, AreaSeries, CandlestickSeries, HistogramSeries, LineSeries, LineStyle,
  type IChartApi, type ISeriesApi, type UTCTimestamp, type MouseEventParams,
  type DeepPartial, type HandleScrollOptions, type IPriceLine, type SeriesMarker,
} from 'lightweight-charts';
import {
  CalendarClock, ChartCandlestick, ChartLine, ChartNoAxesCombined, ChevronDown, Expand, History, Layers, Shrink,
} from 'lucide-react';
import { futuresApi, quantApi, type EconCalendarEvent } from '../api';
import { useKlineStream } from '../hooks/useKlineStream';
import { useIsDark } from '../hooks/useIsDark';
import { useFullscreen } from '../hooks/useFullscreen';
import { useClickOutside } from '../hooks/useClickOutside';
import { getCoinPriceDecimals } from '../lib/coinConfig';
import { bollSeries, emaSeries, macdSeries, maSeries, rsiSeries } from '../lib/indicators';
import { lwcTheme, rgba } from '../lib/chartTheme';
import type { ChartCtx } from '../lib/chartDrawings';
import { useDrawings } from './chart/useDrawings';
import { DrawToolPopover, DrawToolRail } from './chart/DrawToolPicker';
import { EconMarkersLayer } from './chart/EconMarkersLayer';
import { flagHtml } from './CountryFlag';

/** 一根 K：series 只用 OHLC，量/额留给读数和成交量柱。 */
interface Bar { time: number; openMs: number; open: number; high: number; low: number; close: number; volume: number; quote: number; }

const TZ = -8 * 3600;                                       // 固定 UTC+8 偏移(秒)：横轴统一显示新加坡时间且边界对齐
const toBarTime = (ms: number) => Math.floor(ms / 1000) - TZ;
const barDate = (t: number) => new Date((t + TZ) * 1000);   // 反算真实时刻用于格式化
const fmtVol = (n: number) => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(2) + 'K' : n.toFixed(2);
/** 币安原始行 → Bar：k[0]=开盘ms，1-4=OHLC，5=量(基础币)，7=额(USDT) */
const toBar = (k: number[]): Bar => ({
  time: toBarTime(k[0]), openMs: k[0],
  open: +k[1], high: +k[2], low: +k[3], close: +k[4], volume: +k[5], quote: +k[7],
});

/** 成交弹窗里每笔的时刻，只要时分（哪一天由弹窗标题那根 K 线交代） */
const fmtFillTime = (ms: number) =>
  new Date(ms).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Singapore', hour: '2-digit', minute: '2-digit', hour12: false });

/** 日线标签只显示日期：1d 的 bar 开在 UTC 0 点(=新加坡 08:00)，挂个 08:00 纯噪音 */
const fmtBarTime = (d: Date, interval: string) =>
  interval === '1d'
    ? d.toLocaleDateString('zh-CN', { timeZone: 'Asia/Singapore', month: '2-digit', day: '2-digit' })
    : fmtDateTime(d);

// ========== 图内读数（照海报 .lg：主图左上三行、量柱一行、副图各一行） ==========

/** 读数容器：绝对定位在 pane 左上，穿透点击 */
const LG_BASE = 'position:absolute;left:10px;z-index:3;pointer-events:none;white-space:nowrap;font-size:11.5px;line-height:1.55';
/** 每格之间 9px；格内标签灰、值跟着外层色走 */
const LG_SP = 'margin-right:9px';
const LG_LABEL = 'font-style:normal;margin-right:3px';
/** 三条线的固定配色（同 lwcTheme().col3），走 CSS 变量所以切主题不用重刷读数 */
const LG_COL3 = ['var(--color-primary)', '#2f8fd6', '#7c5cff'];

/** 一格读数：`<i>开</i>63,720.5`，color 缺省则继承外层（整行涨跌色） */
const cell = (k: string, v: string, color?: string) =>
  `<span style="${LG_SP}${color ? `;color:${color}` : ''}"><i class="mute" style="${LG_LABEL}">${k}</i>${v}</span>`;

interface LegendEls { main: HTMLDivElement | null; vol: HTMLDivElement | null; macd: HTMLDivElement | null; rsi: HTMLDivElement | null; }

/**
 * 在 pane 左上角挂一条读数。v5 暴露 getHTMLElement 正是为了这种叠加内容。
 * <p>pane 元素默认 static，不改成 relative 的话读数会飞到更外层的定位祖先上。
 */
function makeLegend(host: HTMLElement | null, top: string): HTMLDivElement | null {
  if (!host) return null;
  if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
  const el = document.createElement('div');
  el.style.cssText = `${LG_BASE};top:${top}`;
  host.appendChild(el);
  return el;
}

// ========== 副图指标 (MACD / RSI) ==========

const RSI_PERIODS = [6, 12, 24] as const;              // 国内常用的三档，短中长各一条

/**
 * MACD 柱四色：浓色=动能增强，淡色=动能衰减。
 * 等价于国内软件"实心柱/空心柱"的语义 —— lightweight-charts 的 histogram
 * 每根柱只能给一个填充色，做不出描边空心，而 TradingView 官方 MACD 也是这套四色。
 */
interface HistColors { upS: string; upW: string; dnS: string; dnW: string; }

/** 比前一根更远离零轴 = 动能还在增强 = 浓色(实心)；往零轴回收 = 淡色(空心) */
function histColor(cur: number, prev: number | null, c: HistColors): string {
  const strong = prev === null || (cur >= 0 ? cur >= prev : cur <= prev);
  if (cur >= 0) return strong ? c.upS : c.upW;
  return strong ? c.dnS : c.dnW;
}

/**
 * 副图 series 句柄；indicators=false 或两个副图都关时压根不建，整个为 null。
 * MACD/RSI 各可单独关：关掉的那组 series 不建（hist/dif/dea 为空、rsi 为空数组），
 * pane 序号动态分配（macdPane/rsiPane，-1 = 未开）——只开 RSI 时它就在 pane 1。
 */
interface IndSeries {
  hist?: ISeriesApi<'Histogram'>;
  dif?: ISeriesApi<'Line'>;
  dea?: ISeriesApi<'Line'>;
  rsi: ISeriesApi<'Line'>[];                 // 与 RSI_PERIODS 同序；RSI 关闭时为空
  /** 最新一根的值，鼠标没悬停时读数常驻显示这个 */
  last: { dif?: number; dea?: number; hist?: number; rsi: (number | undefined)[] };
}

/** null 段跳过不画（预热期指标无值），返回 LWC 要的点数组 */
const toLine = (bars: Bar[], s: (number | null)[]) =>
  bars.flatMap((b, i) => s[i] === null ? [] : [{ time: b.time as UTCTimestamp, value: s[i] as number }]);

/**
 * 手机竖屏（图表宽 < 380）走紧凑读数：砍掉指标参数前缀。
 * 按图表实际宽度判断而不是视口——同一台机器横屏、或 PC 上窗口拖窄，都该跟着缩。
 */
const isCompact = (chart: IChartApi) => chart.options().width < 380;

// ========== 主图叠加指标 (MA / EMA / BOLL) ==========

/** MA 和 EMA 共用这组周期 */
const MA_PERIODS = [7, 25, 99] as const;
const BOLL_PERIOD = 20, BOLL_MULT = 2;
const BOLL_LABELS = ['UP', 'MID', 'LOW'];

export type OverlayKey = 'ma' | 'ema' | 'boll';

interface OverlaySeries {
  ma: ISeriesApi<'Line'>[];
  ema: ISeriesApi<'Line'>[];
  boll: ISeriesApi<'Line'>[];                // [upper, mid, lower]
  /** 最新一根的值，没悬停时读数显示它 */
  last: { ma: (number | undefined)[]; ema: (number | undefined)[]; boll: (number | undefined)[] };
}

const lastOf = (s: (number | null)[]) => {
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
  ov.last = { ma: c.ma.map(lastOf), ema: c.ema.map(lastOf), boll: c.boll.map(lastOf) };
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

/** 历史全量灌入副图（关掉的那组没有 series，跳过） */
function setIndicators(ind: IndSeries, bars: Bar[], hc: HistColors) {
  const c = computeAll(bars);
  if (ind.hist) {
    ind.hist.setData(bars.flatMap((b, i) => c.hist[i] === null ? []
      : [{
        time: b.time as UTCTimestamp,
        value: c.hist[i] as number,
        color: histColor(c.hist[i] as number, i > 0 ? c.hist[i - 1] : null, hc),
      }]));
  }
  ind.dif?.setData(toLine(bars, c.dif));
  ind.dea?.setData(toLine(bars, c.dea));
  ind.rsi.forEach((s, k) => s.setData(toLine(bars, c.rsis[k])));
  cacheLast(ind, c);
}

/**
 * 只刷新最后一根的指标值。
 * 未收盘那根 close 在变，但 EMA/RSI 都是从历史向前递推、前面的点早已定型，
 * close 变化只波及递推链末端那一个值 —— 所以全量重算后只 update 末点是精确的，不是近似。
 * (500 根纯算术 <1ms；不做增量状态维护，那反而在同一根 bar 被反复重写时会算错。)
 */
function updateIndicatorsLast(ind: IndSeries, bars: Bar[], hc: HistColors) {
  const n = bars.length - 1;
  if (n < 0) return;
  const c = computeAll(bars);
  const time = bars[n].time as UTCTimestamp;
  if (ind.hist && c.hist[n] !== null) {
    ind.hist.update({
      time, value: c.hist[n] as number,
      color: histColor(c.hist[n] as number, n > 0 ? c.hist[n - 1] : null, hc),
    });
  }
  if (ind.dif && c.dif[n] !== null) ind.dif.update({ time, value: c.dif[n] as number });
  if (ind.dea && c.dea[n] !== null) ind.dea.update({ time, value: c.dea[n] as number });
  ind.rsi.forEach((s, k) => { if (c.rsis[k][n] !== null) s.update({ time, value: c.rsis[k][n] as number }); });
  cacheLast(ind, c);
}

/**
 * 实时蜡烛图 + 成交量柱 + 图内读数。
 * 历史走 REST(含量/额)；当前根两种驱动二选一：
 * - streamLive=true（默认，合约）：走 {@link useKlineStream}(后端实时广播 o/h/l/c/v/q)
 * - streamLive=false（现货/bstock，后端不广播其K线）：由外部 tick(价格流)更新最后一根的 c/h/l
 */
// 桶宽即对齐口径：floor(ts/bucket)*bucket 落到 UTC 整点，与币安各周期的开盘时刻一致
// （4h→UTC 00/04/08/12/16/20，1d→UTC 00:00），所以 tick 驱动落桶不会错位。
const BUCKET_MS = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '4h': 14_400_000, '1d': 86_400_000 } as const;
type Interval = keyof typeof BUCKET_MS;

/**
 * 一个当前仓位要画到图上的全部参考价：入场 / 多档止盈 / 多档止损 / 强平。
 * 页面把仓位映射成这个通用结构传进来，图表不认识业务实体 —— bstock 页没有合约也就不传。
 */
export interface PositionOverlay {
  id: number;
  /** 线标签前缀，如 "多 10x" / "空 25x"（双向持仓同 symbol 至多一多一空，天然不重名） */
  label: string;
  side: 'LONG' | 'SHORT';
  entry: number;
  tps: number[];
  sls: number[];
  /** 全仓的强平价是账户级动态估算，可能给不出 → null 不画 */
  liq: number | null;
}

/** 一笔历史成交要打到图上的信息。B=买入方向（开多/平空），S=卖出方向（开空/平多） */
export interface TradeMark {
  timeMs: number;
  side: 'B' | 'S';
  price: number;
  quantity: number;
}

/** 落进同一根 K 线的一笔成交，点开角标弹窗时按时刻+价格逐笔列 */
type Fill = { timeMs: number; price: number; quantity: number };

// ========== 向左翻历史的三个阈值 ==========
/** 每次往回翻的根数，与首屏同量级 */
const PAGE_SIZE = 500;
/** 左边还剩这么多根就预取。默认视口 110 根≈留一屏缓冲，让加载在用户拖到墙之前就完成 */
const LOAD_THRESHOLD = 100;
/**
 * 内存上限。每次实时 tick 都要对全量 bars 重算 11 条指标序列
 * （见 updateIndicatorsLast 的注释：故意不做增量，否则同一根被反复重写时会算错），
 * 5000 根≈2-5ms/tick 还无感，上万就开始掉帧。
 * 覆盖范围：5m≈17天 / 15m≈52天 / 1h≈208天 / 4h≈2.3年 / 1d≈13.7年。
 */
const MAX_BARS = 5000;

/**
 * 手机纵向滑动交还给页面滚动（否则想下滑页面却在拖图表）；横向平移/捏合缩放保留。
 * 提到模块级：画线拖拽期间要临时关掉 handleScroll，松手后原样恢复这一份。
 */
const SCROLL_OPTS: DeepPartial<HandleScrollOptions> =
  { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false };

/** 触屏设备判定：竖屏全屏的"转横屏"提示只该出现在真能转的设备上（桌面竖屏显示器转不了） */
const IS_TOUCH = window.matchMedia('(pointer: coarse)').matches;

/** 快讯是外部内容，进 innerHTML 前必须转义（标题/正文/URL 都不可信） */
const esc = (s: string) => s.replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string));

/** 贴价格轴的仓位小签：挨得近的往下推，不叠在一起 */
const LABEL_GAP = 16;

/** 日历角标配色：纸底、灰边灰图标 */
const econPalette = () => {
  const th = lwcTheme();
  return { fg: th.mute, border: rgba(th.mute, .45), bg: th.bg };
};

export interface CandleChartProps {
  symbol: string;
  interval: Interval;
  limit?: number;
  visibleBars?: number;
  klinesFn?: (symbol: string, interval: string, limit: number, endTime?: number) => Promise<number[][]>;
  /** 往左翻历史。游客传 false：后端带 endTime 的请求要登录，拖到最左只提示不发请求 */
  loadHistory?: boolean;
  streamLive?: boolean;
  tick?: { price: number; ts: number } | null;
  indicators?: boolean;
  /** 周期 seg 住在图表顶栏，切换走这个回调改 props —— 组件不卸载，全屏/缩放状态不丢 */
  onIntervalChange: (i: Interval) => void;
  positionOverlays?: PositionOverlay[];
  tradeMarks?: TradeMark[];
  /** 财经日历标记：只有 BTC 传 true，其他标的不挂 */
  econMarks?: boolean;
  /** 「高级」档的内容（TradingView）：传了才多出这颗按钮；选中时盖住 plot，图表实例不卸载 */
  advanced?: ReactNode;
  /** 图内读数第一行的灰字，如 'BINANCE 永续' / 'BINANCE 现货' */
  marketLabel?: string;
}

export function CandleChart({
  symbol, interval, limit = 300, visibleBars = 110, klinesFn = futuresApi.klines, loadHistory = true, streamLive = true,
  tick = null, indicators = false, onIntervalChange, positionOverlays, tradeMarks, econMarks,
  advanced, marketLabel = 'BINANCE',
}: CandleChartProps) {
  const { t } = useTranslation('market');
  // 日历弹窗的标签跟界面语言，切语言重建标记
  const uiLang = currentLang();
  const isDark = useIsDark();
  const rootRef = useRef<HTMLDivElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartDivRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const areaRef = useRef<ISeriesApi<'Area'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const indRef = useRef<IndSeries | null>(null);
  const ovRef = useRef<OverlaySeries | null>(null);
  /** 建图时装配好的读数刷新函数：实时 tick 也要调它，所以挂 ref 出来 */
  const legendsRef = useRef<((p: MouseEventParams | null) => void) | null>(null);
  /** 同上：最后一根的原地更新，两条实时通路（广播/价格流）共用建图时那份闭包 */
  const paintLastRef = useRef<((bar: Bar) => void) | null>(null);
  /** 主题切换时重新取一遍 token 铺到图表和各 series 上 */
  const applyThemeRef = useRef<(() => void) | null>(null);
  // 默认全关走裸K：三组九条线画满会糊成一团，要看哪组从「指标」弹层里开
  const [overlays, setOverlays] = useState<Record<OverlayKey, boolean>>({ ma: false, ema: false, boll: false });
  const overlaysRef = useRef(overlays);
  const barsRef = useRef<Bar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const readyRef = useRef(false);
  const hintRef = useRef<HTMLDivElement>(null);
  // 翻历史的两道闸：in-flight 锁挡住 setData 自己触发的那次 range 变化（漏了就是无限自激狂发请求），
  // 枯竭标记挡住"币安没有更早数据了还一直问"
  const loadingRef = useRef(false);
  const exhaustedRef = useRef(false);
  /** 十字线当前压在哪根：实时 tick 只在没悬停时刷读数，否则会把用户正盯着的那根冲掉 */
  const hoverTimeRef = useRef<number | null>(null);

  // 仓位参考线：总开关记 localStorage（跨会话保持），单仓位显隐是会话内临时选择不落盘
  const [showPosLines, setShowPosLines] = useState(() => localStorage.getItem('wiib-chart-pos-lines') !== '0');
  const [hiddenPosIds, setHiddenPosIds] = useState<ReadonlySet<number>>(new Set());

  // 副图开关：MACD/RSI 各自可关。关的那组压根不建 series/pane（省算力也省高度），
  // 切换走建图 effect 重建 —— 与切周期同一条路径，不为省一次重绘再造第二套增删 pane 逻辑
  const [subs, setSubs] = useState(() => ({
    macd: localStorage.getItem('wiib-chart-sub-macd') !== '0',
    rsi: localStorage.getItem('wiib-chart-sub-rsi') !== '0',
  }));
  // 图型：蜡烛 / 折线（折线是一条 AreaSeries，与蜡烛同数据源，切换只切 visible）
  const [chartType, setChartType] = useState<'candle' | 'line'>(
    () => localStorage.getItem('wiib-chart-type') === 'line' ? 'line' : 'candle');
  const chartTypeRef = useRef(chartType);
  /** 「高级」档：plot 区盖上外部传来的 TradingView，图表实例留在下面不卸载 */
  const [advMode, setAdvMode] = useState(false);
  const [indOpen, setIndOpen] = useState(false);
  const indPopRef = useRef<HTMLDivElement>(null);

  // 历史成交 B/S 标记：默认关（打开一次记住）。marksByTimeRef 供点击弹窗按 bar 查成交
  const [showMarks, setShowMarks] = useState(() => localStorage.getItem('wiib-chart-trade-marks') === '1');
  const marksByTimeRef = useRef<Map<number, { b: Fill[]; s: Fill[] }>>(new Map());
  const markTipRef = useRef<HTMLDivElement>(null);
  // 财经日历标记：默认开（关一次记住）。画在主图画布上（EconMarkersLayer），随蜡烛同帧移动
  const [showEcon, setShowEcon] = useState(() => localStorage.getItem('wiib-chart-econ') !== '0');
  const econLayerRef = useRef<EconMarkersLayer | null>(null);
  const econTipRef = useRef<HTMLDivElement>(null);
  const cdRef = useRef<HTMLDivElement>(null);
  /** 仓位参考线的贴轴小签，由 250ms 循环随缩放平移重新定位 */
  const posLabelElsRef = useRef<{ el: HTMLDivElement; price: number }[]>([]);
  const decimals = getCoinPriceDecimals(symbol);
  const base = symbol.replace('USDT', '');

  const live = useKlineStream(symbol, interval);
  const fs = useFullscreen(rootRef);
  const {
    attach: attachDrawings, tool, setTool, magnet, setMagnet, hiddenAll, setHiddenAll,
    selected: hasSelection, count: drawCount, trash, textEdit, commitText, cancelText,
  } = useDrawings();

  /**
   * 图表实例代号：与建图 effect 同一组依赖，任一项变就换个新对象。
   * 参考线/成交标记/快讯这些挂在蜡烛 series 上的 effect 认它重跑——
   * 图一重建 series 就随旧图死了，不重跑的话切周期后线全丢。
   */
  const chartEpoch = useMemo(
    () => ({ symbol, interval, limit, visibleBars, decimals, klinesFn, indicators, subs }),
    [symbol, interval, limit, visibleBars, decimals, klinesFn, indicators, subs],
  );

  // 竖屏全屏会把 K 线纵向拉成细长条（画布 ~390×800，价格轴自动铺满高度）。
  // Android 在 useFullscreen 里直接锁横屏；iOS 没有 lock API，只能提示用户自己转 ——
  // matchMedia 自带监听，转过去提示自动消失，图表随既有的 ResizeObserver 重排
  const [portrait, setPortrait] = useState(() => window.matchMedia('(orientation: portrait)').matches);
  useEffect(() => {
    const mq = window.matchMedia('(orientation: portrait)');
    const onChange = (e: MediaQueryListEvent) => setPortrait(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // 指标弹层：点外面 / Esc 关
  useClickOutside(indPopRef, () => setIndOpen(false), indOpen);
  useEffect(() => {
    if (!indOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setIndOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [indOpen]);

  // 建图 + 拉历史（symbol/interval/副图开关等变则重建）
  useEffect(() => {
    const host = chartDivRef.current;
    if (!host) return;
    // 异步回调回来时图可能已被 cleanup 销毁（切了 symbol/interval），对死图 setData 会抛
    let disposed = false;
    const th = lwcTheme();
    // 量柱和 MACD 柱是逐点着色，颜色随主题变；放一份可变调色板给它们读，切主题时改这里再重灌
    const pal = { gain: th.gain, loss: th.loss };
    const histColors: HistColors = {
      upS: rgba(th.gain, .9), upW: rgba(th.gain, .32), dnS: rgba(th.loss, .9), dnW: rgba(th.loss, .32),
    };
    const volColor = (up: boolean) => rgba(up ? pal.gain : pal.loss, .4);

    const chart = createChart(host, {
      ...th.options,
      width: host.clientWidth, height: host.clientHeight,
      handleScroll: SCROLL_OPTS,
      // 顶上 6% 留给读数，底下 26% 让给量柱
      rightPriceScale: { ...th.options.rightPriceScale, scaleMargins: { top: 0.06, bottom: 0.26 } },
      timeScale: { ...th.options.timeScale, timeVisible: true, secondsVisible: false, rightOffset: 5 },
    });
    chartRef.current = chart;

    const isLine = chartTypeRef.current === 'line';
    const candle = chart.addSeries(CandlestickSeries, {
      ...th.candle,
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
      // 轴上的最新价标签关掉：由 cdRef 那个"价格+倒计时"墨块顶替；虚线最新价线留着
      lastValueVisible: false, priceLineColor: th.fg, priceLineWidth: 1, priceLineStyle: LineStyle.SparseDotted,
      visible: !isLine,
    });
    candleRef.current = candle;

    // 折线档：一条面积图，数据与蜡烛同源。蜡烛藏起来时挂在它上面的画线照样画
    // （primitive 不吃 series.visible），所以切图型只切 visible，画线层原地不动
    const area = chart.addSeries(AreaSeries, {
      // LWC 的 lineWidth 只收 1|2|3|4 整数档，海报上那条 1.5 只能取 2
      lineColor: th.fg, lineWidth: 2, topColor: rgba(th.fg, .08), bottomColor: 'transparent',
      lastValueVisible: false, priceLineColor: th.fg, priceLineWidth: 1, priceLineStyle: LineStyle.SparseDotted,
      crosshairMarkerVisible: false,
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
      visible: isLine,
    });
    areaRef.current = area;

    const vol = chart.addSeries(HistogramSeries, { priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false });
    vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });   // 量柱压底部 18%
    volRef.current = vol;

    // 画线图层。挂蜡烛 series 而不是 pane —— 只有 series primitive 有
    // priceAxisViews/timeAxisViews，水平线的价格轴标签、趋势线端点的时间标签白拿。
    // attach/detach 必须在本 effect 内成对做：若另开同依赖的 effect，React 会先跑
    // 这里的 cleanup(chart.remove())、再跑那边的，那时 series 已死，detachPrimitive 要炸。
    // bars/idx 走 getter：图层活得比任何一帧都久，实时 tick 和翻历史都会换掉 ref 里的数组。
    const detachDrawings = attachDrawings({
      chart, series: candle, host, symbol, decimals, scrollOpts: SCROLL_OPTS,
      ctx: {
        bars: () => barsRef.current,
        idx: () => idxRef.current,
        bucketSec: BUCKET_MS[interval] / 1000,
        timeScale: chart.timeScale(),
        series: candle,
      } satisfies ChartCtx,
      fmtTime: t => fmtBarTime(barDate(t), interval),
    });

    // 主图叠加：MA / EMA / BOLL 都挂 pane 0，跟蜡烛共用价格轴。
    // 三组一律建出来，显不显示走 visible，切换时不用重建 series 重灌数据。
    // MA 实线、EMA 同色虚线（两组同开靠虚实分），BOLL 灰色中轨实线上下轨虚线
    if (indicators) {
      const on = overlaysRef.current;
      const thin = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false };
      ovRef.current = {
        ma: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, { ...thin, color: th.col3[k], visible: on.ma })),
        ema: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, {
          ...thin, color: th.col3[k], lineStyle: LineStyle.Dashed, visible: on.ema,
        })),
        boll: [0, 1, 2].map(k => chart.addSeries(LineSeries, {
          ...thin, color: th.mute,
          lineStyle: k === 1 ? LineStyle.Solid : LineStyle.Dashed,
          visible: on.boll,
        })),
        last: { ma: [], ema: [], boll: [] },
      };
    }

    // 副图：MACD/RSI 各自可开关，pane 序号动态分配（addSeries 第三参数就是 pane 序号，
    // v5 自带时间轴/十字线联动）。只开 RSI 时它顶到 pane 1，不给关掉的 MACD 留空档
    let macdPane = -1, rsiPane = -1;
    if (indicators && (subs.macd || subs.rsi)) {
      const line = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false };
      let paneIdx = 1;
      macdPane = subs.macd ? paneIdx++ : -1;
      rsiPane = subs.rsi ? paneIdx++ : -1;

      const hist = subs.macd
        ? chart.addSeries(HistogramSeries, { priceLineVisible: false, lastValueVisible: false }, macdPane) : undefined;
      const dif = subs.macd ? chart.addSeries(LineSeries, { ...line, color: th.col3[0] }, macdPane) : undefined;
      const dea = subs.macd ? chart.addSeries(LineSeries, { ...line, color: th.col3[1] }, macdPane) : undefined;
      // RSI 天然 0-100，锁死纵轴免得自适应缩放把 70/30 线挤出视野
      const rsi = subs.rsi ? RSI_PERIODS.map((_, k) => chart.addSeries(LineSeries, {
        ...line, color: th.col3[k],
        autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }),
      }, rsiPane)) : [];
      if (rsi.length) {
        for (const price of [70, 30]) {
          rsi[0].createPriceLine({ price, color: rgba(th.mute, .5), lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: false, title: '' });
        }
      }

      const panes = chart.panes();
      panes[0].setStretchFactor(3);      // 主图:每个副图 = 3:1
      // 顶层 rightPriceScale 的 scaleMargins(bottom .26) 是给主图量柱留的，会连累副图；这里按 pane 覆盖掉
      if (macdPane >= 0) {
        panes[macdPane].setStretchFactor(1);
        panes[macdPane].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.12 } });  // top 留给读数
      }
      if (rsiPane >= 0) {
        panes[rsiPane].setStretchFactor(1);
        panes[rsiPane].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.08 } });
      }
      indRef.current = { hist, dif, dea, rsi, last: { rsi: [] } };
    }

    // ---------- 图内读数 ----------
    // pane 的 DOM 是渲染流程里才建的，addSeries 之后立刻 getHTMLElement() 还是 null，
    // 所以每次刷读数时补一次；第一次拉到数据时图表早已 paint 过，必然建得上
    const els: LegendEls = { main: null, vol: null, macd: null, rsi: null };
    const ensureLegends = () => {
      const panes = chart.panes();
      const p0 = panes[0]?.getHTMLElement() ?? null;
      if (!els.main) els.main = makeLegend(p0, '6px');
      // 量柱占 pane 底部 18%，读数就压在它顶上
      if (!els.vol) els.vol = makeLegend(p0, 'calc(82% + 2px)');
      if (macdPane >= 0 && !els.macd) els.macd = makeLegend(panes[macdPane]?.getHTMLElement() ?? null, '4px');
      if (rsiPane >= 0 && !els.rsi) els.rsi = makeLegend(panes[rsiPane]?.getHTMLElement() ?? null, '4px');
    };

    /** 悬停时显示十字线那根，不悬停回落到最新一根 */
    const renderLegends = (param: MouseEventParams | null) => {
      ensureLegends();
      const bars = barsRef.current;
      if (!bars.length || !els.main) return;
      const time = param?.time as number | undefined;
      const hovering = time != null;
      const i = (hovering ? idxRef.current.get(time) : undefined) ?? bars.length - 1;
      const bar = bars[i], prev = bars[i - 1] ?? bar;
      const compact = isCompact(chart);
      const pick = (s: ISeriesApi<'Line'> | ISeriesApi<'Histogram'> | undefined, fb: number | undefined) => {
        if (!s) return undefined;
        if (!hovering) return fb;
        const d = param?.seriesData.get(s);
        return d && 'value' in d ? (d.value as number) : undefined;
      };
      const nv = (v: number | undefined, digits = decimals) => v === undefined ? '--' : fmtNum(v, digits);
      const sv = (v: number | undefined) => v === undefined ? '--' : (v >= 0 ? '+' : '') + fmtNum(v, decimals);

      // 行1 币对 + 周期/市场；行2 开高低收 + 涨跌（整行按当根涨跌上色）；行3 开着的叠加指标
      const d = bar.close - prev.close, up = d >= 0, sg = up ? '+' : '';
      const pct = prev.close ? d / prev.close * 100 : 0;
      const ov = ovRef.current, on = overlaysRef.current;
      let row3 = '';
      if (ov) {
        if (on.ma) row3 += MA_PERIODS.map((p, k) => cell(`MA${p}`, nv(pick(ov.ma[k], ov.last.ma[k])), LG_COL3[k])).join('');
        if (on.ema) row3 += MA_PERIODS.map((p, k) => cell(`EMA${p}`, nv(pick(ov.ema[k], ov.last.ema[k])), LG_COL3[k])).join('');
        if (on.boll) row3 += BOLL_LABELS.map((n, k) => cell(`BOLL ${n}`, nv(pick(ov.boll[k], ov.last.boll[k])), 'var(--color-muted-foreground)')).join('');
      }
      els.main.innerHTML =
        `<div><b style="font-weight:800;font-size:12.5px">${symbol}</b> <span class="mute">${interval} · ${marketLabel}</span></div>`
        + `<div class="num ${up ? 'up' : 'dn'}">`
        + cell(i18n.t('market:chart.open'), fmtNum(bar.open, decimals))
        + cell(i18n.t('market:chart.high'), fmtNum(bar.high, decimals))
        + cell(i18n.t('market:chart.low'), fmtNum(bar.low, decimals))
        + cell(i18n.t('market:chart.close'), fmtNum(bar.close, decimals))
        + `<span style="${LG_SP}">${sg}${fmtNum(d, decimals)} (${sg}${pct.toFixed(2)}%)</span></div>`
        + (row3 ? `<div class="num">${row3}</div>` : '');

      if (els.vol) {
        els.vol.innerHTML = `<span class="num" style="${LG_SP}"><i class="mute" style="${LG_LABEL}">${i18n.t('market:chart.vol')}</i>`
          + `<b class="${bar.close >= bar.open ? 'up' : 'dn'}">${fmtVol(bar.volume)}</b> ${base}</span>`;
      }

      const ind = indRef.current;
      if (els.macd && ind?.hist && ind.dif && ind.dea) {
        const h = pick(ind.hist, ind.last.hist);
        // 窄屏砍掉参数前缀：那截占 88px，手机上留着会把 MACD 值挤出可视区（nowrap 直接裁掉）
        els.macd.innerHTML =
          (compact ? '' : `<span class="mute" style="${LG_SP}"><i style="${LG_LABEL}">MACD</i>12 26 9</span>`)
          + `<span class="num">${cell('DIF', sv(pick(ind.dif, ind.last.dif)), LG_COL3[0])}`
          + cell('DEA', sv(pick(ind.dea, ind.last.dea)), LG_COL3[1]) + '</span>'
          + `<span class="num ${h !== undefined && h < 0 ? 'dn' : 'up'}">${cell('MACD', sv(h))}</span>`;
      }
      if (els.rsi && ind?.rsi.length) {
        els.rsi.innerHTML =
          (compact ? '' : `<span class="mute" style="${LG_SP}"><i style="${LG_LABEL}">RSI</i>6 12 24</span>`)
          + `<span class="num">${RSI_PERIODS.map((p, k) => cell(String(p), nv(pick(ind.rsi[k], ind.last.rsi[k]), 2), LG_COL3[k])).join('')}</span>`;
      }
    };
    legendsRef.current = renderLegends;

    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      hoverTimeRef.current = (param.time as number | undefined) ?? null;
      renderLegends(param);
    });

    // 点击带 B/S 角标的那根 K 线 → 弹出该根内的逐笔成交价；点空白处收起。
    // 按 bar 的时间桶查而不是抠标记的像素命中 —— 点中蜡烛任意位置都算，手机上尤其重要
    chart.subscribeClick((param: MouseEventParams) => {
      const tipEl = markTipRef.current; if (!tipEl) return;
      const time = param.time as number | undefined;
      const g = time != null ? marksByTimeRef.current.get(time) : undefined;
      if (!g || !param.point) { tipEl.style.display = 'none'; return; }
      const row = (side: string, cls: string, fills: Fill[]) => fills.map(f =>
        `<div style="display:flex;gap:10px;align-items:baseline"><span class="${cls}" style="font-weight:700">${side}</span>`
        + `<span class="mute num" style="margin-left:auto">${fmtFillTime(f.timeMs)}</span>`
        + `<span class="num">${f.quantity}</span>`
        + `<span class="num" style="font-weight:700">${fmtNum(f.price, decimals)}</span></div>`).join('');
      // 弹窗每次点击现拼，词表走 i18n 实例（建图 effect 不该因为切语言整个重建）
      tipEl.innerHTML =
        `<div class="mute" style="font-weight:700;margin-bottom:4px;padding-bottom:4px;border-bottom:1px solid var(--color-border)">${i18n.t('market:chart.fillsAt', { time: fmtBarTime(barDate(time as number), interval) })}</div>`
        + row(i18n.t('market:chart.buy'), 'up', g.b) + row(i18n.t('market:chart.sell'), 'dn', g.s);
      tipEl.style.left = `${Math.min(param.point.x + 12, host.clientWidth - 180)}px`;
      tipEl.style.top = `${Math.min(param.point.y + 12, host.clientHeight - 30 * (g.b.length + g.s.length) - 40)}px`;
      tipEl.style.display = 'block';
    });

    /** 全量重灌蜡烛+折线+量柱+指标。首屏和前插历史共用——LWC 只能 append 不能 prepend，前插只能整条重灌 */
    const paintAll = (bars: Bar[]) => {
      candle.setData(bars.map(b => ({ time: b.time as UTCTimestamp, open: b.open, high: b.high, low: b.low, close: b.close })));
      area.setData(bars.map(b => ({ time: b.time as UTCTimestamp, value: b.close })));
      vol.setData(bars.map(b => ({ time: b.time as UTCTimestamp, value: b.volume, color: volColor(b.close >= b.open) })));
      // 指标必须全量重算：MA/BOLL 是滑动窗口老值不变，但 EMA/RSI/MACD 是从最早那根递推的，
      // 前面接上历史后种子位置变了，接缝往后几百根的值都会跟着变（再远指数衰减到看不见）
      if (indRef.current) setIndicators(indRef.current, bars, histColors);
      if (ovRef.current) setOverlayData(ovRef.current, bars);
      renderLegends(null);
    };

    /** 最后一根原地更新：蜡烛/折线/量柱各刷一点，指标只重算末端；没悬停才刷读数 */
    paintLastRef.current = (bar: Bar) => {
      const time = bar.time as UTCTimestamp;
      candle.update({ time, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
      area.update({ time, value: bar.close });
      vol.update({ time, value: bar.volume, color: volColor(bar.close >= bar.open) });
      if (indRef.current) updateIndicatorsLast(indRef.current, barsRef.current, histColors);
      if (ovRef.current) updateOverlayLast(ovRef.current, barsRef.current);
      // 没悬停才刷读数，否则会把用户正盯着的那根值冲掉
      if (hoverTimeRef.current === null) renderLegends(null);
    };

    // ---------- 主题重铺 ----------
    // 只改颜色不重建：图表选项 + 各 series 的线色；逐点着色的量柱/MACD 柱换完调色板重灌一次数据
    applyThemeRef.current = () => {
      const n = lwcTheme();
      chart.applyOptions(n.options);
      candle.applyOptions({ ...n.candle, priceLineColor: n.fg });
      area.applyOptions({ lineColor: n.fg, topColor: rgba(n.fg, .08), priceLineColor: n.fg });
      const o = ovRef.current;
      o?.ma.forEach((s, k) => s.applyOptions({ color: n.col3[k] }));
      o?.ema.forEach((s, k) => s.applyOptions({ color: n.col3[k] }));
      o?.boll.forEach(s => s.applyOptions({ color: n.mute }));
      const ind = indRef.current;
      ind?.dif?.applyOptions({ color: n.col3[0] });
      ind?.dea?.applyOptions({ color: n.col3[1] });
      ind?.rsi.forEach((s, k) => s.applyOptions({ color: n.col3[k] }));
      pal.gain = n.gain; pal.loss = n.loss;
      histColors.upS = rgba(n.gain, .9); histColors.upW = rgba(n.gain, .32);
      histColors.dnS = rgba(n.loss, .9); histColors.dnW = rgba(n.loss, .32);
      if (barsRef.current.length) paintAll(barsRef.current);
    };

    // ========== 向左翻历史 ==========
    // 提示走 DOM 直改：走 setState 会把整个图表子树连带重渲染
    let hintTimer: ReturnType<typeof setTimeout> | undefined;
    const showHint = (text: string, autoHideMs = 2000) => {
      const el = hintRef.current; if (!el) return;
      clearTimeout(hintTimer);
      el.textContent = text;
      el.style.display = 'block';
      if (autoHideMs) hintTimer = setTimeout(() => { el.style.display = 'none'; }, autoHideMs);
    };
    const hideHint = () => { clearTimeout(hintTimer); if (hintRef.current) hintRef.current.style.display = 'none'; };

    const loadMore = () => {
      if (loadingRef.current || exhaustedRef.current || !readyRef.current || !barsRef.current.length) return;
      if (!loadHistory) { exhaustedRef.current = true; showHint(i18n.t('market:chart.loginForHistory')); return; }
      if (barsRef.current.length >= MAX_BARS) { exhaustedRef.current = true; showHint(i18n.t('market:chart.limitReached')); return; }

      loadingRef.current = true;
      showHint(i18n.t('market:chart.loadingHistory'), 0);
      // endTime = 现有最早那根开盘前 1ms；后端按 endTime 缓存 1h（闭合 bar 不可变），多人翻同一页共享同一个 key
      klinesFn(symbol, interval, PAGE_SIZE, barsRef.current[0].openMs - 1).then(raw => {
        if (disposed) return;
        // 去重：币安边界可能回一根重叠的，LWC 遇到重复时间会抛
        const oldest = barsRef.current[0].time;
        const older = raw.map(toBar).filter(b => b.time < oldest);
        if (!older.length) { exhaustedRef.current = true; showHint(i18n.t('market:chart.earliest')); return; }

        const merged = older.concat(barsRef.current);
        const idx = new Map<number, number>();
        merged.forEach((b, i) => idx.set(b.time, i));
        barsRef.current = merged; idxRef.current = idx;

        // 重灌把 logical index 整体右移了 older.length 格，视口不补回去画面就弹走那么多根
        const before = chart.timeScale().getVisibleLogicalRange();
        paintAll(merged);
        if (before) {
          chart.timeScale().setVisibleLogicalRange({ from: before.from + older.length, to: before.to + older.length });
        }
        hideHint();
      }).catch(() => { if (!disposed) hideHint(); })
        // disposed 时新一轮 effect 已经重置过锁了，这里别再动，否则会把新请求的锁误清
        .finally(() => { if (!disposed) loadingRef.current = false; });
    };

    chart.timeScale().subscribeVisibleLogicalRangeChange(r => {
      if (r && r.from < LOAD_THRESHOLD) loadMore();
    });

    readyRef.current = false;
    loadingRef.current = false;
    exhaustedRef.current = false;
    klinesFn(symbol, interval, limit).then(raw => {
      if (disposed) return;
      const bars = raw.map(toBar);
      const idx = new Map<number, number>();
      bars.forEach((b, i) => idx.set(b.time, i));
      barsRef.current = bars; idxRef.current = idx;
      paintAll(bars);
      // 默认只看最近 visibleBars 根（fitContent 会把全量挤进视口，蜡烛小成一条线）；往左拖/缩放仍可看全历史
      if (bars.length > visibleBars) {
        chart.timeScale().setVisibleLogicalRange({ from: bars.length - visibleBars, to: bars.length + 5 });
      } else {
        chart.timeScale().fitContent();
      }
      exhaustedRef.current = raw.length < limit;   // 首屏就没拉满 = 币安只有这么多，别再往回问
      readyRef.current = true;
    }).catch(() => { /* 历史失败仍可靠实时累积 */ });

    const ro = new ResizeObserver(() => {
      chart.applyOptions({ width: host.clientWidth, height: host.clientHeight });
      // 旋屏/拖窗口会让 compact 判定翻转，读数得跟着重排，否则要等下一个 tick 才变
      renderLegends(null);
    });
    ro.observe(host);

    return () => {
      // hint 是 JSX 节点、不随图表销毁重建：切 symbol/interval 时若正挂着"载入历史…"，
      // 在飞的请求会因 disposed 直接 return 而走不到 hideHint，不在这里收就永远留在新图上
      disposed = true; hideHint();
      detachDrawings();                    // 必须赶在 chart.remove() 前面
      ro.disconnect(); chart.remove();
      chartRef.current = null; candleRef.current = null; areaRef.current = null; volRef.current = null;
      indRef.current = null; ovRef.current = null;
      legendsRef.current = null; paintLastRef.current = null; applyThemeRef.current = null;
      readyRef.current = false; barsRef.current = []; idxRef.current = new Map();
      loadingRef.current = false; exhaustedRef.current = false;
    };
  }, [symbol, interval, limit, visibleBars, decimals, klinesFn, loadHistory, indicators, subs, marketLabel, base, attachDrawings]);

  // 图型切换：只切 visible，两条 series 的数据一直同步喂着。
  // 蜡烛藏起来后挂在它身上的画线照画（primitive 不吃 series.visible），画线层原地不动
  useEffect(() => {
    chartTypeRef.current = chartType;
    const candle = candleRef.current, area = areaRef.current;
    if (!candle || !area) return;
    const line = chartType === 'line';
    candle.applyOptions({ visible: !line });
    area.applyOptions({ visible: line });
  }, [chartType, chartEpoch]);

  // 指标开关：只切 visible，不重建 series；切完立刻刷读数（展开的组要马上有值）
  useEffect(() => {
    overlaysRef.current = overlays;
    const ov = ovRef.current; if (!ov) return;
    ov.ma.forEach(s => s.applyOptions({ visible: overlays.ma }));
    ov.ema.forEach(s => s.applyOptions({ visible: overlays.ema }));
    ov.boll.forEach(s => s.applyOptions({ visible: overlays.boll }));
    legendsRef.current?.(null);
  }, [overlays]);

  // 仓位参考线：入场墨色、止损跌色、止盈涨色、强平警示色，一律 4/4 虚线。
  // 信息写在线上不落 y 轴（轴标签一多互相盖）：每条线配一个贴价格轴的小签，定位由 250ms 循环维护。
  // 线挂在"当前可见"的那条 series 上 —— LWC 的 createPriceLine 认 series.visible，
  // 折线档蜡烛是隐形的，线得改挂面积图那条，否则整组参考线跟着消失
  useEffect(() => {
    const series = chartType === 'line' ? areaRef.current : candleRef.current;
    const wrap = wrapRef.current;
    if (!series || !wrap || !showPosLines || !positionOverlays?.length) return;
    const th = lwcTheme();
    const lines: IPriceLine[] = [];
    const labels: { el: HTMLDivElement; price: number }[] = [];
    for (const p of positionOverlays) {
      if (hiddenPosIds.has(p.id)) continue;
      const add = (price: number | null | undefined, title: string, color: string) => {
        if (price == null || !(price > 0)) return;
        lines.push(series.createPriceLine({ price, color, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: false, title: '' }));
        const el = document.createElement('div');
        el.textContent = `${title} ${fmtNum(price, decimals)}`;
        el.className = 'num';
        el.style.cssText = 'position:absolute;display:none;transform:translateY(-50%);z-index:4;pointer-events:none;'
          + 'padding:0 5px;font-size:11px;font-weight:700;line-height:1.5;white-space:nowrap;'
          + `background:var(--color-background);border:1px solid currentColor;color:${color}`;
        wrap.appendChild(el);
        labels.push({ el, price });
      };
      add(p.entry, `${p.label} ${t('chart.entry')}`, th.fg);
      p.tps.forEach((tp, i) => add(tp, `${p.label} TP${p.tps.length > 1 ? i + 1 : ''}`, th.gain));
      p.sls.forEach((s, i) => add(s, `${p.label} SL${p.sls.length > 1 ? i + 1 : ''}`, th.loss));
      add(p.liq, `${p.label} ${t('chart.liq')}`, th.warning);
    }
    posLabelElsRef.current = labels;
    return () => {
      posLabelElsRef.current = [];
      labels.forEach(l => l.el.remove());
      // 开关/数据变时挨个摘掉重画；图整体重建时 series 已死、removePriceLine 会抛，吞掉即可
      try { lines.forEach(l => series.removePriceLine(l)); } catch { /* chart disposed */ }
    };
    // t 进依赖：切语言时 t 换新引用，小签（"多10x 入场 63000"）跟着重画；isDark 换主题色
  }, [positionOverlays, showPosLines, hiddenPosIds, decimals, chartEpoch, chartType, isDark, t]);

  // 历史成交 B/S 标记：角标只说这根有买/有卖，不带笔数，逐笔价格点开弹窗看。
  // 方块贴在那根最低价下方（快讯 globe 在最高价上方，两边不打架）；买绿卖红，都有就上下叠两个
  useEffect(() => {
    const series = candleRef.current;
    const markTip = markTipRef.current;
    if (!series || !showMarks || !tradeMarks?.length) { marksByTimeRef.current = new Map(); return; }
    const th = lwcTheme();

    const bucketMs = BUCKET_MS[interval];
    const byTime = new Map<number, { b: Fill[]; s: Fill[] }>();
    for (const m of tradeMarks) {
      const time = toBarTime(Math.floor(m.timeMs / bucketMs) * bucketMs);
      const g = byTime.get(time) ?? { b: [], s: [] };
      (m.side === 'B' ? g.b : g.s).push({ timeMs: m.timeMs, price: m.price, quantity: m.quantity });
      byTime.set(time, g);
    }
    for (const g of byTime.values()) {
      g.b.sort((x, y) => x.timeMs - y.timeMs);
      g.s.sort((x, y) => x.timeMs - y.timeMs);
    }
    marksByTimeRef.current = byTime;

    // 同一根放两个 marker，LWC 会自己往下错开叠放
    const markers: SeriesMarker<UTCTimestamp>[] = [...byTime.entries()]
      .sort((a, b) => a[0] - b[0])
      .flatMap(([time, g]) => ([
        ...(g.b.length ? [{ time: time as UTCTimestamp, position: 'belowBar', color: th.gain, shape: 'square', text: 'B' } as const] : []),
        ...(g.s.length ? [{ time: time as UTCTimestamp, position: 'belowBar', color: th.loss, shape: 'square', text: 'S' } as const] : []),
      ]));
    const plugin = createSeriesMarkers(series, markers);

    return () => {
      marksByTimeRef.current = new Map();
      if (markTip) markTip.style.display = 'none';
      // 图整体重建时 series 已死，detach 会抛，吞掉即可
      try { plugin.detach(); } catch { /* chart disposed */ }
    };
  }, [tradeMarks, showMarks, interval, chartEpoch, isDark]);

  // 财经日历标记：High 级事件按 K 线时间桶聚合，日历图标悬在所属那根上方，点开看实际/预测/前值。
  // 语义是"这根K线覆盖的时间段内公布了什么"——按公布时刻定位，不承诺行情因果。
  // 标记画在主图画布上（EconMarkersLayer 挂蜡烛 series）：与蜡烛同帧渲染，平移缩放零延迟；
  // 弹窗仍是 DOM（国旗与换行画布画不了），点击命中在 pointerdown 里主动 pick
  useEffect(() => {
    const wrap = wrapRef.current, candle = candleRef.current;
    const econTip = econTipRef.current;
    if (!econMarks || !showEcon || !wrap || !candle) return;
    let disposed = false;
    const bucketMs = BUCKET_MS[interval];
    /** time → 该桶的事件组，点击标记时按命中的时间桶取内容 */
    const groups = new Map<number, EconCalendarEvent[]>();
    const layer = new EconMarkersLayer(time => {
      const i = idxRef.current.get(time);
      return i == null ? null : barsRef.current[i].high;
    });
    layer.palette = econPalette();
    candle.attachPrimitive(layer);
    econLayerRef.current = layer;

    const fmtClock = (ms: number) => new Date(ms).toLocaleString('zh-CN',
      { timeZone: 'Asia/Singapore', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    /** 实际 · 预测 · 前值，有哪个拼哪个；讲话类三个都没有就空串 */
    const values = (e: EconCalendarEvent) => ([['actual', e.actual], ['forecast', e.forecast], ['previous', e.previous]] as const)
      .filter(([, v]) => v)
      .map(([k, v]) => `${i18n.t(`home:calendar.${k}`)} ${esc(v!)}`).join(' · ');
    const showPopup = (rect: { x: number; y: number; w: number }, events: EconCalendarEvent[]) => {
      const tip = econTipRef.current; if (!tip || !events.length) return;
      tip.innerHTML = events.map(e => {
        const nums = values(e);
        return '<div style="padding:6px 0;border-bottom:1px solid var(--color-border)">'
          + `<div class="mute" style="font-weight:700;margin-bottom:2px">${fmtClock(e.eventTime)} · ${flagHtml(e.country)} ${esc(e.country)} / ${esc(e.currency)}</div>`
          + `<div style="font-weight:700;margin-bottom:2px">${esc(e.title)}</div>`
          + (nums ? `<div class="mute num">${nums}</div>` : '')
          + '</div>';
      }).join('');
      tip.style.display = 'block';
      // 内容定了再量尺寸：横向对中标记并夹在图内，纵向优先弹标记上方、顶部放不下翻到下方
      const W = wrap.clientWidth, tw = tip.offsetWidth, th = tip.offsetHeight;
      const ix = rect.x + rect.w / 2, iy = rect.y;
      tip.style.left = `${Math.min(Math.max(4, ix - tw / 2), W - tw - 4)}px`;
      tip.style.top = `${iy - th - 8 >= 4 ? iy - th - 8 : iy + 26}px`;
    };

    // 窗口按内存上限的最远可翻历史算：翻到底标记也都在
    quantApi.econCalendarEvents(Date.now() - bucketMs * MAX_BARS, Date.now() + bucketMs).then(events => {
      if (disposed || !events.length) return;
      for (const e of events) {
        const time = toBarTime(Math.floor(e.eventTime / bucketMs) * bucketMs);
        const g = groups.get(time) ?? [];
        g.push(e);
        groups.set(time, g);
      }
      layer.markers = [...groups.entries()].map(([time, g]) => ({ time, count: g.length }));
      layer.update();
    }).catch(() => { /* 接口失败：没有标记而已，图表照常 */ });

    // 点击命中：capture 在 document 上——点中标记时截住事件（LWC 的拖拽别跟着起步），
    // 点在标记与弹窗之外的任何地方都收起弹窗
    const onDown = (ev: PointerEvent) => {
      const tip = econTipRef.current;
      const target = ev.target as Node;
      if (tip && tip.style.display !== 'none' && tip.contains(target)) return;   // 弹窗内放行
      const paneCanvas = chartRef.current?.panes()[0]?.getHTMLElement()?.querySelector('canvas');
      const r = paneCanvas?.getBoundingClientRect();
      const hit = r ? layer.pick(ev.clientX - r.left, ev.clientY - r.top) : null;
      if (hit !== null) {
        ev.preventDefault();
        ev.stopPropagation();
        const rect = layer.rects.get(hit);
        if (rect) showPopup(rect, groups.get(hit) ?? []);
        return;
      }
      if (tip && tip.style.display !== 'none') tip.style.display = 'none';
    };
    document.addEventListener('pointerdown', onDown, true);
    return () => {
      disposed = true;
      document.removeEventListener('pointerdown', onDown, true);
      econLayerRef.current = null;
      // 图整体重建时 series 已死，detach 会抛，吞掉即可（同成交标记的清理）
      try { candle.detachPrimitive(layer); } catch { /* chart disposed */ }
      if (econTip) econTip.style.display = 'none';
    };
  }, [econMarks, showEcon, interval, chartEpoch, uiLang]);

  // 「最新价 + 收盘倒计时」墨块：顶在价格轴上原生最新价标签的位置（原生标签已关），
  // 上行价格、下行倒计时，一个框解决"倒计时和价格分家"。
  // 250ms 循环重取 Y 坐标与文案，价格跳动/缩放平移都跟得上；顺带把仓位参考线的贴轴小签
  // 一起重定位（它们同样要随缩放走，各开一个定时器纯属浪费）。
  // 休市/断流时倒计时行自动消失（一个停摆的倒计时比没有更误导），价格行保留。
  useEffect(() => {
    const el = cdRef.current; if (!el) return;
    const bucketMs = BUCKET_MS[interval];
    const render = () => {
      const chart = chartRef.current, candle = candleRef.current;
      const last = barsRef.current[barsRef.current.length - 1];
      const axisW = chart ? chart.priceScale('right').width() : 0;

      // ---- 仓位参考线小签：--------多10x 入场 63000----│y轴│ ----
      // 价格超出 y 轴可视范围时 priceToCoordinate 不返回 null 而是给界外坐标，
      // 小签会飘到主图 pane 外（盖顶栏/副图），按 pane 0 高度裁掉；
      // 剩下的按 y 排序，挨得近的往下推 16px，不叠在一起
      const paneH = chart ? chart.paneSize(0).height : 0;
      const vis: { el: HTMLDivElement; y: number }[] = [];
      for (const { el: label, price } of posLabelElsRef.current) {
        const y = candle?.priceToCoordinate(price);
        if (y == null || y < 0 || y > paneH) { label.style.display = 'none'; continue; }
        vis.push({ el: label, y });
      }
      vis.sort((a, b) => a.y - b.y);
      vis.forEach((v, k) => {
        if (k && v.y - vis[k - 1].y < LABEL_GAP) v.y = vis[k - 1].y + LABEL_GAP;
        v.el.style.top = `${v.y}px`;
        v.el.style.right = `${axisW + 4}px`;
        v.el.style.display = 'block';
      });

      if (!chart || !candle || !last) { el.style.display = 'none'; return; }
      const y = candle.priceToCoordinate(last.close);
      if (y == null) { el.style.display = 'none'; return; }

      const remain = last.openMs + bucketMs - Date.now();
      let cd = '';
      if (remain > 0 && remain <= bucketMs) {
        const s = Math.floor(remain / 1000);
        const pad = (n: number) => String(n).padStart(2, '0');
        const h = Math.floor(s / 3600);
        cd = h > 0 ? `${h}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
                   : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;
      }
      el.innerHTML = fmtNum(last.close, decimals)
        + (cd ? `<div style="font-size:9.5px;font-weight:600;opacity:.8;letter-spacing:.04em">${cd}</div>` : '');
      el.style.minWidth = `${axisW}px`;
      el.style.top = `${y}px`;
      el.style.display = 'block';
    };
    render();
    const timer = setInterval(render, 250);
    return () => { clearInterval(timer); el.style.display = 'none'; };
  }, [interval, decimals, chartEpoch]);

  // 主题切换：只改颜色，不重建
  useEffect(() => {
    applyThemeRef.current?.();
    const econ = econLayerRef.current;
    if (econ) { econ.palette = econPalette(); econ.update(); }
  }, [isDark]);

  // 外部价格 tick 驱动（streamLive=false）：桶对齐后更新/追加最后一根，量额保持历史值（价格流无量数据）
  useEffect(() => {
    if (streamLive || !tick || tick.price <= 0 || !readyRef.current) return;
    const paint = paintLastRef.current; if (!paint) return;
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
    paint(bar);
  }, [tick, streamLive, interval]);

  // 实时：当前根原地更新（蜡烛 + 折线 + 量柱 + 读数）
  useEffect(() => {
    if (!streamLive || !live || !readyRef.current) return;
    const paint = paintLastRef.current; if (!paint) return;
    const time = toBarTime(live.t);
    // 防乱序：忽略比最后一根更早的(重连/迟到)消息，否则 LWC update(time<lastTime) 会抛异常
    const last = barsRef.current[barsRef.current.length - 1];
    if (last && time < last.time) return;
    const bar: Bar = { time, openMs: live.t, open: live.o, high: live.h, low: live.l, close: live.c, volume: live.v, quote: live.q };
    const i = idxRef.current.get(time);
    if (i == null) { idxRef.current.set(time, barsRef.current.length); barsRef.current.push(bar); }
    else barsRef.current[i] = bar;
    paint(bar);
  }, [live, streamLive]);

  const toggle = (k: OverlayKey) => setOverlays(prev => ({ ...prev, [k]: !prev[k] }));
  const toggleSub = (k: 'macd' | 'rsi') => setSubs(prev => {
    const next = { ...prev, [k]: !prev[k] };
    localStorage.setItem('wiib-chart-sub-' + k, next[k] ? '1' : '0');
    return next;
  });
  const pickType = (v: 'candle' | 'line') => { setChartType(v); localStorage.setItem('wiib-chart-type', v); };

  return (
    // 全屏用的是这一层：原生模式靠 :fullscreen 的 UA 样式铺满，iPhone Safari 没有元素级
    // 全屏则退成 fixed。两种都只改类名不改 DOM 结构，图表不会被 React 卸载重建。
    <div ref={rootRef} className={cn(
      'w-full h-full flex flex-col',
      fs.active && 'fixed inset-0 z-50 bg-background p-4 pb-7',
    )}>
      {/* 顶栏：周期 / 图型 / 指标入口 —— 撑开 —— 显示开关 / 全屏。画线工具收进左侧竖栏（手机放这行最前） */}
      <div className="flex items-center gap-2.5 mb-2.5 flex-wrap">
        {!advMode && (
          <DrawToolPopover
            className="md:hidden" tool={tool} onSelect={setTool}
            magnet={magnet} onToggleMagnet={() => setMagnet(!magnet)}
            hiddenAll={hiddenAll} onToggleHidden={() => setHiddenAll(!hiddenAll)} hideDisabled={!drawCount}
            onTrash={trash} trashDisabled={!hasSelection && !drawCount}
            trashTitle={hasSelection ? t('chart.deleteSelected') : t('chart.clearAll')}
          />
        )}

        <div className="seg num">
          {(Object.keys(BUCKET_MS) as Interval[]).map(k => (
            <button key={k} type="button" className={cn(!advMode && interval === k && 'on')}
                    onClick={() => { setAdvMode(false); onIntervalChange(k); }}>
              {k}
            </button>
          ))}
          {advanced && (
            <button type="button" className={cn(advMode && 'on')} onClick={() => setAdvMode(true)}>
              {t('chart.advanced')}
            </button>
          )}
        </div>

        <div className="seg">
          <button type="button" className={cn('px-[9px]', chartType === 'candle' && 'on')}
                  title={t('chart.candle')} onClick={() => pickType('candle')}>
            <ChartCandlestick className="w-[15px] h-[15px]" />
          </button>
          <button type="button" className={cn('px-[9px]', chartType === 'line' && 'on')}
                  title={t('chart.line')} onClick={() => pickType('line')}>
            <ChartLine className="w-[15px] h-[15px]" />
          </button>
        </div>

        {/* 指标弹层：主图三组、副图两组，chip 填墨=开 */}
        {indicators && (
          <div ref={indPopRef} className="relative">
            <button type="button" className="btn xs" onClick={() => setIndOpen(o => !o)}>
              <ChartNoAxesCombined className="ic" />
              {t('chart.indicators')}
              <ChevronDown className={cn('w-3 h-3 transition-transform', indOpen && 'rotate-180')} />
            </button>
            <div className={cn(
              'absolute left-0 top-[calc(100%+6px)] z-20 min-w-[230px] px-3 py-2 border border-foreground bg-background',
              'transition-[opacity,transform] duration-150',
              indOpen ? 'opacity-100 translate-y-0 pointer-events-auto' : 'opacity-0 -translate-y-1 pointer-events-none',
            )}>
              <div className="flex items-center gap-1.5 py-1.5">
                <b className="w-[34px] text-[11.5px] font-semibold text-muted-foreground">{t('chart.mainOverlay')}</b>
                {(['ma', 'ema', 'boll'] as OverlayKey[]).map(k => (
                  <button key={k} type="button" onClick={() => toggle(k)}
                          className={cn('chip cursor-pointer', overlays[k] && 'fill')}>
                    {k.toUpperCase()}
                  </button>
                ))}
              </div>
              <div className="flex items-center gap-1.5 py-1.5">
                <b className="w-[34px] text-[11.5px] font-semibold text-muted-foreground">{t('chart.subPane')}</b>
                {(['macd', 'rsi'] as const).map(k => (
                  <button key={k} type="button" onClick={() => toggleSub(k)}
                          className={cn('chip cursor-pointer', subs[k] && 'fill')}>
                    {k.toUpperCase()}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}

        <span className="flex-1" />

        {/* 仓位参考线开关：只有页面传了仓位数据才出现（现货/代币化美股页没有）。
            双开时每个仓位一颗 chip，颜色跟方向走，可单独藏掉某一边 */}
        {positionOverlays != null && (
          <>
            <button type="button" title={t('chart.posLinesTitle')}
                    className={cn('ibtn', showPosLines && 'on')}
                    onClick={() => {
                      const v = !showPosLines;
                      setShowPosLines(v);
                      localStorage.setItem('wiib-chart-pos-lines', v ? '1' : '0');
                    }}>
              <Layers className="ic" />
            </button>
            {showPosLines && positionOverlays.length > 1 && positionOverlays.map(p => (
              <button key={p.id} type="button"
                      className={cn('chip cursor-pointer',
                        hiddenPosIds.has(p.id) ? 'mute' : p.side === 'LONG' ? 'up' : 'dn')}
                      onClick={() => setHiddenPosIds(prev => {
                        const next = new Set(prev);
                        if (next.has(p.id)) next.delete(p.id); else next.add(p.id);
                        return next;
                      })}>
                {p.label}
              </button>
            ))}
          </>
        )}

        {/* 历史成交标记开关：只有页面传了成交数据才出现 */}
        {tradeMarks != null && (
          <button type="button" title={t('chart.marksTitle')}
                  className={cn('ibtn', showMarks && 'on')}
                  onClick={() => {
                    const v = !showMarks;
                    setShowMarks(v);
                    localStorage.setItem('wiib-chart-trade-marks', v ? '1' : '0');
                  }}>
            <History className="ic" />
          </button>
        )}

        {/* 财经日历标记开关：只有 BTC 才出现 */}
        {econMarks && (
          <button type="button" title={t('chart.econTitle')}
                  className={cn('ibtn', showEcon && 'on')}
                  onClick={() => {
                    const v = !showEcon;
                    setShowEcon(v);
                    localStorage.setItem('wiib-chart-econ', v ? '1' : '0');
                  }}>
            <CalendarClock className="ic" />
          </button>
        )}

        <button type="button" onClick={fs.toggle} className="ibtn"
                title={fs.active ? t('chart.exitFullscreen') : t('chart.fullscreen')}>
          {fs.active ? <Shrink className="ic" /> : <Expand className="ic" />}
        </button>
      </div>

      {/* 左竖栏 34px + 画布。高级档整块盖住 plot，竖栏也收起来 */}
      <div className={cn('grid grid-cols-1 border-t border-foreground flex-1 min-h-0',
        advMode ? 'md:grid-cols-1' : 'md:grid-cols-[34px_1fr]')}>
        {!advMode && (
          <DrawToolRail
            className="hidden md:flex" tool={tool} onSelect={setTool}
            magnet={magnet} onToggleMagnet={() => setMagnet(!magnet)}
            hiddenAll={hiddenAll} onToggleHidden={() => setHiddenAll(!hiddenAll)} hideDisabled={!drawCount}
            onTrash={trash} trashDisabled={!hasSelection && !drawCount}
            trashTitle={hasSelection ? t('chart.deleteSelected') : t('chart.clearAll')}
          />
        )}
        <div ref={wrapRef} className="relative min-h-0">
          <div ref={chartDivRef} className="absolute inset-0" />
          {/* 文字标注输入。Esc 会先把锚点清掉，所以随后 unmount 触发的 blur→commit 是空转。
              透明浮层：文字直接浮在图上，所见即所得（提交后的标注就长这样），只留一条虚线下划线 */}
          {textEdit && (
            <input autoFocus placeholder={t('chart.textPlaceholder')}
                   onKeyDown={e => {
                     if (e.key === 'Enter') commitText(e.currentTarget.value);
                     else if (e.key === 'Escape') cancelText();
                   }}
                   onBlur={e => commitText(e.currentTarget.value)}
                   className="absolute z-[6] w-[200px] py-0.5 border-0 outline-none bg-transparent text-foreground text-[12px] font-semibold"
                   style={{
                     left: textEdit.x, top: textEdit.y - 12,
                     borderBottom: '1px dashed var(--color-primary)', caretColor: 'var(--color-primary)',
                   }} />
          )}
          {/* 竖屏全屏的形状提示：Android 会被 orientation.lock 直接转过去（这条最多闪一下），
              iOS 靠它请用户动手。转到横屏 matchMedia 翻面，提示自动消失 */}
          {fs.active && portrait && IS_TOUCH && (
            <div className="absolute left-1/2 top-2 -translate-x-1/2 z-[5] px-2.5 py-1 bg-foreground text-background text-[11px] font-semibold pointer-events-none whitespace-nowrap">
              {t('chart.rotate')}
            </div>
          )}
          {/* 翻历史提示（载入中 / 到底） */}
          <div ref={hintRef} className="absolute z-[4] pointer-events-none px-2 py-0.5 border border-foreground bg-background text-[12px] font-semibold"
               style={{ left: 10, top: 6, display: 'none' }} />
          {/* B/S 标记的点击弹窗：逐笔成交价（subscribeClick 填充） */}
          <div ref={markTipRef} className="absolute z-[6] pointer-events-none px-2.5 py-1.5 border border-foreground bg-background text-[12px] min-w-[130px]"
               style={{ display: 'none' }} />
          {/* 日历图标的点击弹窗：时间+国旗+标题+实际/预测/前值 */}
          <div ref={econTipRef} className="absolute z-[6] px-3 py-0.5 border border-foreground bg-background text-[12px] leading-[1.55] w-[280px] max-h-[240px] overflow-y-auto"
               style={{ display: 'none' }} />
          {/* 「最新价 + 收盘倒计时」墨块：顶替原生最新价轴标签，右缘与价格轴齐平 */}
          <div ref={cdRef} className="num absolute z-[4] pointer-events-none bg-foreground text-background text-[11px] font-bold leading-[1.35] text-center"
               style={{ display: 'none', right: 0, boxSizing: 'border-box', padding: '2px 0 2px 8px', transform: 'translateY(-50%)' }} />
          {/* 高级档：外部塞进来的 TradingView，盖在图表上（图表实例不卸载） */}
          {advMode && <div className="absolute inset-0 z-10 bg-background">{advanced}</div>}
        </div>
      </div>
    </div>
  );
}
