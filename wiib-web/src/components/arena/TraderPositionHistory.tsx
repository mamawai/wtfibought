import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { traderApi } from '../../api';
import { PositionHistoryList } from '../PositionHistoryList';
import type { PageResult, PositionHistoryItem } from '../../types';

const PAGE_SIZE = 20;

const EMPTY: PageResult<PositionHistoryItem> = {
  records: [], total: 0, size: PAGE_SIZE, current: 1, pages: 0,
};

/**
 * 合约持仓历史页签：当前局子账户已平的仓位，一行一笔完整仓位，点开是这笔的每一次成交；谁都能看。
 * 换 trader、开了新局、点刷新，由外面换 key 整个重挂
 */
export function TraderPositionHistory({ traderId }: { traderId: number }) {
  const { t } = useTranslation('ai');
  const [data, setData] = useState<PageResult<PositionHistoryItem>>(EMPTY);
  const [page, setPage] = useState(1);
  // 回来的是第几页；追上 page 之前算加载中
  const [loadedPage, setLoadedPage] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const loading = loadedPage !== page;

  useEffect(() => {
    let cancelled = false;
    traderApi.positionHistory(traderId, page, PAGE_SIZE)
      .then(res => {
        if (cancelled) return;
        setData(res);
        setFailed(false);
      })
      .catch(() => { if (!cancelled) setFailed(true); })
      .finally(() => { if (!cancelled) setLoadedPage(page); });
    return () => { cancelled = true; };
  }, [traderId, page]);

  if (failed && !loading) return <div className="empty"><b>{t('detail.historyFailed')}</b></div>;
  return (
    <div className="mt-4">
      <PositionHistoryList records={data.records} page={data.current} pages={data.pages} loading={loading}
                           onPage={setPage} emptyText={t('detail.noTrades')} />
    </div>
  );
}
