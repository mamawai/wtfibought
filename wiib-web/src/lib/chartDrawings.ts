/**
 * K 线画线工具的纯逻辑层：数据模型、持久化、坐标换算、磁吸、几何命中。
 *
 * 这里不碰 DOM 也不碰 canvas —— 渲染在 DrawingLayer.ts，交互在 useDrawings.ts。
 *
 * 最关键的一条约定：**图形锚点只存绝对 bar time + 价格，绝不存 logical index**。
 * CandleChart 会向左前插历史(loadMore)，前插后所有 logical 整体右移 older.length，
 * 存 logical 的话翻一次历史所有线就集体飘走。存 time 则每帧现查 idx，天然免疫；
 * 顺带白拿一个红利：跨周期通用——1h 上画的趋势线切到 4h 还钉在同一时刻同一价位。
 */
import type { ISeriesApi, ITimeScaleApi, Logical, Time } from 'lightweight-charts';

// ========== 数据模型 ==========

/**
 * 图形种类（对齐 TradingView 常用集）：
 * trend 趋势线 / ray 射线 / hray 水平射线 / arrow 箭头 / hline 水平线 / vline 垂直线 / channel 平行通道 /
 * rect 矩形 / fib 斐波回撤 / fibext 斐波扩展（趋势型）/ long·short 多头·空头仓位（入场+止损+止盈区间）/
 * range 价格区间（量幅度·根数·时长）/ text 文字
 */
export type DrawingKind =
  | 'trend' | 'ray' | 'hray' | 'arrow' | 'hline' | 'vline' | 'channel'
  | 'rect' | 'fib' | 'fibext' | 'long' | 'short' | 'range' | 'text';

/** 锚点：t=bar 开盘时刻(秒，已含 CandleChart 的 UTC+8 偏移口径)，p=价格 */
export interface Anchor { t: number; p: number; }

/**
 * pts 约定：
 * - hline 只用 p，vline 只用 t，text 只用锚点定位，hray 一点（从这根往右）；
 * - trend/ray/arrow/rect/fib/range 两点；channel 三点（基线两端 + 平行线过的点）；
 * - fibext 三点（趋势起点、趋势终点、回撤落点）；
 * - long/short 三点 = [入场, 止损, 止盈]，止损点的 t 同时是区间右缘，止盈点 t 恒等于止损点 t。
 */
export interface Drawing {
  id: string;
  kind: DrawingKind;
  pts: Anchor[];
  color: string;
  text?: string;
  /** 线宽(px)，没设=1；旧存档没有这两个字段，按默认画 */
  width?: number;
  dash?: LineDash;
}

/** 线型：实线 / 虚线 / 点线 */
export type LineDash = 'solid' | 'dashed' | 'dotted';

/** 属性条可选的线宽 */
export const LINE_WIDTHS = [1, 2, 3] as const;

/** 属性条色板：第一个是默认蓝 */
export const DRAW_PALETTE = ['#2962ff', '#f23645', '#089981', '#ff9800', '#9c27b0', '#00bcd4', '#e91e63', '#787b86'];

/**
 * 每种图形属性条能改哪几样。斐波/仓位/区间是多色的语义图形（档位色、盈亏色、涨跌色），
 * 颜色和线型不给改；文字只有颜色
 */
export const STYLE_CAPS: Record<DrawingKind, { color: boolean; line: boolean }> = {
  trend: { color: true, line: true }, ray: { color: true, line: true },
  hray: { color: true, line: true }, arrow: { color: true, line: true },
  hline: { color: true, line: true }, vline: { color: true, line: true },
  channel: { color: true, line: true }, rect: { color: true, line: true },
  fib: { color: false, line: false }, fibext: { color: false, line: false },
  long: { color: false, line: false }, short: { color: false, line: false },
  range: { color: false, line: false }, text: { color: true, line: false },
};

