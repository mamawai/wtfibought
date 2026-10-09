/**
 * 成交量分布（VP）图层：以 ISeriesPrimitive 挂在蜡烛 series 上，横柱从主图右缘往左画、压在蜡烛下面。
 * 每档横柱左段主动买（涨色）、右段主动卖（跌色）；价值区内深、区外浅；POC 一条虚线横贯主图。
 * <p>{@link attachVolumeProfile} 管数据：按可见范围选子周期、按整块拉子 K、算 profile 交给图层画。
 */
import type {
  IChartApi, IPrimitivePaneRenderer, IPrimitivePaneView, ISeriesApi,
  ISeriesPrimitive, SeriesAttachedParameter, SeriesType, Time,
} from 'lightweight-charts';
import type { CanvasRenderingTarget2D } from 'fancy-canvas';
import { ApiError } from '../../api';
import { rgba } from '../../lib/chartTheme';
import {
  CHUNK_BARS, chunkEndMs, chunkOf, computeProfile, pickSubInterval,
  type VolumeProfile, type VpBar,
} from '../../lib/volumeProfile';

export interface VpPalette { gain: string; loss: string; line: string; mute: string; }

/** 横柱最长占主图宽度的比例 */
const WIDTH_RATIO = 0.25;
/** 价值区内 / 外的填充透明度 */
const VA_ALPHA = 0.42;
const OUT_ALPHA = 0.18;

class VolumeProfileLayer implements ISeriesPrimitive<Time> {
  profile: VolumeProfile | null = null;
  /** 拉数据出错时画在 VP 区的一行字，空串不画 */
  status = '';
  palette: VpPalette;
  /** 挂在哪条 series 上，价格换纵坐标用它 */
  readonly series: ISeriesApi<SeriesType>;

  private _requestUpdate?: () => void;
  // 固定同一个数组引用：库内部按引用做缓存
  private readonly _views: IPrimitivePaneView[];

  constructor(series: ISeriesApi<SeriesType>, palette: VpPalette) {
    this.series = series;
    this.palette = palette;
    this._views = [new PaneView(this)];
  }

  attached(p: SeriesAttachedParameter<Time, SeriesType>) {
    this._requestUpdate = p.requestUpdate;
  }

  detached() {
    this._requestUpdate = undefined;
  }

  /** 改完 profile/status/palette 调它重画 */
  update() {
    this._requestUpdate?.();
  }

  paneViews() {
    return this._views;
  }
}

class PaneView implements IPrimitivePaneView {
  private readonly _r: PaneRenderer;

  constructor(layer: VolumeProfileLayer) {
    this._r = new PaneRenderer(layer);
  }

  zOrder() {
    return 'bottom' as const;   // 压在蜡烛下面，不挡 K 线
  }

  renderer() {
    return this._r;
  }
}

class PaneRenderer implements IPrimitivePaneRenderer {
  private readonly _layer: VolumeProfileLayer;

  constructor(layer: VolumeProfileLayer) {
    this._layer = layer;
  }

  draw(target: CanvasRenderingTarget2D) {
    target.useMediaCoordinateSpace(({ context: c, mediaSize }) => {
      const L = this._layer, series = L.series;
      const { gain, loss, line, mute } = L.palette;
      const W = mediaSize.width, maxW = W * WIDTH_RATIO;
      const p = L.profile;

      if (p) {
        p.rows.forEach((row, i) => {
          if (!(row.vol > 0)) return;
          const yTop = series.priceToCoordinate(row.hi), yBot = series.priceToCoordinate(row.lo);
          if (yTop === null || yBot === null) return;
          const h = Math.max(1, yBot - yTop - 1);   // 档间留 1px 缝
          const w = row.vol / p.maxVol * maxW, wBuy = w * row.buy / row.vol;
          const a = i >= p.vaLo && i <= p.vaHi ? VA_ALPHA : OUT_ALPHA;
          c.fillStyle = rgba(gain, a);
          c.fillRect(W - w, yTop, wBuy, h);
          c.fillStyle = rgba(loss, a);
          c.fillRect(W - w + wBuy, yTop, w - wBuy, h);
        });

        const poc = p.rows[p.poc];
        const y = series.priceToCoordinate((poc.lo + poc.hi) / 2);
        if (y !== null) {
          c.save();
          c.strokeStyle = line;
          c.lineWidth = 1;
          c.setLineDash([4, 3]);
          c.beginPath();
          c.moveTo(0, Math.round(y) + 0.5);
          c.lineTo(W, Math.round(y) + 0.5);
          c.stroke();
          c.restore();
        }
      }

      if (L.status) {
        c.save();
        c.font = '600 11px system-ui, sans-serif';
        c.fillStyle = mute;
        c.textAlign = 'right';
        c.textBaseline = 'bottom';
        // 量柱占底部 18%，字压在它上面
        c.fillText(L.status, W - 6, mediaSize.height * 0.82 - 6);
        c.restore();
      }
    });
  }
}

