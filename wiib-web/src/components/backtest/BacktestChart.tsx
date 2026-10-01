import { useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart, createSeriesMarkers, CandlestickSeries, HistogramSeries,
  type DeepPartial, type HandleScrollOptions, type IChartApi, type ISeriesApi,
  type ISeriesMarkersPluginApi, type SeriesMarker, type Time, type UTCTimestamp,
} from 'lightweight-charts';
import { useTranslation } from 'react-i18next';
import { useIsDark } from '../../hooks/useIsDark';
import { useDrawings } from '../chart/useDrawings';
import { DrawToolPopover, DrawToolRail } from '../chart/DrawToolPicker';
import { DrawOverlay } from '../chart/DrawOverlay';
import type { ChartCtx, OhlcBar } from '../../lib/chartDrawings';
import { lwcTheme, rgba } from '../../lib/chartTheme';
import { fmtDateTime } from '../../lib/utils';

/** 与 CandleChart 同款时区约定：横轴按 UTC+8 显示且 bar 边界对齐 */
const TZ = -8 * 3600;
const toBarTime = (ms: number) => (Math.floor(ms / 1000) - TZ) as UTCTimestamp;
/** 与 CandleChart 同款：手机纵向滑动交还给页面滚动，横向平移/捏合缩放保留 */
const SCROLL_OPTS: DeepPartial<HandleScrollOptions> =
  { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false };

/**
 * 通用成交标记：一次成交一条。entry 画方向箭头（多下箭头向上、空上箭头向下），
 * exit 画圆点按 pnl 着色。模式1把每笔 trade 拆成进/出两条，模式2按每次成交（开/加/平/减）各一条。
 */
export interface ChartTradeMark {
  /** 在 bars 里的下标；游标没走到就不显示 */
  barIndex: number;
  time: number;              // ms，贴在哪根蜡烛上
  side: 'LONG' | 'SHORT';
  kind: 'entry' | 'exit';
  /** exit：按盈亏着色（缺省视为 ≥0） */
  pnl?: number;
  /** 标记文字；entry 缺省 多/空 */
  label?: string;
}

interface Props {
  /** 全量 K 线（含预热/上下文段），行 = [openTime, open, high, low, close, volume] */
  bars: number[][];
  marks: ChartTradeMark[];
  /** 显示到的 bar 数（回放游标）；= bars.length 即全量 */
  cursor: number;
  /** 画线存档按 symbol 走，与实盘 Coin 图共用同一套（锚点是绝对时间，天然对齐） */
  symbol: string;
  decimals?: number;
  /** 不传：桌面 420，小屏 min(52vh, 460) */
  height?: number;
  /** 盲测：传回放段首根 openTime(ms)，时间轴/十字线/画线标签显示 D{n} HH:mm 相对时间 */
  blindBaseMs?: number | null;
  /** bar 周期秒数（画线磁吸/命中检测的桶宽），bars 聚合到几分钟就传几分钟；缺省 5m */
  bucketSec?: number;
}

/** 行 → LWC 蜡烛点 */
function toCandle(row: number[]) {
  return { time: toBarTime(row[0]), open: row[1], high: row[2], low: row[3], close: row[4] };
}

/** 行 → 量柱点（涨绿跌红看收盘对开盘），颜色与 CandleChart 同款：40% 透明压在蜡烛下层 */
function toVol(row: number[], up: string, down: string) {
  return { time: toBarTime(row[0]), value: row[5] ?? 0, color: row[4] >= row[1] ? up : down };
}

/** 行 → 画线层 OhlcBar（time 为图表口径的秒） */
function toOhlc(row: number[]): OhlcBar {
  return { time: toBarTime(row[0]), open: row[1], high: row[2], low: row[3], close: row[4] };
}

/** 盲测相对时间：D{第几天} HH:mm（HH:mm 为 UTC+8 时刻，不泄露日期；天按 UTC+8 日历日数，开局那天 D1、过午夜进位） */
function blindLabel(shiftedSec: number, baseShiftedSec: number): string {
  const day = Math.floor(shiftedSec / 86_400) - Math.floor(baseShiftedSec / 86_400) + 1;
  const d = new Date(shiftedSec * 1000);
  const hh = String(d.getUTCHours()).padStart(2, '0');
  const mm = String(d.getUTCMinutes()).padStart(2, '0');
  return `D${day} ${hh}:${mm}`;
}

/**
 * 回测/复盘通用蜡烛图：蜡烛+底部量柱、进出场 markers、前端回放游标、画线工具（全站 DrawingLayer 复用）。
 * 游标小步前进走 update() 增量追加，跳变/回退走 setData() 重切——两条路径都不重建图表。
 */