/** 线型 → canvas 的 setLineDash 参数，间隔跟着线宽放大，粗线的点不会糊成一条 */
export function dashPattern(dash: LineDash | undefined, width: number): number[] {
  if (dash === 'dashed') return [width * 5, width * 3];
  if (dash === 'dotted') return [width, width * 2];
  return [];
}

/** 每种图形要用户亲手落几个点（long/short 落入场+止损两点，止盈派生，见 finalizePoints） */
export const PLACE_POINTS: Record<DrawingKind, 1 | 2 | 3> = {
  trend: 2, ray: 2, hray: 1, arrow: 2, hline: 1, vline: 1, channel: 3, rect: 2,
  fib: 2, fibext: 3, long: 2, short: 2, range: 2, text: 1,
};

/** 仓位工具默认盈亏比：定完止损后止盈按 2:1 派生，之后可拖止盈手柄改 */
export const POSITION_RR = 2;

/**
 * 落点收齐后补齐派生锚点。仓位工具：止盈 = 入场 + (入场-止损)×RR，
 * 多头止损在下则止盈在上、空头反之——同一条公式靠符号自洽，不分支。
 */
export function finalizePoints(kind: DrawingKind, pts: Anchor[]): Anchor[] {
  if ((kind === 'long' || kind === 'short') && pts.length >= 2) {
    const [entry, stop] = pts;
    return [entry, stop, { t: stop.t, p: entry.p + (entry.p - stop.p) * POSITION_RR }];
  }
  return pts;
}

/** 默认线色：TradingView 同款蓝，亮暗主题下都压得住红绿蜡烛 */
export const DRAW_COLOR = '#2962ff';
/** 仓位工具的盈/亏区、量能红绿：与蜡烛同色系 */
export const GAIN_COLOR = '#089981';
export const LOSS_COLOR = '#f23645';

/** 秒数 → 紧凑时长文案（价格区间工具用）：3d 4h / 2h 15m / 45m */
export function fmtDuration(sec: number): string {
  const s = Math.abs(Math.round(sec));
  const d = Math.floor(s / 86_400), h = Math.floor((s % 86_400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d > 0) return h > 0 ? `${d}d ${h}h` : `${d}d`;
  if (h > 0) return m > 0 ? `${h}h ${m}m` : `${h}h`;
  return `${m}m`;
}

/** 斐波那契档位与配色（对齐 TradingView 惯例：0/1 端点灰，中间档暖→冷渐变） */
export const FIB_LEVELS = [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1] as const;
export const FIB_COLORS = ['#787b86', '#f23645', '#ff9800', '#4caf50', '#089981', '#00bcd4', '#787b86'];
/** 斐波扩展档位：各档价 = 回撤落点 + 趋势幅度×档位；1.618/2.618 是最常盯的止盈目标 */
export const FIBEXT_LEVELS = [0, 0.382, 0.618, 1, 1.272, 1.618, 2, 2.618] as const;
export const FIBEXT_COLORS = ['#787b86', '#ff9800', '#4caf50', '#787b86', '#00bcd4', '#2962ff', '#9c27b0', '#e91e63'];

// ========== 交互阈值（像素） ==========

/** 线身命中半径：手指比鼠标粗，取 8 在 PC 上不误触、手机上也够点 */
export const HIT_LINE = 8;
/** 端点手柄命中半径：比线身大，保证"想拖端点"时优先于"拖整体" */
export const HIT_HANDLE = 11;
/** 磁吸半径：超过就放弃吸附走自由落点，强扭会让人没法画两根之间的位置 */
export const MAGNET_PX = 12;

// ========== 持久化 ==========

/** 按 symbol 存，不带 interval —— 锚点是绝对时间，各周期共用同一套图形 */
const key = (symbol: string) => `wiib-draw:${symbol}`;

export function loadDrawings(symbol: string): Drawing[] {
  try {
    const raw = localStorage.getItem(key(symbol));
    const arr = raw ? JSON.parse(raw) : null;
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];   // 隐私模式禁写 / 旧版脏数据：当没画过，存档挂掉不影响看盘
  }
}