// ========== 数据 ==========

export interface VpController {
  setRows(n: number): void;
  setPalette(p: VpPalette): void;
  dispose(): void;
}

interface VpOptions {
  chart: IChartApi;
  series: ISeriesApi<SeriesType>;
  symbol: string;
  /** 主图周期毫秒 */
  mainMs: number;
  klinesFn: (symbol: string, interval: string, limit: number, endTime?: number) => Promise<number[][]>;
  /** 币安原始行 → VpBar */
  toBar: (k: number[]) => VpBar;
  /** 主图 K 线（实时 tick / 翻历史会换数组，所以走 getter） */
  bars: () => VpBar[];
  rows: number;
  palette: VpPalette;
  /** 收完的块，key = 子周期:块号，长存。同品种切周期复用，换品种由调用方换新的 */
  cache: Map<string, VpBar[]>;
  /** 拉失败且后端没给话时显示的字 */
  failText: () => string;
}

/** 块尾早于服务器时间这么久才算收完：后端刚过边界 2 秒内只缓存 2 秒，再留余量 */
const DONE_LAG_MS = 10_000;
/** 还在长的块隔多久重拉 */
const REFRESH_MS = 30_000;
/** 拖动/缩放停下多久再算 */
const DEBOUNCE_MS = 300;
/** 拉失败后隔多久再试 */
const RETRY_MS = 10_000;

