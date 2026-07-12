import { fmtNum } from '../lib/utils';
import { useEffect, useRef } from 'react';
import {
  createChart, CrosshairMode,
  type IChartApi, type ISeriesApi, type UTCTimestamp, type MouseEventParams,
} from 'lightweight-charts';
import { futuresApi } from '../api';
import { useKlineStream } from '../hooks/useKlineStream';
import { useIsDark } from '../hooks/useIsDark';
import { getCoinPriceDecimals } from '../lib/coinConfig';

/** 一根 K：series 只用 OHLC，量/额留给气泡和成交量柱。 */
interface Bar { time: number; openMs: number; open: number; high: number; low: number; close: number; volume: number; quote: number; }

const TZ = new Date().getTimezoneOffset() * 60;             // 本地时区偏移(秒)：横轴显示本地时间且边界对齐
const toBarTime = (ms: number) => Math.floor(ms / 1000) - TZ;
const barDate = (t: number) => new Date((t + TZ) * 1000);   // 反算真实时刻用于格式化
const fmtVol = (n: number) => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(2) + 'K' : n.toFixed(2);
const pad = (n: number) => String(n).padStart(2, '0');
const VOL_UP = 'rgba(38,166,154,.5)', VOL_DOWN = 'rgba(239,83,80,.5)';

/** 气泡 HTML（固定深色，亮/暗主题下都清晰）：时间·开高低收·涨跌·涨跌幅·振幅·量·额。 */
function tooltipHtml(bar: Bar, bars: Bar[], idx: Map<number, number>, d: number, base: string): string {
  const i = idx.get(bar.time);
  const prevClose = (i != null && i > 0) ? bars[i - 1].close : bar.open;   // 昨收=前一根收盘
  const chg = bar.close - prevClose;
  const chgPct = prevClose ? chg / prevClose * 100 : 0;
  const amp = prevClose ? (bar.high - bar.low) / prevClose * 100 : 0;
  const up = chg >= 0, col = up ? '#26a69a' : '#ef5350', sign = up ? '+' : '';
  const dt = barDate(bar.time);
  const tStr = `${pad(dt.getMonth() + 1)}-${pad(dt.getDate())} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`;
  const row = (k: string, v: string, c = '#d1d4dc') =>
    `<div style="display:flex;justify-content:space-between;gap:18px"><span style="color:#6b7280">${k}</span><span style="color:${c};font-weight:700">${v}</span></div>`;
  return `<div style="color:#9ca3af;font-weight:700;margin-bottom:5px;padding-bottom:5px;border-bottom:1px solid #2a2e3a">${tStr}</div>`
    + row('开', fmtNum(bar.open, d)) + row('高', fmtNum(bar.high, d)) + row('低', fmtNum(bar.low, d)) + row('收', fmtNum(bar.close, d))
    + row('涨跌', sign + fmtNum(chg, d), col) + row('涨跌幅', sign + chgPct.toFixed(2) + '%', col)
    + row('振幅', amp.toFixed(2) + '%')
    + row('量', fmtVol(bar.volume) + ' ' + base) + row('额', fmtVol(bar.quote) + ' USDT');
}

/**
 * 合约实时蜡烛图 + 成交量柱 + 悬停气泡。
 * 历史走 REST(含量/额)，当前根走 {@link useKlineStream}(后端实时广播 o/h/l/c/v/q)。仅合约。
 */
