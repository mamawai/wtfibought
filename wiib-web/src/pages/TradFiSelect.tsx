import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { TRADFI_LIST } from '../lib/coinConfig';

export function TradFiSelect() {
  const { t } = useTranslation('market');
  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <h1 className="text-[22px] font-extrabold tracking-[-.01em] mb-2.5 flex items-baseline gap-2.5 flex-wrap">
        {t('select.tradfiTitle')}
        <small className="text-[13px] font-medium tracking-normal text-muted-foreground">{t('select.tradfiSub')}</small>
      </h1>
      <CoinMarketGrid list={TRADFI_LIST} />
    </div>
  );
}
