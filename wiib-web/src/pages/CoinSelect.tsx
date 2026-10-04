import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { MarketTabs } from '../components/MarketTabs';

export function CoinSelect() {
  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <MarketTabs />
      <CoinMarketGrid />
    </div>
  );
}
