import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { cn } from '../lib/utils';
import { formatCoinPrice, type CoinCfg } from '../lib/coinConfig';
import { cryptoApi, futuresApi, bstockApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { Sparkline } from './fx/Sparkline';
import type { BStock } from '../types';

/**
 * 终端式行情表行：图标名称 | 价格(右对齐) | 走势线 | 涨跌幅。
 * 移动端收起走势线列。首页行情表与选币/选股页共用。
 */
export function MarketRow({ icon, name, sub, price, pct, spark, sparkColor, onClick }: {
  icon: ReactNode;
  name: string;
  sub?: string;
  price: string | null;
  pct: number | null;
  spark?: number[];
  sparkColor?: string;
  onClick: () => void;
}) {
  const up = (pct ?? 0) >= 0;
  return (
    <button
      onClick={onClick}
      className={cn(
        'grid grid-cols-[minmax(0,1fr)_auto_72px] sm:grid-cols-[minmax(0,1.1fr)_1fr_88px_76px] items-center gap-2 sm:gap-3',
        'w-full px-3 py-2.5 text-left border-b border-border/60 last:border-0',
        'hover:bg-surface-hover transition-colors cursor-pointer',
      )}
    >
      <span className="flex items-center gap-2.5 min-w-0">
        {icon}
        <span className="min-w-0">
          <span className="block text-sm font-semibold leading-tight truncate">{name}</span>
          {sub && <span className="block text-[10px] text-muted-foreground truncate">{sub}</span>}
        </span>
      </span>

      <span className="num text-sm font-medium text-right">{price ?? '—'}</span>

      <span className="hidden sm:block h-[22px]">
        {spark && spark.length > 1 && <Sparkline data={spark} stroke={sparkColor} className="w-full h-full" />}
      </span>

      <span
        className={cn(
          'num text-[11px] font-semibold text-right tabular-nums',
          pct == null ? 'text-muted-foreground' : up ? 'text-gain' : 'text-loss',
        )}
      >
        {pct == null ? '—' : `${up ? '+' : ''}${pct.toFixed(2)}%`}
      </span>
    </button>
  );
}

/** 币种/商品行：实时流价 + 1h×25 根K线（首根收盘=24h涨跌基准，整条作走势线），与原行情卡同口径 */
export function CoinMarketRow({ cfg }: { cfg: CoinCfg }) {
  const navigate = useNavigate();
  const tick = useCryptoStream(cfg.symbol, cfg.futuresOnly ? 'futures' : 'spot');
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    const loadKlines = cfg.futuresOnly ? futuresApi.klines : cryptoApi.klines;
    loadKlines(cfg.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [cfg.symbol, cfg.futuresOnly]);

  const livePrice = cfg.futuresOnly ? (tick?.fp ?? tick?.price) : tick?.price;
  const spark = livePrice != null && closes.length ? [...closes.slice(0, -1), livePrice] : closes;
  const price = livePrice ?? (closes.length ? closes[closes.length - 1] : null);
  const base = closes.length ? closes[0] : null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;

  return (
    <MarketRow
      icon={<cfg.icon className={cn('w-6 h-6 shrink-0', cfg.colorClass)} />}
      name={cfg.name}
      sub={cfg.pair}
      price={price == null ? null : `$${formatCoinPrice(cfg.symbol, price)}`}
      pct={pct}
      spark={spark}
      sparkColor={cfg.chartColor}
      onClick={() => navigate(`/coin/${cfg.symbol}`)}
    />
  );
}

/** bStock 行：list 的日涨跌 + 1h K线走势 */
export function BStockMarketRow({ stock }: { stock: BStock }) {
  const navigate = useNavigate();
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    bstockApi.klines(stock.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [stock.symbol]);

  return (
    <MarketRow
      icon={(
        <span className="w-6 h-6 rounded-md border border-border bg-card-2 flex items-center justify-center shrink-0 text-[8px] font-bold text-muted-foreground tracking-tight">
          {stock.ticker?.slice(0, 4)}
        </span>
      )}
      name={stock.name}
      sub={`${stock.ticker} · 美股`}
      price={stock.price == null ? null : `$${Number(stock.price).toLocaleString('en-US', { maximumFractionDigits: 2 })}`}
      pct={stock.changePct ?? null}
      spark={closes}
      onClick={() => navigate(`/bstock/${stock.symbol}`)}
    />
  );
}
