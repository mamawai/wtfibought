import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Inbox } from 'lucide-react';
import { bstockApi } from '../api';
import { StockMarketRow } from '../components/MarketRow';
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
    <>
      <p className="sub">{stocks != null && stocks.length > 0 && t('bstockList.subtitle', { count: stocks.length })}</p>
      <div className="list">
        {stocks == null
          ? Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="skr">
              <i className="sk rounded-full" style={{ height: 30 }} />
              <i className="sk t" style={{ width: '38%' }} />
              <i className="sk t" />
              <i className="sk" style={{ height: 26 }} />
            </div>
          ))
          : stocks.length
            ? stocks.map(s => <StockMarketRow key={s.symbol} stock={s} />)
            : <div className="empty"><span className="ib0"><Inbox className="ic" /></span><b>{t('common:noData')}</b></div>}
      </div>
    </>
  );
}
