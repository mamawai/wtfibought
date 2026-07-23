import { Globe } from 'lucide-react';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { TRADFI_LIST } from '../lib/coinConfig';

export function TradFiSelect() {
  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <div className="flex flex-col items-center gap-1.5">
        <div className="flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-sky-500/10">
            <Globe className="w-5 h-5 text-sky-500" />
          </div>
          <h1 className="text-xl font-bold">TradFi 合约</h1>
        </div>
        <p className="text-[11px] text-muted-foreground">美股 / ETF 永续合约 · 24/7</p>
      </div>
      <CoinMarketGrid list={TRADFI_LIST} />
    </div>
  );
}
