import { Fragment, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { WhaleBuckets } from '../../api';
import { cn, fmtMoney } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';

/** 视角：开仓价分布 / 强平价分布，两套桶宽不同（0.25% / 0.5% 标记价） */
export type WhaleView = 'entry' | 'liq';

/** 价位行数上限，超了相邻桶两两合并 */
const MAX_ROWS = 30;
const ROW_H = 20;
/** 快照价那条分隔线的高 */
const DIVIDER_H = 18;

interface Level {
  lower: number;
  width: number;
  long: number;
  short: number;
}

/**
 * 有仓位的价位，从高到低。多于 MAX_ROWS 时桶宽翻倍、相邻桶合并，直到放得下。
 * 桶下界都是桶宽的整数倍，按下标合并。
 */
function toLevels({ width, buckets }: WhaleBuckets): Level[] {
  for (let k = 1; ; k *= 2) {
    const byIdx = new Map<number, Level>();
    for (const [lower, long, short] of buckets) {
      const idx = Math.floor(Math.round(lower / width) / k);
      const lv = byIdx.get(idx) ?? { lower: k === 1 ? lower : idx * k * width, width: k * width, long: 0, short: 0 };
      lv.long += long;
      lv.short += short;
      byIdx.set(idx, lv);
    }
    if (byIdx.size <= MAX_ROWS) return [...byIdx.values()].sort((a, b) => b.lower - a.lower);
  }
}

/**
 * 大户名义额按价位逐行：只画有仓位的价位，一行一个等高，从高到低；多头柱向左、空头柱向右，两侧共用一个比例。
 * 价格远近看每行的距快照价百分比；快照价是插在对应位置的一条分隔线。悬停或点一行，下方读数给这个价位的两侧名义。
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
  // 选中行的下界；换视角清掉
  const [active, setActive] = useState<number | null>(null);

  const entryLevels = useMemo(() => toLevels(entry), [entry]);
  const liqLevels = useMemo(() => toLevels(liq), [liq]);
  const levels = view === 'entry' ? entryLevels : liqLevels;
  // 高度按两个视角里行多的那个定，切视角不跳
  const height = Math.max(entryLevels.length, liqLevels.length, 4) * ROW_H + DIVIDER_H;

  const max = Math.max(0, ...levels.map(l => Math.max(l.long, l.short)));
  const px = (v: number) => formatCoinPrice(symbol, v);
  const money = (v: number) => (v > 0 ? `$${fmtMoney(v)}` : t('whale.none'));
  const dist = (l: Level) => {
    const d = ((l.lower + l.width / 2) / price - 1) * 100;
    return `${d >= 0 ? '+' : ''}${d.toFixed(Math.abs(d) >= 100 ? 0 : 1)}%`;
  };
  // 中间两列按最长的字定宽，各行对齐；百分号比数字宽，多给 1ch
  const priceCh = Math.max(px(price).length, ...levels.map(l => px(l.lower).length));
  const distCh = Math.max(5, ...levels.map(l => dist(l).length)) + 1;
  const cols = `1fr ${priceCh}ch ${distCh}ch 1fr`;
  // 分隔线插在第一个下界 ≤ 快照价的价位前面；全在上方就放最后
  const cut = levels.findIndex(l => l.lower <= price);
  const selected = levels.find(l => l.lower === active);

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

      {levels.length === 0 ? (
        <div className="flex items-center justify-center text-[13px] mute" style={{ height }}>{t('whale.none')}</div>
      ) : (
        // key 跟视角：切视角重挂，入场动画再走一遍
        <div key={view} className="reveal" style={{ minHeight: height }} onMouseLeave={() => setActive(null)}>
          {levels.map((l, i) => (
            <Fragment key={l.lower}>
              {i === cut && divider}
              <div
                className="grid items-center gap-x-2 cursor-default"
                style={{ gridTemplateColumns: cols, height: ROW_H, background: l.lower === active ? 'var(--color-card-2)' : undefined }}
                onMouseEnter={() => setActive(l.lower)}
                onClick={() => setActive(l.lower)}
              >
                <div className="flex justify-end h-3">{l.long > 0 && <i className="block h-full bg-gain" style={bar(l.long)} />}</div>
                <span className="num text-[12px] text-right">{px(l.lower)}</span>
                <span className="num text-[12px] text-right mute">{dist(l)}</span>
                <div className="flex h-3">{l.short > 0 && <i className="block h-full bg-loss" style={bar(l.short)} />}</div>
              </div>
            </Fragment>
          ))}
          {cut === -1 && divider}
        </div>
      )}

      <div className="num mt-2 min-h-[18px] text-[12px] mute">
        {selected
          ? t('whale.bucketTip', {
            from: px(selected.lower), to: px(selected.lower + selected.width),
            long: money(selected.long), short: money(selected.short),
          })
          : t('whale.readoutHint')}
      </div>
    </div>
  );
}
