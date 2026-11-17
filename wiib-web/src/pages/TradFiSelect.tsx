import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { TRADFI_LIST } from '../lib/coinConfig';

export function TradFiSelect() {
  const { t } = useTranslation('market');
  return <CoinMarketGrid list={TRADFI_LIST} sub={t('select.tradfiSub')} />;
}
