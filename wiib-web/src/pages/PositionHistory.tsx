import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, Info, RefreshCw } from 'lucide-react';
import { futuresApi } from '../api';
import { PositionHistoryList } from '../components/PositionHistoryList';
import { spinIcon } from '../lib/utils';
import type { PageResult, PositionHistoryItem } from '../types';
import './records.css';

const PAGE_SIZE = 20;

const EMPTY: PageResult<PositionHistoryItem> = {
  records: [], total: 0, size: PAGE_SIZE, current: 1, pages: 0,
};

/**
 * 我的合约仓位历史。一行是一笔完整的仓位（开仓到全部平掉），点开是这笔仓位的每一次成交。
 * <p>
 * 跟「成交记录」页的分工：那边一行一笔委托，看的是"什么时候下了什么单"；
 * 这边把同一仓位的开仓、加仓、分批平仓合成一条，看的是"这笔仓位最后赚没赚、回报率多少"。
 */
export function PositionHistory() {
  const { t } = useTranslation(['portfolio', 'common']);
  const [data, setData] = useState<PageResult<PositionHistoryItem>>(EMPTY);
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);

  // loading 由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 Ledger/ForceOrders）
  const requestKey = `${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    futuresApi.positionHistory(page, PAGE_SIZE)
      .then(res => { if (!cancelled) setData(res); })
      .catch(() => { if (!cancelled) setData(EMPTY); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, page]);

  return (
    <div className="page-shell rec">
      <div className="pg-h">
        <div>
          <Link className="back" to="/portfolio"><ChevronLeft className="ic" />{t('history.back')}</Link>
          <h1>{t('history.title')}{!loading && <small>{t('history.total', { count: data.total })}</small>}</h1>
          <p className="lead">{t('history.desc')}</p>
        </div>
        <div className="r">
          <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')}
                  onClick={e => { spinIcon(e); setRefreshNonce(n => n + 1); }}>
            <RefreshCw className="ic" />
          </button>
        </div>
      </div>

      {/* 「我平了一半怎么这儿没有」是必然会有的疑问，与其等人来问，不如写在页面上 */}
      <div className="note mt-[22px] mb-[18px]">
        <Info className="ic" />
        <div>{t('history.note')}</div>
      </div>

      <PositionHistoryList
        records={data.records}
        page={data.current}
        pages={data.pages}
        loading={loading}
        onPage={setPage}
      />
    </div>
  );
}