export function saveDrawings(symbol: string, ds: Drawing[]): void {
  try {
    if (ds.length) localStorage.setItem(key(symbol), JSON.stringify(ds));
    else localStorage.removeItem(key(symbol));
  } catch { /* 配额满/禁写：画线仍在内存里可用，只是刷新后没了 */ }
}

let seq = 0;
export const newId = () => `d${Date.now().toString(36)}${(seq++).toString(36)}`;

// ========== 坐标换算 ==========

/** 只取 OHLC —— 与 CandleChart 内部的 Bar 结构兼容，单独声明是为了不产生循环依赖 */
export interface OhlcBar { time: number; open: number; high: number; low: number; close: number; }

/**
 * 图表侧只读上下文。bars/idx 走 getter 而不是直接传值：
 * 图层活得比任何一次渲染都久，实时 tick 和翻历史都会换掉 ref 里的数组。
 */
export interface ChartCtx {
  bars: () => OhlcBar[];
  idx: () => Map<number, number>;
  /** 当前周期的 bar 间隔(秒)，范围外外推靠它 */
  bucketSec: number;
  timeScale: ITimeScaleApi<Time>;
  series: ISeriesApi<'Candlestick'>;
}

/**
 * bar time → logical index（可含小数）。
 *
 * 三种情形：
 *  1. 正好是当前周期的某根 → 查表，整数；
 *  2. 落在已加载区间内但不对齐（切周期最常见：1h 上画的点在 4h 上多半卡在两根之间）
 *     → 二分找夹住它的两根，按**实际间隔**插值（真实行情可能缺根，硬套 bucketSec 会一路歪）；
 *  3. 超出两头（趋势线延伸到还没产生的未来）→ 按 bucketSec 外推。
 */
export function timeToLogical(t: number, ctx: ChartCtx): number | null {
  const bars = ctx.bars();
  if (!bars.length) return null;
  const i = ctx.idx().get(t);
  if (i != null) return i;

  const last = bars.length - 1;
  if (t <= bars[0].time) return (t - bars[0].time) / ctx.bucketSec;          // 早于首根，结果为负
  if (t >= bars[last].time) return last + (t - bars[last].time) / ctx.bucketSec;

  let lo = 0, hi = last;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (bars[mid].time <= t) lo = mid; else hi = mid;
  }
  const span = bars[hi].time - bars[lo].time;
  return span > 0 ? lo + (t - bars[lo].time) / span : lo;
}

/**
 * logical（可含小数）→ 像素 x。
 *
 * **不能把小数直接丢给 logicalToCoordinate**：LWC 内部的 indexToCoordinate 开头就是
 * `if (isEmpty() || !isInteger(index)) return 0;` —— 非整数既不报错也不返回 null，
 * 而是静默给 0，表现为所有卡在两根之间的锚点全部堆到画布最左缘（切周期时必现）。
 * 取相邻两个整数格问出格宽再插值，只用公开 API，结果精确。
 */
function logicalToX(l: number, ctx: ChartCtx): number | null {
  const i = Math.floor(l);
  const a = ctx.timeScale.logicalToCoordinate(i as Logical);
  if (a === null) return null;
  const frac = l - i;
  if (frac === 0) return a;
  const b = ctx.timeScale.logicalToCoordinate((i + 1) as Logical);
  return b === null ? a : a + frac * (b - a);
}

/** logical index(取整后) → bar time，timeToLogical 的逆运算 */
export function logicalToTime(i: number, ctx: ChartCtx): number | null {
  const bars = ctx.bars();
  if (!bars.length) return null;
  if (i >= 0 && i < bars.length) return bars[i].time;
  const last = bars.length - 1;
  return i < 0 ? bars[0].time + i * ctx.bucketSec
    : bars[last].time + (i - last) * ctx.bucketSec;
}

