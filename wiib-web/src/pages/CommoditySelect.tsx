import { Gem } from 'lucide-react';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { COMMODITY_LIST } from '../lib/coinConfig';

export function CommoditySelect() {
  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <div className="flex flex-col items-center gap-1.5">
        <div className="flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-amber-500/10">
            <Gem className="w-5 h-5 text-amber-500" />
          </div>
          <h1 className="text-xl font-bold">大宗商品</h1>
        </div>
        <p className="text-[11px] text-muted-foreground">TradFi 永续合约 · 黄金 / 原油 · 24/7</p>
      </div>
      <CoinMarketGrid list={COMMODITY_LIST} />
    </div>
  );
}
