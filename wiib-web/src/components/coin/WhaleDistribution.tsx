import { Fragment, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp } from 'lucide-react';
import type { WhaleBuckets } from '../../api';
import { cn, fmtMoney } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';

/** 视角：开仓价分布 / 强平价分布，两套桶宽不同（0.25% / 0.5% 标记价） */
export type WhaleView = 'entry' | 'liq';

/** 快照价上下各多少行 */
const HALF_ROWS = 12;
const ROW_H = 20;
/** 快照价那条分隔线的高 */
const DIVIDER_H = 18;
/** 轴要罩住多少名义，剩下的进上下两条溢出行 */
const COVER = 0.95;
/** 轴半径占快照价的下限、上限 */
const MIN_RADIUS_PCT = 0.05;
const MAX_RADIUS_PCT = 0.5;

/** 一行 = 一个价格区间 [lo, hi)；j 是相对快照价的行号，0 是上方第一行、-1 是下方第一行 */
interface Row {
  j: number;
  lo: number;
  hi: number;
  long: number;
  short: number;
}

/** 两侧名义合计 */
interface Sum {
  long: number;
  short: number;
}

interface Axis {
  /** 2×HALF_ROWS 行，从高到低 */
  rows: Row[];
  /** 轴上边界之上、下边界之下的名义 */
  over: Sum;
  under: Sum;
  top: number;
  bottom: number;
}

/** 桶相对快照价差几格 */
interface Rel {
  r: number;
  long: number;
  short: number;
}

/**
 * 桶 → 等距价格轴，格子锚在快照价上。
 * 桶宽是快照价的 1/400（开仓）或 1/200（强平），快照价落在桶边界上，没有哪一行跨快照价。
 */
function buildAxis({ width, buckets }: WhaleBuckets, price: number): Axis {
  const p = Math.round(price / width);
  const rel: Rel[] = buckets.map(([lower, long, short]) => ({ r: Math.round(lower / width) - p, long, short }));
  const m = pickStep(rel, price, width);
  const step = m * width;

  const rows: Row[] = [];
  for (let j = HALF_ROWS - 1; j >= -HALF_ROWS; j--) {
    rows.push({ j, lo: price + j * step, hi: price + (j + 1) * step, long: 0, short: 0 });
  }
  const over: Sum = { long: 0, short: 0 };
  const under: Sum = { long: 0, short: 0 };
  for (const b of rel) {
    const j = Math.floor(b.r / m);
    // rows 按 j 从大到小排，下标就是 (HALF_ROWS-1) - j
    const cell = j > HALF_ROWS - 1 ? over : j < -HALF_ROWS ? under : rows[HALF_ROWS - 1 - j];
    cell.long += b.long;
    cell.short += b.short;
  }
  return { rows, over, under, top: price + HALF_ROWS * step, bottom: price - HALF_ROWS * step };
}

/**
 * 一行占几个原始桶。每个桶先算出「要被轴装下，行高至少得多大」，再按名义从近到远累计，
 * 罩住 COVER 就取那一档；最后把轴半径夹回快照价的 MIN~MAX。
 */
function pickStep(rel: Rel[], price: number, width: number): number {
  const lo = Math.ceil((price * MIN_RADIUS_PCT) / (HALF_ROWS * width));
  const hi = Math.floor((price * MAX_RADIUS_PCT) / (HALF_ROWS * width));
  // 上方要 floor(r/m) ≤ HALF_ROWS-1，下方要 floor(r/m) ≥ -HALF_ROWS，反解出各自的最小 m
  const need = rel
    .map(b => ({ m: b.r >= 0 ? Math.floor(b.r / HALF_ROWS) + 1 : Math.ceil(-b.r / HALF_ROWS), n: b.long + b.short }))
    .sort((a, b) => a.m - b.m);
  const total = need.reduce((s, x) => s + x.n, 0);
  let acc = 0;
  let m = lo;
  for (const x of need) {
    acc += x.n;
    m = x.m;
    if (acc >= total * COVER) break;
  }
  return Math.min(hi, Math.max(lo, m));
}

/**
 * 大户名义额按价位逐行：快照价上下各等高等宽 12 行，纵向间距就是价格距离，空价位也占位。
 * 多头柱向左、空头柱向右，两侧共用一个比例，比例只按轴内算。
 * 轴外的名义收进上下两条溢出行，只给数字不画柱子。悬停或点一行，下方读数给这行的区间与两侧名义。
 */
