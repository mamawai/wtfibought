import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { MarketTabs } from '../components/MarketTabs';
import { COMMODITY_LIST } from '../lib/coinConfig';

export function CommoditySelect() {
  const { t } = useTranslation('market');
  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <MarketTabs sub={t('select.commoditySub')} />
      <CoinMarketGrid list={COMMODITY_LIST} />
    </div>
  );
}
