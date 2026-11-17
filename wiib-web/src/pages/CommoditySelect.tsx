import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { COMMODITY_LIST } from '../lib/coinConfig';

export function CommoditySelect() {
  const { t } = useTranslation('market');
  return <CoinMarketGrid list={COMMODITY_LIST} sub={t('select.commoditySub')} />;
}
