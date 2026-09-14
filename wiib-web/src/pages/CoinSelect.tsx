import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';

export function CoinSelect() {
  const { t } = useTranslation('market');
  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <h1 className="text-[22px] font-extrabold tracking-[-.01em] mb-2.5">{t('select.coinTitle')}</h1>
      <CoinMarketGrid />
    </div>
  );
}
