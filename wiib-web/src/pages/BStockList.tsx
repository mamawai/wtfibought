import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { bstockApi } from '../api';
import { StockMarketRow } from '../components/MarketRow';
import { MarketTabs } from '../components/MarketTabs';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import type { BStock } from '../types';

export function BStockList() {
  const { t } = useTranslation(['market', 'common']);
  const { toast } = useToast();
  // null = 还在拉
  const [stocks, setStocks] = useState<BStock[] | null>(null);

  // 只拉一次：要的是哪几只 + 名称这些静态信息，价格和涨跌每行自己走现货流实时刷。按市值从大到小排
  useEffect(() => {
    bstockApi.list()
      .then(list => setStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0))))
      .catch(() => {
        setStocks([]);
        toast(t('bstockList.loadFailed'), 'error', { description: t('bstockList.loadFailedHint') });
      });
  }, [toast, t]);

  return (
    <div className="max-w-[820px] mx-auto px-5 pt-11 pb-14">
      <MarketTabs sub={stocks != null && stocks.length > 0 ? t('bstockList.subtitle', { count: stocks.length }) : null} />
      <div className="mkt-list @container border-t border-border">
        {stocks == null
          ? Array.from({ length: 6 }).map((_, i) => <Skeleton key={i} className="h-[60px] @md:h-[66px] mt-px" />)
          : stocks.length
            ? stocks.map(s => <StockMarketRow key={s.symbol} stock={s} />)
            : <p className="py-12 text-center text-[13px] text-muted-foreground">{t('common:noData')}</p>}
      </div>
    </div>
  );
}
