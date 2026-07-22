import { useEffect, useState, type ComponentType } from 'react';
import { useNavigate } from 'react-router-dom';
import { Landmark, Bitcoin, Gem, ChevronRight, type LucideProps } from 'lucide-react';
import { cn } from '../lib/utils';
import { COIN_MAP, COMMODITY_LIST } from '../lib/coinConfig';
import { CoinMarketRow, BStockMarketRow } from './MarketRow';
import { Skeleton } from './ui/skeleton';
import { bstockApi } from '../api';
import type { BStock } from '../types';

/** 分类头：整行可点，跳转对应市场页 */
function CategoryHeader({ icon: Icon, title, subtitle, iconColor, to }: {
  icon: ComponentType<LucideProps>; title: string; subtitle: string;
  iconColor: string; to: string;
}) {
  const navigate = useNavigate();
  return (
    <button
      onClick={() => navigate(to)}
      className="flex items-center gap-2 w-full px-3 py-2.5 border-b border-border group cursor-pointer hover:bg-surface-hover transition-colors"
    >
      <Icon className={cn('w-3.5 h-3.5', iconColor)} />
      <span className="text-xs font-bold">{title}</span>
      <span className="text-[10px] text-muted-foreground">{subtitle}</span>
      <span className="ml-auto inline-flex items-center gap-0.5 text-[10px] font-semibold text-muted-foreground group-hover:text-primary transition-colors">
        全部 <ChevronRight className="w-3 h-3" />
      </span>
    </button>
  );
}

/**
 * 首页市场行情：bStock / Crypto / 大宗商品三分类终端表，每类 2 个代表标的。
 * 点分类头去市场页，点行直达交易页。桌面三列并排，移动端纵向堆叠。
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
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div className="pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Landmark} title="股票" subtitle="代币化美股" iconColor="text-blue-500" to="/bstock" />
        {topStocks.length
          ? topStocks.map(s => <BStockMarketRow key={s.symbol} stock={s} />)
          : Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-[52px] m-2" />)}
      </div>

      <div className="pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Bitcoin} title="Crypto" subtitle="加密货币" iconColor="text-amber-500" to="/coin" />
        {cryptoReps.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>

      <div className="pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Gem} title="大宗商品" subtitle="TradFi 永续" iconColor="text-yellow-500" to="/commodity" />
        {COMMODITY_LIST.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>
    </div>
  );
}
