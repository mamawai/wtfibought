import { useId, useMemo, useState, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { smoothPath } from '../../lib/smoothPath';
import { cn, DAY_MS, fmtDate, fmtDateTime, fmtNum, fmtSignedPct } from '../../lib/utils';
import type { TraderEquityPoint } from '../../types';
import { BASE_EQUITY } from './traderConfig';

/** svg 内部坐标：横 1000、竖 300，拉伸铺满容器 */
const W = 1000;
const H = 300;
/** 网格线、基准线离末值标签不到这么远就不画字，免得叠在一起 */
const LABEL_GAP = 20;
/** 日期分隔最多标这么多个，多了隔几天标一个 */
const MAX_DAY_LABELS = 8;

/** 曲线上某个时刻发生的事（开仓、平仓），悬停到最近那个点时写进提示 */
export interface CurveEvent {
  at: number;
  text: string;
}

const NO_EVENTS: CurveEvent[] = [];

/** 北京时间（+8，无夏令时）的第几天 */
const bjDay = (ms: number) => Math.floor((ms + 8 * 3_600_000) / DAY_MS);

/** 时间上离 at 最近的那个点（点按时间升序，二分；一样近取前一个） */
function nearestPoint(points: TraderEquityPoint[], at: number): number {
  let lo = 0;
  let hi = points.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (points[mid].wakeTime < at) lo = mid + 1;
    else hi = mid;
  }
  return lo > 0 && at - points[lo - 1].wakeTime <= points[lo].wakeTime - at ? lo - 1 : lo;
}

/** 网格间距：1 / 2 / 2.5 / 5 乘 10 的整数次幂 */
function niceStep(raw: number): number {
  const p = Math.pow(10, Math.floor(Math.log10(raw)));
  const f = raw / p;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
}

const pct = (y: number) => `${y / H * 100}%`;

/**
 * 本局净值：初始资金那条虚线以上画绿、以下画红，往虚线方向渐隐；右边一栏是刻度和现值，
 * 每天零点一条分隔线。点是每次醒来记一次，横轴按点等距（跟后端落点一一对应），不是按时间
 */
