import { DollarSign } from 'lucide-react';
import { CoinMarketGrid } from '../components/CoinMarketGrid';

export function CoinSelect() {
  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <div className="flex items-center justify-center gap-2.5">
        <div className="p-1.5 rounded-lg bg-amber-500/10">
          <DollarSign className="w-5 h-5 text-amber-500" />
        </div>
        <h1 className="text-xl font-bold">币种交易</h1>
      </div>
      <CoinMarketGrid />
    </div>
  );
}
