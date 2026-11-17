import { useId, useMemo, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { cn, fmtDateTime, fmtNum, fmtSignedPct } from '../../lib/utils';
import type { TraderEquityPoint } from '../../types';
import { BASE_EQUITY } from './traderConfig';

/** svg 内部坐标：横 700、竖 280，拉伸铺满容器 */
const W = 700;
const H = 280;

/** 曲线上某个时刻发生的事：开仓画空心圈，平仓赚了绿点、亏了红点；指到那个点时读数行写出来 */
export interface CurveEvent {
  at: number;
  text: string;
  type: 'open' | 'win' | 'loss';
}

const NO_EVENTS: CurveEvent[] = [];

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

const pct = (y: number) => `${y / H * 100}%`;

/**
 * 净值曲线（样式见 ArenaDetail.css .cv）：初始资金画一条虚线，末值在它之上整条线绿、之下红；曲线和虚线之间铺一层同色，往虚线方向渐隐。
 * 上面一行读数（默认最后一个点，指到哪个点读哪个，左右方向键也能逐点走），底下首 / 中 / 末三个时刻。
 * 点是每次醒来记一次，横轴按点等距（跟后端落点一一对应），不是按时间。至少两个点才画
 */
export function EquityCurve({ points, events = NO_EVENTS, base = BASE_EQUITY }: {
  points: TraderEquityPoint[];
  events?: CurveEvent[];
  /** 基准线：每局的注资额 */
  base?: number;
}) {
  const { t } = useTranslation('ai');
  const gid = useId();
  const [hover, setHover] = useState<number | null>(null);
  const n = points.length;

  const v = useMemo(() => {
    const vals = points.map(p => p.equity);
    let lo = Math.min(...vals, base);
    let hi = Math.max(...vals, base);
    const pad = (hi - lo) * 0.1 || base * 0.005;
    lo -= pad;
    hi += pad;
    const ys = vals.map(e => (hi - e) / (hi - lo) * H);
    // 事件挂到时间上最近的那个点；区间外的不挂
    const first = points[0].wakeTime;
    const last = points[points.length - 1].wakeTime;
    const at = new Map<number, CurveEvent[]>();
    for (const e of events) {
      if (e.at < first || e.at > last) continue;
      const i = nearestPoint(points, e.at);
      at.set(i, [...(at.get(i) ?? []), e]);
    }
    return {
      pts: ys.map((y, i) => `${(i / (points.length - 1) * W).toFixed(1)},${y.toFixed(1)}`).join(' '),
      ys, yb: (hi - base) / (hi - lo) * H, at,
    };
  }, [points, events, base]);

  // 换了区间点数变少，指着的位置可能已经出界
  const h = hover != null && hover < n ? hover : null;
  const x = (i: number) => `${i / (n - 1) * 100}%`;
  const p = points[h ?? n - 1];
  const diff = p.equity - base;
  const hovered = h != null ? v.at.get(h) : undefined;
  const up = points[n - 1].equity >= base;

  const move = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    setHover(Math.max(0, Math.min(n - 1, Math.round((e.clientX - r.left) / r.width * (n - 1)))));
  };
  const step = (e: KeyboardEvent<HTMLDivElement>) => {
    const d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
    if (!d) return;
    e.preventDefault();
    setHover(Math.max(0, Math.min(n - 1, (h ?? n - 1) + d)));
  };

  return (
    <>
      <div className="rd">
        <span>{fmtDateTime(p.wakeTime)}</span>
        <span>{t('term.equity')} <b>{fmtNum(p.equity)}</b></span>
        <span className={diff >= 0 ? 'up' : 'dn'}>
          {t('wb.curve.vsBase', { diff: `${diff >= 0 ? '+' : '-'}${fmtNum(Math.abs(diff))}`, pct: fmtSignedPct(diff / base * 100) })}
        </span>
        {hovered && <span><b>{hovered.map(e => e.text).join(' · ')}</b></span>}
      </div>
      <div className={cn('cv', up ? 'up' : 'dn')} tabIndex={0} role="img" aria-label={t('detail.curveAria')}
           onPointerMove={move} onPointerLeave={() => setHover(null)} onKeyDown={step} onBlur={() => setHover(null)}>
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
          <defs>
            {/* 浓的一头贴着曲线：亏的时候曲线在虚线下面，上下对调 */}
            <linearGradient id={gid} x1="0" y1={up ? 0 : 1} x2="0" y2={up ? 1 : 0}>
              <stop offset="0" stopColor="currentColor" stopOpacity=".14" />
              <stop offset="1" stopColor="currentColor" stopOpacity="0" />
            </linearGradient>
          </defs>
          <line className="bl" x1="0" x2={W} y1={v.yb} y2={v.yb} />
          <polygon fill={`url(#${gid})`} points={`${v.pts} ${W},${v.yb.toFixed(1)} 0,${v.yb.toFixed(1)}`} />
          <polyline className="ln" points={v.pts} />
        </svg>
        <span className="bt" style={{ top: pct(v.yb) }}>{t('wb.curve.base', { v: fmtNum(base, 0) })}</span>
        {[...v.at].map(([i, es]) => {
          // 一个点上既开又平：按平仓的盈亏上色
          const close = es.find(e => e.type !== 'open');
          return <i key={i} className={cn('evt', close && (close.type === 'win' ? 'g' : 'r'))} style={{ left: x(i), top: pct(v.ys[i]) }}
                    title={es.map(e => e.text).join(' · ')} />;
        })}
        {h != null && (
          <>
            <i className="cur" style={{ left: x(h) }} />
            <i className="dot" style={{ left: x(h), top: pct(v.ys[h]) }} />
          </>
        )}
      </div>
      <div className="xl">
        {[...new Set([0, Math.floor((n - 1) / 2), n - 1])].map(i => (
          <span key={i} style={{ left: x(i) }}>{fmtDateTime(points[i].wakeTime)}</span>
        ))}
      </div>
    </>
  );
}
