import { useEffect, useState, type ComponentType } from 'react';
import { useNavigate } from 'react-router-dom';
import { Landmark, Bitcoin, Gem, ChevronRight, type LucideProps } from 'lucide-react';
import { cn, fmtNum } from '../lib/utils';
import { COIN_MAP, COMMODITY_LIST } from '../lib/coinConfig';
import { CoinMarketCard } from './CoinMarketGrid';
import { sparkPoints } from '../lib/sparkline';
import { Skeleton } from './ui/skeleton';
import { bstockApi } from '../api';
import type { BStock } from '../types';

/** bStock 行情卡：与 CoinMarketCard 同款外观；涨跌幅直接用 list 的日涨跌，走势线拉 1h K线 */
function BStockMarketCard({ stock }: { stock: BStock }) {
  const navigate = useNavigate();
  const [closes, setCloses] = useState<number[]>([]);

  useEffect(() => {
    let cancelled = false;
    bstockApi.klines(stock.symbol, '1h', 25)
      .then(rows => { if (!cancelled && rows?.length) setCloses(rows.map(r => Number(r[4]))); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [stock.symbol]);

  const chg = stock.changePct ?? null;
  const up = (chg ?? 0) >= 0;

  return (
    <button
      onClick={() => navigate(`/bstock/${stock.symbol}`)}
      className="flex flex-col rounded-2xl p-4 text-left transition-all cursor-pointer neu-btn-sm bg-blue-500/10 hover:bg-blue-500/20"
    >
      <div className="flex items-center gap-2.5 w-full">
        <div className="w-7 h-7 rounded-lg bg-blue-500/15 flex items-center justify-center shrink-0 text-[9px] font-bold text-blue-500 tracking-tight">
          {stock.ticker?.slice(0, 4)}
        </div>
        <div className="min-w-0">
          <div className="font-bold text-sm leading-tight truncate">{stock.name}</div>
          <div className="text-[10px] text-muted-foreground whitespace-nowrap">{stock.ticker} · 美股</div>
        </div>
        <span
          className={cn(
            'ml-auto shrink-0 text-[11px] font-bold tabular-nums px-1.5 py-0.5 rounded-full',
            chg == null ? 'text-muted-foreground' : up ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
          )}
        >
          {chg == null ? '—' : `${up ? '+' : ''}${chg.toFixed(2)}%`}
        </span>
      </div>

      <div className="mt-2 text-lg font-extrabold tabular-nums tracking-tight">
        {stock.price == null ? '—' : `$${fmtNum(stock.price)}`}
      </div>

      <div className="mt-2 h-7 w-full">
        {closes.length > 1 && (
          <svg viewBox="0 0 100 28" preserveAspectRatio="none" className="w-full h-full">
            <polyline
              points={sparkPoints(closes)}
              fill="none" stroke="#3b82f6" strokeWidth="1.5"
              strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"
            />
          </svg>
        )}
      </div>
    </button>
  );
}

/** 分类头：整行可点，跳转对应市场页 */
function CategoryHeader({ icon: Icon, title, subtitle, iconBg, iconColor, to }: {
  icon: ComponentType<LucideProps>; title: string; subtitle: string;
  iconBg: string; iconColor: string; to: string;
}) {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate(to)} className="flex items-center gap-2 w-full group cursor-pointer">
      <div className={cn('w-7 h-7 rounded-lg flex items-center justify-center', iconBg)}>
        <Icon className={cn('w-3.5 h-3.5', iconColor)} />
      </div>
      <span className="text-sm font-bold">{title}</span>
      <span className="text-[11px] text-muted-foreground">{subtitle}</span>
      <span className="ml-auto inline-flex items-center gap-0.5 text-[11px] font-bold text-muted-foreground group-hover:text-primary transition-colors">
        全部 <ChevronRight className="w-3.5 h-3.5" />
      </span>
    </button>
  );
}

/**
 * 首页市场行情：bStock / Crypto / 大宗商品三分类，每类 2 个代表标的。
 * 点分类头去市场页，点卡片直达交易页。桌面三列并排，移动端纵向堆叠（块内两卡并排）。
 */
export function HomeMarketSection() {
  const [topStocks, setTopStocks] = useState<BStock[]>([]);

  // 市值前 2 作代表；8s 静默刷新与 /bstock 列表页同频
  useEffect(() => {
    const load = () => bstockApi.list()
      .then(list => setTopStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0)).slice(0, 2)))
      .catch(() => {});
    load();
    const t = setInterval(load, 8000);
    return () => clearInterval(t);
  }, []);

  const cryptoReps = [COIN_MAP.BTCUSDT, COIN_MAP.ETHUSDT];

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
      <div className="space-y-3">
        <CategoryHeader icon={Landmark} title="股票" subtitle="代币化美股" iconBg="bg-blue-500/10" iconColor="text-blue-500" to="/bstock" />
        <div className="grid grid-cols-2 md:grid-cols-1 gap-3">
          {topStocks.length
            ? topStocks.map(s => <BStockMarketCard key={s.symbol} stock={s} />)
            : Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="rounded-2xl h-[118px]" />)}
        </div>
      </div>

      <div className="space-y-3">
        <CategoryHeader icon={Bitcoin} title="Crypto" subtitle="加密货币" iconBg="bg-amber-500/10" iconColor="text-amber-500" to="/coin" />
        <div className="grid grid-cols-2 md:grid-cols-1 gap-3">
          {cryptoReps.map(c => <CoinMarketCard key={c.symbol} cfg={c} />)}
        </div>
      </div>

      <div className="space-y-3">
        <CategoryHeader icon={Gem} title="大宗商品" subtitle="TradFi 永续" iconBg="bg-yellow-500/10" iconColor="text-yellow-500" to="/commodity" />
        <div className="grid grid-cols-2 md:grid-cols-1 gap-3">
          {COMMODITY_LIST.map(c => <CoinMarketCard key={c.symbol} cfg={c} />)}
        </div>
      </div>
    </div>
  );
}
