import { useId, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { AssetSnapshot } from '../types';
import { Seg } from './ui/seg';
import { cn, fmtSigned } from '../lib/utils';

interface Props {
  data: AssetSnapshot[];
}

type NumKey = Exclude<keyof AssetSnapshot, 'date'>;

// 四分类收益曲线：crypto 含币合约，大宗商品含金/油合约。表里存词表 key，画的时候再查——存翻译结果会在模块加载那一刻定死
const CUMULATIVE: { key: NumKey; nameKey: string; color: string }[] = [
  { key: 'profit', nameKey: 'cat.totalProfit', color: 'var(--color-foreground)' },
  { key: 'cryptoProfit', nameKey: 'cat.crypto', color: 'var(--color-primary)' },
  { key: 'commodityProfit', nameKey: 'cat.commodity', color: 'var(--color-warning)' },
  { key: 'bstockProfit', nameKey: 'cat.bstock', color: 'var(--color-gain)' },
  { key: 'predictionProfit', nameKey: 'cat.prediction', color: 'var(--color-info)' },
];
const DAILY: typeof CUMULATIVE = [
  { key: 'dailyProfit', nameKey: 'cat.dailyProfit', color: 'var(--color-foreground)' },
  { key: 'dailyCryptoProfit', nameKey: 'cat.crypto', color: 'var(--color-primary)' },
  { key: 'dailyCommodityProfit', nameKey: 'cat.commodity', color: 'var(--color-warning)' },
  { key: 'dailyBstockProfit', nameKey: 'cat.bstock', color: 'var(--color-gain)' },
  { key: 'dailyPredictionProfit', nameKey: 'cat.prediction', color: 'var(--color-info)' },
];

/** 画布（viewBox）宽高和上下留白 */
const VW = 600;
const VH = 248;
const PAD = 8;

/**
 * 持仓页「收益」面板：总收益 + 四类的曲线（样式见 pages/Portfolio.css .pc）。
 * 不弹浮层：上面一行读数跟着光标走，移开回到最新一天；聚焦后左右方向键逐日看。
 */
export function ProfitChart({ data }: Props) {
  const { t } = useTranslation('portfolio');
  const gradId = useId();
  const [mode, setMode] = useState<'cumulative' | 'daily'>('cumulative');
  const [range, setRange] = useState<'7' | '14' | '30'>('7');
  // 光标停在第几天；null = 没指着，读数看最新一天
  const [cur, setCur] = useState<number | null>(null);

  if (data.length === 0) {
    return <div className="pc-none">{t('chart.noHistory')}</div>;
  }

  const rows = mode === 'daily' ? data.slice(-Number(range)) : data;
  const series = mode === 'daily' ? DAILY : CUMULATIVE;
  const n = rows.length;
  const val = (i: number, key: NumKey) => rows[i][key] ?? 0;

  const all = rows.flatMap((_, i) => series.map(s => val(i, s.key)));
  const mn = Math.min(0, ...all);
  const mx = Math.max(0, ...all);
  const x = (i: number) => n > 1 ? i / (n - 1) * VW : VW / 2;
  const y = (v: number) => VH - PAD - (v - mn) / (mx - mn || 1) * (VH - PAD * 2);
  // 纵轴取四档左右的整数刻度
  const stepRaw = (mx - mn) / 4;
  const mag = 10 ** Math.floor(Math.log10(stepRaw || 1));
  const step = Math.ceil(stepRaw / mag) * mag || 1;
  const ticks: number[] = [];
  for (let k = Math.ceil(mn / step); k * step <= mx; k++) ticks.push(k * step);
  const line = (key: NumKey) => rows.map((_, i) => `${x(i).toFixed(1)},${y(val(i, key)).toFixed(1)}`).join(' ');

  const at = cur ?? n - 1;
  const day = (i: number) => rows[i].date.substring(5);

  /** 光标横坐标 → 最近的那一天 */
  const pick = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    setCur(Math.max(0, Math.min(n - 1, Math.round((e.clientX - r.left) / r.width * (n - 1)))));
  };
  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const d = e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0;
    if (!d) return;
    e.preventDefault();
    setCur(Math.max(0, Math.min(n - 1, at + d)));
  };

  return (
    <>
      <div className="pc-top">
        <Seg value={mode} onChange={m => { setMode(m); setCur(null); }} label={t('ov.profit')} options={[
          { value: 'cumulative', label: t('chart.cumulative') },
          { value: 'daily', label: t('cat.dailyProfit') },
        ]} />
        {mode === 'daily' && (
          <Seg value={range} onChange={r => { setRange(r); setCur(null); }} label={t('cat.dailyProfit')}
               options={(['7', '14', '30'] as const).map(d => ({ value: d, label: t('chart.range', { days: d }) }))} />
        )}
      </div>
      <div className="pc-rd">
        <b>{day(at)}</b>
        {series.map(s => {
          const v = val(at, s.key);
          return (
            <span key={s.key}>
              <i style={{ '--c': s.color } as CSSProperties} />{t(s.nameKey)} <b className={v >= 0 ? 'up' : 'dn'}>{fmtSigned(v)}</b>
            </span>
          );
        })}
      </div>
      <div className="pc" tabIndex={0} role="img" aria-label={t('chart.profitAria')}
           onPointerMove={pick} onPointerLeave={() => setCur(null)} onKeyDown={onKey}>
        <svg viewBox={`0 0 ${VW} ${VH}`} preserveAspectRatio="none" aria-hidden="true">
          <defs>
            <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" style={{ stopColor: 'var(--color-foreground)', stopOpacity: 0.12 }} />
              <stop offset="1" style={{ stopColor: 'var(--color-foreground)', stopOpacity: 0 }} />
            </linearGradient>
          </defs>
          {ticks.map(v => <line key={v} className={v === 0 ? 'gl zl' : 'gl'} x1={0} x2={VW} y1={y(v)} y2={y(v)} />)}
          {n > 1 && (
            <>
              {/* 累计模式下总收益底下垫一层渐隐的面 */}
              {mode === 'cumulative' && <polygon fill={`url(#${gradId})`} points={`${line(series[0].key)} ${VW},${y(0)} 0,${y(0)}`} />}
              {series.slice(1).map(s => <polyline key={s.key} style={{ stroke: s.color }} points={line(s.key)} />)}
              <polyline className="tot" style={{ stroke: series[0].color }} points={line(series[0].key)} />
            </>
          )}
        </svg>
        {/* 只有一天：总收益就一个点。svg 是拉伸的，点用 html 画才是圆的 */}
        {n < 2 && (
          <i className="dot" style={{ left: `${x(0) / VW * 100}%`, top: `${y(val(0, series[0].key)) / VH * 100}%`, background: series[0].color }} />
        )}
        {cur != null && <i className="cur" style={{ left: `${x(cur) / VW * 100}%` }} />}
        {ticks.map(v => <span key={v} className="yl" style={{ top: `${y(v) / VH * 100}%` }}>{v === 0 ? '0' : fmtSigned(v, 0)}</span>)}
      </div>
      <div className={cn('pc-x', n < 2 && 'one')}>
        {[...new Set([0, Math.floor((n - 1) / 2), n - 1])].map(i => <span key={i}>{day(i)}</span>)}
      </div>
    </>
  );
}