/** 锚点 → 画布像素。任一维算不出（图表还没布局好）就返回 null，调用方跳过不画 */
export function anchorToPoint(a: Anchor, ctx: ChartCtx): { x: number; y: number } | null {
  const l = timeToLogical(a.t, ctx);
  if (l === null) return null;
  const x = logicalToX(l, ctx);
  const y = ctx.series.priceToCoordinate(a.p);
  return x === null || y === null ? null : { x, y };
}

/** x 像素 → bar time。取整 = 天然吸附到整根 bar，横向不会画出"半根"的歪位置 */
export function coordToTime(x: number, ctx: ChartCtx): number | null {
  const l = ctx.timeScale.coordinateToLogical(x);
  return l === null ? null : logicalToTime(Math.round(l), ctx);
}

// ========== 磁吸 ==========

/**
 * 弱磁吸：把落点纵向吸到同一根 bar 的 O/H/L/C 中像素距离最近的那个。
 *
 * 画支撑压力位/趋势线时，人想对的是"某根的最高价"这种确切的点，
 * 手动对齐永远差几个像素；但超过 MAGNET_PX 就必须放手，
 * 否则用户没法在两个价位中间自由落点。snapped 回传给渲染层画吸附提示点。
 *
 * 返回 null = 价格轴还没准备好，此时调用方应放弃这次落点：
 * 硬塞个 0 进去会凭空造出一条钉在零轴的垃圾线。
 */
export function magnetPrice(t: number, y: number, ctx: ChartCtx): { p: number; snapped: boolean } | null {
  const free = ctx.series.coordinateToPrice(y);
  if (free === null) return null;
  const fallback = { p: free, snapped: false };
  const i = ctx.idx().get(t);
  if (i == null) return fallback;          // 未来区/未加载区没有 OHLC 可吸

  const b = ctx.bars()[i];
  let best = MAGNET_PX, bestP: number | null = null;
  for (const p of [b.open, b.high, b.low, b.close]) {
    const cy = ctx.series.priceToCoordinate(p);
    if (cy === null) continue;
    const d = Math.abs(cy - y);
    if (d < best) { best = d; bestP = p; }
  }
  return bestP === null ? fallback : { p: bestP, snapped: true };
}

// ========== 几何 ==========

/**
 * 射线终点：从 p0 经 p1 方向一直延到画布边缘(w×h)。
 * 取 x/y 两轴各自到边的参数 t，小的那个先出界；方向为 0 的轴给 Infinity 不参与。
 */
export function rayEnd(p0: { x: number; y: number }, p1: { x: number; y: number },
                       w: number, h: number): { x: number; y: number } {
  const dx = p1.x - p0.x, dy = p1.y - p0.y;
  if (dx === 0 && dy === 0) return p1;
  const tx = dx > 0 ? (w - p0.x) / dx : dx < 0 ? -p0.x / dx : Infinity;
  const ty = dy > 0 ? (h - p0.y) / dy : dy < 0 ? -p0.y / dy : Infinity;
  const t = Math.max(0, Math.min(tx, ty));
  return { x: p0.x + dx * t, y: p0.y + dy * t };
}

/** 点是否在多边形内（射线法）；通道/仓位区间的"点内部拖整体"用 */
export function pointInPoly(px: number, py: number, poly: { x: number; y: number }[]): boolean {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    const a = poly[i], b = poly[j];
    if ((a.y > py) !== (b.y > py) && px < (b.x - a.x) * (py - a.y) / (b.y - a.y) + a.x) inside = !inside;
  }
  return inside;
}

/** 点到线段的像素距离。线身命中判定用，比"点到直线"多一步端点夹紧 */
export function distToSegment(px: number, py: number, x1: number, y1: number, x2: number, y2: number): number {
  const dx = x2 - x1, dy = y2 - y1;
  const len2 = dx * dx + dy * dy;
  // 退化成一个点（两端重合）时直接算点距，否则下面会除 0
  if (len2 === 0) return Math.hypot(px - x1, py - y1);
  const t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len2));
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
}
