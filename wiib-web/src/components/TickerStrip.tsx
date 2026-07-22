import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { cn } from '../lib/utils';
import { COIN_MAP } from '../lib/coinConfig';
import { cryptoApi, futuresApi, bstockApi } from '../api';
import { useCryptoStream } from '../hooks/useCryptoStream';
import type { BStock } from '../types';

/** NumberFlow 要的是 Intl 参数而非格式化后的字符串，按价位档给小数位 */
function fractionDigits(price: number): number {
  if (price >= 100) return 2;
  if (price >= 1) return 3;
  return 5;
}

interface Quote { key: string; name: string; price: number | null; pct: number | null; to: string; live: boolean }

/** 单币报价：实时流价 + 1h×25 根K线首根收盘作 24h 基准（与行情卡同口径） */
function useCoinQuote(symbol: string): Quote {
  const cfg = COIN_MAP[symbol];
  const tick = useCryptoStream(symbol, cfg.futuresOnly ? 'futures' : 'spot');
  const [base, setBase] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    const load = cfg.futuresOnly ? futuresApi.klines : cryptoApi.klines;
    load(symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setBase(Number(rows[0][4])); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [symbol, cfg.futuresOnly]);

  const price = (cfg.futuresOnly ? (tick?.fp ?? tick?.price) : tick?.price) ?? null;
  const pct = price != null && base ? ((price - base) / base) * 100 : null;
  return { key: symbol, name: cfg.name, price, pct, to: `/coin/${symbol}`, live: true };
}

function TickerCell({ q, onGo }: { q: Quote; onGo: (to: string) => void }) {
  const up = (q.pct ?? 0) >= 0;
  return (
    <button
      type="button"
      tabIndex={-1}
      onClick={() => onGo(q.to)}
      className="flex items-center gap-1.5 px-3 h-full text-[11px] num whitespace-nowrap hover:bg-surface-hover transition-colors cursor-pointer"
    >
      <span className="font-semibold text-foreground font-sans">{q.name}</span>
      {q.price == null
        ? <span className="text-muted-foreground">—</span>
        : q.live
          ? <NumberFlow
              value={q.price}
              format={{ maximumFractionDigits: fractionDigits(q.price), minimumFractionDigits: 2 }}
              className="text-muted-foreground"
            />
          : <span className="text-muted-foreground">{q.price.toLocaleString('en-US', { maximumFractionDigits: 2, minimumFractionDigits: 2 })}</span>}
      {q.pct != null && (
        <span className={cn('font-semibold', up ? 'text-gain' : 'text-loss')}>
          {up ? '+' : ''}{q.pct.toFixed(2)}%
        </span>
      )}
    </button>
  );
}

/**
 * 行情副条：顶栏下 26px 报价条，横向缓慢无缝滚动（悬停暂停），点击直达交易页。
 * 盘面 = 主流三币 + 黄金（实时流）+ 美股市值 Top 4（8s REST 刷新）。仅桌面显示。
 */
export function TickerStrip() {
  const navigate = useNavigate();
  // hooks 不能循环调用，固定盘面写死四路
  const btc = useCoinQuote('BTCUSDT');
  const eth = useCoinQuote('ETHUSDT');
  const sol = useCoinQuote('SOLUSDT');
  const xau = useCoinQuote('XAUUSDT');

  const [stocks, setStocks] = useState<BStock[]>([]);
  useEffect(() => {
    const load = () => bstockApi.list()
      .then(list => setStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0)).slice(0, 4)))
      .catch(() => {});
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, []);

  const quotes: Quote[] = [
    btc, eth, sol, xau,
    ...stocks.map(s => ({
      key: s.symbol,
      name: s.ticker ?? s.name,
      price: s.price ?? null,
      pct: s.changePct ?? null,
      to: `/bstock/${s.symbol}`,
      live: false,
    })),
  ];

  // 两份相同内容首尾相接：数据/订阅只有一份，DOM 渲染两遍
  const half = (hidden: boolean): ReactNode => (
    <div className="flex items-stretch h-full shrink-0" aria-hidden={hidden}>
      {quotes.map(q => <TickerCell key={`${hidden ? 'b' : 'a'}-${q.key}`} q={q} onGo={navigate} />)}
    </div>
  );

  return (
    <div className="hidden md:block h-[26px] border-b border-border bg-card-2 overflow-hidden group">
      <div className="flex h-full w-max animate-[wiib-ticker_50s_linear_infinite] group-hover:[animation-play-state:paused] motion-reduce:animate-none">
        {half(false)}
        {half(true)}
      </div>
    </div>
  );
}