export function CandleChart({ symbol, interval, limit = 300 }: { symbol: string; interval: '5m' | '15m'; limit?: number }) {
  const isDark = useIsDark();
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartDivRef = useRef<HTMLDivElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const barsRef = useRef<Bar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const readyRef = useRef(false);
  const hoverRef = useRef<{ time: number | null; x: number; y: number }>({ time: null, x: 0, y: 0 });
  const isDarkRef = useRef(isDark);
  const decimals = getCoinPriceDecimals(symbol);
  const base = symbol.replace('USDT', '');

  const live = useKlineStream(symbol, interval);

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
    const grid = dark ? '#1c1f2a' : '#eceff3', border = dark ? '#1c1f2a' : '#e5e7eb', text = dark ? '#9ca3af' : '#6b7280';

    const chart = createChart(host, {
      width: host.clientWidth, height: host.clientHeight,
      layout: { background: { color: 'transparent' }, textColor: text, fontSize: 11 },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: border, scaleMargins: { top: 0.06, bottom: 0.26 } },  // 上方留蜡烛，下方留量柱
      timeScale: { borderColor: border, timeVisible: true, secondsVisible: false, rightOffset: 5 },
    });
    chartRef.current = chart;

    const candle = chart.addCandlestickSeries({
      upColor: '#26a69a', downColor: '#ef5350', borderUpColor: '#26a69a', borderDownColor: '#ef5350',
      wickUpColor: '#26a69a', wickDownColor: '#ef5350',
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
    });
    candleRef.current = candle;

    const vol = chart.addHistogramSeries({ priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false });
    vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });   // 量柱压底部 18%
    volRef.current = vol;

    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      const tip = tipRef.current; if (!tip) return;
      const t = param.time as number | undefined;
      if (t == null || !param.point) { hoverRef.current.time = null; tip.style.display = 'none'; return; }
      const i = idxRef.current.get(t);
      if (i == null) { tip.style.display = 'none'; return; }
      hoverRef.current = { time: t, x: param.point.x, y: param.point.y };
      showTipRef.current(barsRef.current[i], param.point.x, param.point.y);
    });

    readyRef.current = false;
    futuresApi.klines(symbol, interval, limit).then(raw => {
      const bars: Bar[] = [], idx = new Map<number, number>();
      for (const k of raw) {
        const time = toBarTime(k[0]);
        idx.set(time, bars.length);
        bars.push({ time, openMs: k[0], open: +k[1], high: +k[2], low: +k[3], close: +k[4], volume: +k[5], quote: +k[7] });
      }
      barsRef.current = bars; idxRef.current = idx;
      candle.setData(bars.map(b => ({ time: b.time as UTCTimestamp, open: b.open, high: b.high, low: b.low, close: b.close })));
      vol.setData(bars.map(b => ({ time: b.time as UTCTimestamp, value: b.volume, color: b.close >= b.open ? VOL_UP : VOL_DOWN })));
      chart.timeScale().fitContent();
      readyRef.current = true;
    }).catch(() => { /* 历史失败仍可靠实时累积 */ });

    const ro = new ResizeObserver(() => chart.applyOptions({ width: host.clientWidth, height: host.clientHeight }));
    ro.observe(host);

    return () => {
      ro.disconnect(); chart.remove();
      chartRef.current = null; candleRef.current = null; volRef.current = null;
      readyRef.current = false; barsRef.current = []; idxRef.current = new Map();
    };
  }, [symbol, interval, limit, decimals]);

  // 主题切换：只改颜色，不重建
  useEffect(() => {
    const chart = chartRef.current; if (!chart) return;
    const grid = isDark ? '#1c1f2a' : '#eceff3', border = isDark ? '#1c1f2a' : '#e5e7eb', text = isDark ? '#9ca3af' : '#6b7280';
    chart.applyOptions({
      layout: { textColor: text },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      rightPriceScale: { borderColor: border }, timeScale: { borderColor: border },
    });
  }, [isDark]);

  // 实时：当前根原地更新（蜡烛 + 量柱 + 悬停时刷新气泡）
  useEffect(() => {
    if (!live || !readyRef.current) return;
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
    if (hoverRef.current.time === time) showTipRef.current(bar, hoverRef.current.x, hoverRef.current.y);
  }, [live]);

  return (
    <div ref={wrapRef} className="relative w-full h-full">
      <div ref={chartDivRef} className="absolute inset-0" />
      <div ref={tipRef} style={{
        position: 'absolute', display: 'none', pointerEvents: 'none', zIndex: 5,
        background: 'rgba(13,14,18,.9)', border: '1px solid #2a2e3a', borderRadius: 8, padding: '8px 11px',
        font: '12px/1.6 ui-monospace, Consolas, monospace', minWidth: 154, boxShadow: '0 6px 22px rgba(0,0,0,.45)',
      }} />
    </div>
  );
}