export function attachVolumeProfile(o: VpOptions): VpController {
  const layer = new VolumeProfileLayer(o.series, o.palette);
  o.series.attachPrimitive(layer);
  let rows = o.rows;
  let disposed = false;
  /** 还在长的块：拉到的 bar 和拉回来的时刻 */
  const growing = new Map<string, { bars: VpBar[]; at: number }>();
  /** 拉到过的最新子 K 开盘时刻，服务器时间下限的一部分 */
  let newestOpen = 0;
  const inflight = new Set<string>();
  const failedAt = new Map<string, number>();
  let debounceTimer: ReturnType<typeof setTimeout> | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let timerAt = Infinity;

  /** 画出完整的一张就把出错提示清掉 */
  const show = (p: VolumeProfile | null) => {
    layer.profile = p;
    layer.status = '';
    layer.update();
  };

  /** ms 后再 plan 一次；已经约了更早的就不动 */
  const planIn = (ms: number) => {
    const at = Date.now() + ms;
    if (at >= timerAt) return;
    clearTimeout(timer);
    timerAt = at;
    timer = setTimeout(() => { timerAt = Infinity; plan(); }, ms);
  };

  /** 按对齐的 endTime 拉一块；在飞的不拉，刚失败过的约到点再来。回来（成败都）重新 plan 一次 */
  const request = (key: string, iv: string, endMs: number, onOk: (bars: VpBar[]) => void) => {
    if (inflight.has(key)) return;
    const wait = (failedAt.get(key) ?? 0) + RETRY_MS - Date.now();
    if (wait > 0) { planIn(wait); return; }
    inflight.add(key);
    o.klinesFn(o.symbol, iv, CHUNK_BARS, endMs).then(raw => {
      if (disposed) return;
      failedAt.delete(key);
      const bars = raw.map(o.toBar);
      const last = bars[bars.length - 1];
      if (last && last.openMs > newestOpen) newestOpen = last.openMs;
      onOk(bars);
    }).catch((e: unknown) => {
      if (disposed) return;
      failedAt.set(key, Date.now());
      layer.status = e instanceof ApiError ? e.message : o.failText();
      layer.update();
    }).finally(() => {
      inflight.delete(key);
      if (!disposed) plan();
    });
  };

  /** 按当前可见范围凑齐子 K 并算 profile；缺数据就发请求，没凑齐前保留上一次的图 */
  const plan = () => {
    const bars = o.bars();
    const range = o.chart.timeScale().getVisibleLogicalRange();
    if (!bars.length || !range) return;
    const i0 = Math.max(0, Math.ceil(range.from)), i1 = Math.min(bars.length - 1, Math.floor(range.to));
    if (i0 > i1) { show(null); return; }

    let lo = Infinity, hi = -Infinity;
    for (let i = i0; i <= i1; i++) {
      if (bars[i].low < lo) lo = bars[i].low;
      if (bars[i].high > hi) hi = bars[i].high;
    }
    const fromMs = bars[i0].openMs, toMs = bars[i1].openMs + o.mainMs;
    const sub = pickSubInterval(toMs - fromMs, o.mainMs);
    if (!sub) {
      // 直接用主图 K 线，隔 REFRESH_MS 重算一次
      show(computeProfile(bars.slice(i0, i1 + 1), lo, hi, rows));
      planIn(REFRESH_MS);
      return;
    }

    // 服务器时间的下限，判断块收没收完用它，不用本机时钟。
    // 主图取倒数第二根：最后一根可能是价格 tick 按本机时钟推出来的，倒数第二根在服务器上肯定已经开盘
    const serverLow = Math.max(newestOpen, bars.length > 1 ? bars[bars.length - 2].openMs : 0);
    const now = Date.now();
    const used: VpBar[] = [];
    let ready = true;
    for (let k = chunkOf(fromMs, sub.ms); k <= chunkOf(toMs - 1, sub.ms); k++) {
      const key = `${sub.iv}:${k}`, end = chunkEndMs(k, sub.ms);
      const done = o.cache.get(key);
      if (done) { used.push(...done); continue; }
      const own = (b: VpBar[]) => b.filter(x => chunkOf(x.openMs, sub.ms) === k);
      // 还没拿到长存那份的，先用手上的顶着
      const g = growing.get(key);
      if (g) used.push(...g.bars); else ready = false;
      if (end + DONE_LAG_MS < serverLow) {
        // 收完的块：拉一次长存
        request(key, sub.iv, end, b => { o.cache.set(key, own(b)); growing.delete(key); });
      } else if (!g || now - g.at >= REFRESH_MS) {
        request(key, sub.iv, end, b => { growing.set(key, { bars: own(b), at: Date.now() }); });
      } else {
        planIn(g.at + REFRESH_MS - now);
      }
    }
    if (!ready) return;
    show(computeProfile(used.filter(b => b.openMs >= fromMs && b.openMs < toMs), lo, hi, rows));
  };

  const onRange = () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(plan, DEBOUNCE_MS);
  };
  o.chart.timeScale().subscribeVisibleLogicalRangeChange(onRange);
  plan();

  return {
    setRows(n) {
      if (n === rows) return;
      rows = n;
      plan();
    },
    setPalette(p) {
      layer.palette = p;
      layer.update();
    },
    dispose() {
      disposed = true;
      clearTimeout(debounceTimer);
      clearTimeout(timer);
      // 图整体重建时 chart/series 已死，退订和 detach 会抛，吞掉
      try {
        o.chart.timeScale().unsubscribeVisibleLogicalRangeChange(onRange);
        o.series.detachPrimitive(layer);
      } catch { /* chart disposed */ }
    },
  };
}