export function BacktestChart({ bars, marks, cursor, symbol, decimals = 2, height, blindBaseMs, bucketSec = 300 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);
  const drawnRef = useRef(0);           // 已画到的 bar 数
  const lastBarsRef = useRef<number[][] | null>(null);   // bars 换引用（新任务/新分段）必须走全量重切
  const markerCountRef = useRef(-1);    // 上次 setMarkers 的条数，变了才重设
  // 上次 setMarkers 用的那份 allMarkers：换了引用（切语言/换数据）也重设
  const markerSrcRef = useRef<{ atBar: number; marker: SeriesMarker<Time> }[] | null>(null);
  // 画线层的只读上下文：随游标同步维护（图层活得比每帧都久，必须走 ref getter）
  const ohlcRef = useRef<OhlcBar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const blindBaseRef = useRef<number | null>(null);
  blindBaseRef.current = blindBaseMs ?? null;
  const isDark = useIsDark();
  const { t } = useTranslation('strategy');

  const drawings = useDrawings();
  const {
    attach: attachDrawings, tool, setTool, magnet, setMagnet, hiddenAll, setHiddenAll,
    selected: hasSelection, count: drawCount, trash, undo, canUndo,
  } = drawings;

  // 小屏（手机竖屏）默认矮一点，给下方操作按钮留出手指空间
  const [autoHeight] = useState(() => window.innerWidth < 768
    ? Math.min(Math.round(window.innerHeight * 0.52), 460) : 420);
  const h = height ?? autoHeight;

  /** 图表口径秒 → 轴/十字线标签。盲测走相对天序号，正常走真实时间 */
  const fmtShifted = (sec: number) => {
    const base = blindBaseRef.current;
    if (base != null) return blindLabel(sec, Math.floor(base / 1000) - TZ);
    return fmtDateTime((sec + TZ) * 1000);
  };

  // 进场箭头的缺省文字先查出来再进 useMemo：切语言时这两个串会变，memo 跟着重算整份 markers。
  // 这里不写 i18n.language——useMemo 里它不参与计算，exhaustive-deps 会判成多余依赖报 warning
  const longText = t('side.long');
  const shortText = t('side.short');

  // 标记配色现读 token，换主题这次渲染就拿到新色（getComputedStyle 一次的开销可忽略）
  const { gain, loss } = lwcTheme();

  // markers 预排序：按所在 bar 升序，游标推进时按可见数量切片
  const allMarkers = useMemo(() => {
    const out: { atBar: number; marker: SeriesMarker<Time> }[] = marks.map(m => {
      const isLong = m.side === 'LONG';
      const marker: SeriesMarker<Time> = m.kind === 'entry'
        ? {
          time: toBarTime(m.time), position: isLong ? 'belowBar' : 'aboveBar',
          shape: isLong ? 'arrowUp' : 'arrowDown', color: isLong ? gain : loss,
          text: m.label ?? (isLong ? longText : shortText),
        }
        : {
          time: toBarTime(m.time), position: isLong ? 'aboveBar' : 'belowBar',
          shape: 'circle', color: (m.pnl ?? 0) >= 0 ? gain : loss,
          text: m.label,
        };
      return { atBar: m.barIndex, marker };
    });
    return out.sort((a, b) => a.atBar - b.atBar);
  }, [marks, gain, loss, longText, shortText]);

  // 建图（主题/高度/币种变化时重建，颜色 token 才能生效；画线层 attach/detach 同一 effect 成对做）
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const th = lwcTheme();
    const chart = createChart(el, {
      ...th.options,
      width: el.clientWidth,
      height: h,
      handleScroll: SCROLL_OPTS,
      // 底部 22% 让给量柱（量柱自己的 scale 压在 82%~100%），蜡烛不与量柱重叠
      rightPriceScale: { ...th.options.rightPriceScale, scaleMargins: { top: 0.08, bottom: 0.22 } },
      timeScale: {
        ...th.options.timeScale, timeVisible: true, secondsVisible: false,
        // 盲测时间脱敏在 tick 一层做：真实日期不上轴
        tickMarkFormatter: (time: Time) => {
          const base = blindBaseRef.current;
          return base == null ? null : blindLabel(time as number, Math.floor(base / 1000) - TZ);
        },
      },
      localization: {
        timeFormatter: (time: Time) => fmtShifted(time as number),
      },
    });
    const series = chart.addSeries(CandlestickSeries, {
      ...th.candle,
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
    });
    // 成交量：独立隐藏价格轴（priceScaleId ''）叠在主图底部，不占用蜡烛的价格刻度
    const vol = chart.addSeries(HistogramSeries, {
      priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false,
    });
    vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    chartRef.current = chart;
    seriesRef.current = series;
    volRef.current = vol;
    markersRef.current = createSeriesMarkers(series, []);
    drawnRef.current = 0;
    markerCountRef.current = -1;
    ohlcRef.current = [];
    idxRef.current = new Map();

    const detachDrawings = attachDrawings({
      chart, series, host: el, symbol, decimals, scrollOpts: SCROLL_OPTS,
      ctx: {
        bars: () => ohlcRef.current,
        idx: () => idxRef.current,
        bucketSec,
        timeScale: chart.timeScale(),
        series,
      } satisfies ChartCtx,
      fmtTime: fmtShifted,
    });

    const onResize = () => chart.applyOptions({ width: el.clientWidth });
    const ro = new ResizeObserver(onResize);
    ro.observe(el);
    return () => {
      ro.disconnect();
      detachDrawings();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      volRef.current = null;
      markersRef.current = null;
    };
    // fmtShifted/attachDrawings 稳定（ref/useCallback），不进依赖；bucketSec 变化（切周期）需重建图重挂画线层
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDark, h, symbol, decimals, bucketSec]);

  // 游标应用：小步前进 update 追加；回退/跳变 setData 重切。画线层的 bars/idx 同步维护
  useEffect(() => {
    const series = seriesRef.current;
    const vol = volRef.current;
    const chart = chartRef.current;
    if (!series || !vol || !chart) return;
    const target = Math.min(Math.max(cursor, 0), bars.length);
    const drawn = drawnRef.current;
    const sameBars = lastBarsRef.current === bars;
    const th = lwcTheme();
    const vUp = rgba(th.gain, .4), vDn = rgba(th.loss, .4);

    if (sameBars && target === drawn && drawn !== 0) {
      // 数据没动
    } else if (sameBars && target > drawn && target - drawn <= 600 && drawn > 0) {
      for (let i = drawn; i < target; i++) {
        series.update(toCandle(bars[i]));
        vol.update(toVol(bars[i], vUp, vDn));
        const o = toOhlc(bars[i]);
        idxRef.current.set(o.time, ohlcRef.current.length);
        ohlcRef.current.push(o);
      }
    } else {
      const shown = bars.slice(0, target);
      series.setData(shown.map(toCandle));
      vol.setData(shown.map(r => toVol(r, vUp, vDn)));
      const ohlc: OhlcBar[] = new Array(target);
      const idx = new Map<number, number>();
      for (let i = 0; i < target; i++) {
        ohlc[i] = toOhlc(bars[i]);
        idx.set(ohlc[i].time, i);
      }
      ohlcRef.current = ohlc;
      idxRef.current = idx;
      // 只在"从空到有"那一下自适应视野；分段追加/回放重切都保留用户当前缩放
      if (drawn === 0 && target > 0) chart.timeScale().fitContent();
    }
    drawnRef.current = target;
    lastBarsRef.current = bars;

    // 可见 markers：开/平仓 bar 已入画面才显示
    const visible: SeriesMarker<Time>[] = [];
    for (const m of allMarkers) {
      if (m.atBar < target) visible.push(m.marker);
      else break;
    }
    if (visible.length !== markerCountRef.current || markerSrcRef.current !== allMarkers) {
      markersRef.current?.setMarkers(visible);
      markerCountRef.current = visible.length;
      markerSrcRef.current = allMarkers;
    }
    // 后五项是建图依赖：图重建后 drawnRef 归零，跟着重灌一次
  }, [bars, cursor, allMarkers, isDark, h, symbol, decimals, bucketSec]);

  // 磁吸/显隐/删除跟画线工具住一起（竖栏底部；手机在顶栏那一行）
  const toolProps = {
    tool, onSelect: setTool,
    magnet, onToggleMagnet: () => setMagnet(!magnet),
    hiddenAll, onToggleHidden: () => setHiddenAll(!hiddenAll), hideDisabled: !drawCount,
    onUndo: undo, undoDisabled: !canUndo,
    onTrash: trash, trashDisabled: !hasSelection && !drawCount,
    trashTitle: hasSelection ? t('chart.deleteSelected') : t('chart.clearAll'),
  };

  return (
    <div>
      {/* 顶栏：桌面端画线工具在左竖栏，这行只剩手机的工具弹层 */}
      <div className="flex items-center gap-2.5 mb-2.5 flex-wrap md:hidden">
        <DrawToolPopover {...toolProps} />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-[34px_1fr] border-t border-foreground">
        <DrawToolRail className="hidden md:flex" {...toolProps} />
        {/* 图表主体（relative：文字标注输入、属性条的定位基准） */}
        <div className="relative w-full" style={{ height: h }}>
          <div ref={containerRef} className="absolute inset-0" />
          <DrawOverlay d={drawings} />
        </div>
      </div>
    </div>
  );
}
