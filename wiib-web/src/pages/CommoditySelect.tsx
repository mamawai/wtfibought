import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { COMMODITY_LIST } from '../lib/coinConfig';

export function CommoditySelect() {
  const { t } = useTranslation('market');
  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <h1 className="text-[22px] font-extrabold tracking-[-.01em] mb-2.5 flex items-baseline gap-2.5 flex-wrap">
        {t('select.commodityTitle')}
        <small className="text-[13px] font-medium tracking-normal text-muted-foreground">{t('select.commoditySub')}</small>
      </h1>
      <CoinMarketGrid list={COMMODITY_LIST} />
    </div>
  );
}
