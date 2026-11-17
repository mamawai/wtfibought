import { useState, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { getCoin } from '../lib/coinConfig';
import { cn, fmtNum } from '../lib/utils';

interface CryptoRow {
  symbol: string;
  marketValue: number;
}

interface FuturesRow {
  symbol: string;
  marketValue: number;
}

interface BStockRow {
  ticker: string;
  marketValue: number;
}

/** 现货和股票按墨色深浅循环取色 */
const INK = [
  'var(--color-foreground)',
  'color-mix(in srgb,var(--color-foreground) 62%,var(--color-background))',
  'color-mix(in srgb,var(--color-foreground) 40%,var(--color-background))',
  'color-mix(in srgb,var(--color-foreground) 24%,var(--color-background))',
];
/** 圆环半径（viewBox 200×200）、两段之间留的缝 */
const R = 86;
const C = 2 * Math.PI * R;
const GAP = 2.5;

interface Props {
  cryptoPositions?: CryptoRow[];
  bstockRows?: BStockRow[];
  futuresRows?: FuturesRow[];
  balance: number;
  gameBalance?: number;
}

/**
 * 持仓页「分布」面板：一样东西一段的圆环 + 右边一列明细（样式见 pages/Portfolio.css .dist）。
 * 指着明细里哪行，环上那段亮、别的退后，环心报它的数。
 */
export function PortfolioChart({ cryptoPositions = [], bstockRows = [], futuresRows = [], balance, gameBalance = 0 }: Props) {
  const { t } = useTranslation('portfolio');
  const [hot, setHot] = useState<number | null>(null);

  // 圆环画不了负数：负的（全仓浮亏）不进环，红字列在下面
  const losses = futuresRows.filter(f => f.marketValue < 0);
  const spot = [
    ...cryptoPositions.filter(c => c.marketValue > 0).map(c => ({ name: getCoin(c.symbol).name, value: c.marketValue })),
    ...bstockRows.filter(b => b.marketValue > 0).map(b => ({ name: b.ticker, value: b.marketValue })),
  ];
  const rows = [
    ...spot.map((r, i) => ({ ...r, color: INK[i % INK.length] })),
    ...futuresRows.filter(f => f.marketValue > 0)
      .map(f => ({ name: t('dist.futures', { name: getCoin(f.symbol).name }), value: f.marketValue, color: 'var(--color-primary)' })),
    { name: t('ov.balanceWallet'), value: balance, color: 'var(--color-line2)' },
    ...(gameBalance > 0 ? [{ name: t('ov.gameWallet'), value: gameBalance, color: 'var(--color-fill2)' }] : []),
  ];
  // 破产清零那会儿可能一样都没有，别除零
  const total = rows.reduce((s, r) => s + r.value, 0) || 1;

  let acc = 0;
  const segs = rows.map((r, i) => {
    const len = r.value / total * C;
    const seg = (
      <circle key={i} cx={100} cy={100} r={R} className={cn(hot === i && 'on')} style={{ stroke: r.color }}
              strokeDasharray={`${Math.max(0.5, len - GAP).toFixed(2)} ${C.toFixed(2)}`} strokeDashoffset={(-acc).toFixed(2)} />
    );
    acc += len;
    return seg;
  });
  const cur = hot == null ? null : rows[hot];

  return (
    <>
      <div className="dist">
        <div className={cn('donut', hot != null && 'dim')}>
          <svg viewBox="0 0 200 200" role="img" aria-label={t('alloc.title')}>{segs}</svg>
          <div className="mid">
            <span>{cur ? cur.name : t('dist.total')}</span>
            <b className="nf">{fmtNum(cur ? cur.value : total)}</b>
            <em>{cur ? `${(cur.value / total * 100).toFixed(1)}%` : ''}</em>
          </div>
        </div>
        <div className="dl" onMouseLeave={() => setHot(null)} onBlur={() => setHot(null)}>
          {rows.map((r, i) => (
            <button key={i} type="button" onMouseEnter={() => setHot(i)} onFocus={() => setHot(i)}>
              <i style={{ '--c': r.color } as CSSProperties} />
              <span>{r.name}</span>
              <b>{fmtNum(r.value)}</b>
              <em>{(r.value / total * 100).toFixed(1)}%</em>
            </button>
          ))}
        </div>
      </div>
      <p className="pv-note">{t('chart.gameWalletNote')}</p>
      {losses.length > 0 && (
        <p className="pv-note text-loss">
          {t('chart.futuresLoss')} {losses.map(f => `${getCoin(f.symbol).name} ${fmtNum(f.marketValue)}`).join(' · ')}
        </p>
      )}
    </>
  );
}