export function EquityCurve({ points, events = NO_EVENTS, base = BASE_EQUITY }: {
  points: TraderEquityPoint[];
  events?: CurveEvent[];
  /** 基准线：每局的注资额 */
  base?: number;
}) {
  const { t } = useTranslation('ai');
  const uid = useId();
  const [hover, setHover] = useState<number | null>(null);

  const v = useMemo(() => {
    const vals = points.map(p => p.equity);
    let lo = Math.min(...vals, base);
    let hi = Math.max(...vals, base);
    const pad = (hi - lo) * 0.14 || base * 0.005;
    lo -= pad;
    hi += pad;
    const step = W / (points.length - 1);
    const Y = (x: number) => H - (x - lo) / (hi - lo) * H;
    const ys = vals.map(Y);
    const yb = Y(base);
    const yEnd = ys[ys.length - 1];
    const gs = niceStep((hi - lo) / 4);
    const grid: { y: number; v: number }[] = [];
    for (let g = Math.ceil(lo / gs) * gs; g <= hi; g += gs) {
      const y = Y(g);
      if (Math.abs(y - yb) >= LABEL_GAP && Math.abs(y - yEnd) >= LABEL_GAP) grid.push({ y, v: g });
    }
    // 北京时间换日的那个点前面画一条分隔
    const dayAt: number[] = [];
    for (let i = 1; i < points.length; i++) {
      if (bjDay(points[i].wakeTime) !== bjDay(points[i - 1].wakeTime)) dayAt.push(i);
    }
    const every = Math.ceil(dayAt.length / MAX_DAY_LABELS);
    const days = dayAt.filter((_, k) => k % every === 0)
      .map(i => ({ x: i * step / W * 100, label: fmtDate(points[i].wakeTime).slice(5) }));
    // 事件挂到时间上最近的那个点；区间外的不挂
    const first = points[0].wakeTime;
    const last = points[points.length - 1].wakeTime;
    const eventsAt = new Map<number, string[]>();
    for (const e of events) {
      if (e.at < first || e.at > last) continue;
      const i = nearestPoint(points, e.at);
      eventsAt.set(i, [...(eventsAt.get(i) ?? []), e.text]);
    }
    return {
      line: smoothPath(ys, 0, step), ys, yb, yEnd, step, grid, days, eventsAt,
      top: Math.min(...ys), bottom: Math.max(...ys),
    };
  }, [points, events, base]);

  const area = `${v.line} L${W} ${v.yb.toFixed(1)} L0 ${v.yb.toFixed(1)} Z`;
  // 线的颜色在基准线处切开，切口往下挪 1.5，正好压在基准线上的那段算绿
  const cut = ((v.yb + 1.5) / H).toFixed(4);
  const last = points[points.length - 1];
  const endUp = last.equity >= base;
  const ids = { line: `${uid}-l`, up: `${uid}-u`, dn: `${uid}-d`, above: `${uid}-a`, below: `${uid}-b` };

  const move = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const p = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    setHover(Math.round(p * (points.length - 1)));
  };

  const h = hover != null && hover < points.length ? hover : null;
  const hp = h != null ? points[h] : null;
  const hx = h != null ? h * v.step / W * 100 : 0;
  return (
    <div className="tr-cv-box num">
      <div className="tr-cv" onPointerMove={move} onPointerLeave={() => setHover(null)}>
        {v.grid.map(g => <span key={g.v} className="tr-cv-g" style={{ top: pct(g.y) }}><b>{fmtNum(g.v, 0)}</b></span>)}
        {v.days.map(d => <span key={d.x} className="tr-cv-d" style={{ left: `${d.x}%` }}><b>{d.label}</b></span>)}
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true" className="reveal">
          <defs>
            <linearGradient id={ids.line} gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2={H}>
              <stop className="s-up" offset={cut} />
              <stop className="s-dn" offset={cut} />
            </linearGradient>
            <linearGradient id={ids.up} gradientUnits="userSpaceOnUse" x1="0" y1={v.top} x2="0" y2={v.yb}>
              <stop className="s-up" offset="0" stopOpacity=".24" />
              <stop className="s-up" offset="1" stopOpacity="0" />
            </linearGradient>
            <linearGradient id={ids.dn} gradientUnits="userSpaceOnUse" x1="0" y1={v.yb} x2="0" y2={v.bottom}>
              <stop className="s-dn" offset="0" stopOpacity="0" />
              <stop className="s-dn" offset="1" stopOpacity=".22" />
            </linearGradient>
            <clipPath id={ids.above}><rect x="0" y="-10" width={W} height={v.yb + 10} /></clipPath>
            <clipPath id={ids.below}><rect x="0" y={v.yb} width={W} height={H + 10 - v.yb} /></clipPath>
          </defs>
          <path d={area} fill={`url(#${ids.up})`} clipPath={`url(#${ids.above})`} />
          <path d={area} fill={`url(#${ids.dn})`} clipPath={`url(#${ids.below})`} />
          <path d={v.line} fill="none" stroke={`url(#${ids.line})`} strokeWidth="2.25"
                strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="tr-cv-base" style={{ top: pct(v.yb) }}>
          {Math.abs(v.yEnd - v.yb) >= LABEL_GAP && <b>{t('wb.curve.base', { v: fmtNum(base, 0) })}</b>}
        </span>
        <i className={cn('tr-cv-end reveal-late', endUp ? 'up' : 'dn')} style={{ top: pct(v.yEnd) }} />
        <span className={cn('tr-cv-tag reveal-late', endUp ? 'up' : 'dn')} style={{ top: pct(v.yEnd) }}>{fmtNum(last.equity)}</span>
        {hp && h != null && (
          <>
            <i className="tr-cv-xh" style={{ left: `${hx}%` }} />
            <i className={cn('tr-cv-xd', hp.equity >= base ? 'up' : 'dn')} style={{ left: `${hx}%`, top: pct(v.ys[h]) }} />
            {/* 提示框放在点的另一侧：点靠右就翻到左边，点在上半截就放到点下面 */}
            <span className="tr-cv-tip" style={{
              left: `${hx}%`, top: pct(v.ys[h]),
              transform: `translate(${hx > 70 ? 'calc(-100% - 14px)' : '14px'}, ${v.ys[h] < H / 2 ? '18px' : 'calc(-100% - 18px)'})`,
            }}>
              <b>{fmtNum(hp.equity)}</b>
              <span className={cn('d', hp.equity >= base ? 'up' : 'dn')}>
                {t('wb.curve.vsBase', {
                  diff: `${hp.equity >= base ? '+' : '-'}${fmtNum(Math.abs(hp.equity - base))}`,
                  pct: fmtSignedPct((hp.equity - base) / base * 100),
                })}
              </span>
              <em>{[fmtDateTime(hp.wakeTime), ...(v.eventsAt.get(h) ?? [])].join(' · ')}</em>
            </span>
          </>
        )}
      </div>
    </div>
  );
}