export function WhaleDistribution({ entry, liq, price, symbol, view, onViewChange, className }: {
  entry: WhaleBuckets;
  liq: WhaleBuckets;
  /** 快照价（Hyperliquid 标记价） */
  price: number;
  symbol: string;
  view: WhaleView;
  onViewChange: (v: WhaleView) => void;
  className?: string;
}) {
  const { t } = useTranslation('market');
  // 选中行的行号；换视角清掉
  const [active, setActive] = useState<number | null>(null);

  const source = view === 'entry' ? entry : liq;
  const axis = useMemo(() => buildAxis(source, price), [source, price]);
  // 行数固定，高度跟数据无关
  const height = 2 * HALF_ROWS * ROW_H + 2 * ROW_H + DIVIDER_H;

  const max = Math.max(...axis.rows.map(r => Math.max(r.long, r.short)));
  const px = (v: number) => formatCoinPrice(symbol, v);
  const money = (v: number) => (v > 0 ? `$${fmtMoney(v)}` : t('whale.none'));
  const mid = (r: Row) => (r.lo + r.hi) / 2;
  // 价格列和百分比列都取行中点，两列一个口径
  const dist = (v: number) => {
    const d = (v / price - 1) * 100;
    return `${d >= 0 ? '+' : ''}${d.toFixed(Math.abs(d) >= 100 ? 0 : 1)}%`;
  };
  // 中间两列按最长的字定宽，各行对齐；百分号比数字宽，多给 1ch
  const priceCh = Math.max(px(price).length, ...axis.rows.map(r => px(mid(r)).length));
  const distCh = Math.max(5, ...axis.rows.map(r => dist(mid(r)).length)) + 1;
  const cols = `1fr ${priceCh}ch ${distCh}ch 1fr`;
  const selected = axis.rows.find(r => r.j === active);

  const switchView = (v: WhaleView) => {
    setActive(null);
    onViewChange(v);
  };
  const bar = (v: number) => ({ width: `${(v / max) * 100}%`, minWidth: 2 });

  const divider = (
    <div className="num flex items-center gap-2 text-[11.5px] mute" style={{ height: DIVIDER_H }}>
      <i className="flex-1 border-t border-dashed border-muted-foreground" />
      {t('whale.snapshotPrice')} {px(price)}
      <i className="flex-1 border-t border-dashed border-muted-foreground" />
    </div>
  );

  /** 溢出行：轴外两侧的名义，没有就不画 */
  const overflow = (s: Sum, edge: number, up: boolean) => (s.long + s.short === 0 ? null : (
    <div className="num flex items-center gap-1.5 text-[11.5px] mute" style={{ height: ROW_H }}>
      {up ? <ChevronUp className="w-3 h-3 shrink-0" /> : <ChevronDown className="w-3 h-3 shrink-0" />}
      <span className="truncate">
        {t(up ? 'whale.axisOver' : 'whale.axisUnder', { price: px(edge), long: money(s.long), short: money(s.short) })}
      </span>
    </div>
  ));

  return (
    <div className={className}>
      <div className="flex items-center justify-between flex-wrap gap-x-4 gap-y-2 mb-3">
        <div className="flex items-center gap-4 text-[12px] mute">
          <span className="inline-flex items-center gap-1.5"><i className="block w-2.5 h-2.5 bg-gain" />{t('whale.legendLong')}</span>
          <span className="inline-flex items-center gap-1.5"><i className="block w-2.5 h-2.5 bg-loss" />{t('whale.legendShort')}</span>
        </div>
        <div className="seg">
          <button type="button" className={cn(view === 'entry' && 'on')} onClick={() => switchView('entry')}>
            {t('whale.viewEntry')}
          </button>
          <button type="button" className={cn(view === 'liq' && 'on')} onClick={() => switchView('liq')}>
            {t('whale.viewLiq')}
          </button>
        </div>
      </div>

      {source.buckets.length === 0 ? (
        <div className="flex items-center justify-center text-[13px] mute" style={{ height }}>{t('whale.none')}</div>
      ) : (
        // key 跟视角：切视角重挂，入场动画再走一遍
        <div key={view} className="reveal" style={{ minHeight: height }} onMouseLeave={() => setActive(null)}>
          {overflow(axis.over, axis.top, true)}
          {axis.rows.map((r, i) => (
            <Fragment key={r.j}>
              {/* 快照价压在 j=0 与 j=-1 的交界，也就是第 HALF_ROWS 行前面 */}
              {i === HALF_ROWS && divider}
              <div
                className="grid items-center gap-x-2 cursor-default"
                style={{ gridTemplateColumns: cols, height: ROW_H, background: r.j === active ? 'var(--color-card-2)' : undefined }}
                onMouseEnter={() => setActive(r.j)}
                onClick={() => setActive(r.j)}
              >
                <div className="flex justify-end h-3">{r.long > 0 && <i className="block h-full bg-gain" style={bar(r.long)} />}</div>
                <span className="num text-[12px] text-right">{px(mid(r))}</span>
                <span className="num text-[12px] text-right mute">{dist(mid(r))}</span>
                <div className="flex h-3">{r.short > 0 && <i className="block h-full bg-loss" style={bar(r.short)} />}</div>
              </div>
            </Fragment>
          ))}
          {overflow(axis.under, axis.bottom, false)}
        </div>
      )}

      <div className="num mt-2 min-h-[18px] text-[12px] mute">
        {selected
          ? t('whale.bucketTip', {
            from: px(selected.lo), to: px(selected.hi),
            long: money(selected.long), short: money(selected.short),
          })
          : t('whale.readoutHint')}
      </div>
    </div>
  );
}
