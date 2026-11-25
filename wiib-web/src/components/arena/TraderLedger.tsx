import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { traderApi } from '../../api';
import { LedgerRows } from '../LedgerRows';
import { Skeleton } from '../ui/skeleton';
import { useToast } from '../ui/use-toast';
import { cn } from '../../lib/utils';
import type { LedgerEntry } from '../../types';

/** 每页条数；后端封顶 100，回来不满一页就是到底了 */
const PAGE = 50;

/**
 * 资金账单页签：当前局子账户的资金流水，谁都能看。
 * 挂上就拉第一页；换 trader、开了新局、点刷新，由外面换 key 整个重挂
 */
export function TraderLedger({ traderId }: { traderId: number }) {
  const { t } = useTranslation(['ai', 'portfolio']);
  const { toast } = useToast();
  // null=第一页还没回来
  const [entries, setEntries] = useState<LedgerEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  // 只看哪一类；null=全部
  const [type, setType] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    traderApi.ledger(traderId, PAGE)
      .then(rows => {
        if (cancelled) return;
        setEntries(rows);
        setHasMore(rows.length >= PAGE);
      })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [traderId]);

  // 出现过哪些类型就有哪些筛选钮；只在已经拉下来的这些里挑，计数也是
  const types = useMemo(() => {
    const seen = new Map<string, { label: string; n: number }>();
    for (const e of entries ?? []) {
      const hit = seen.get(e.bizType);
      if (hit) hit.n++;
      else seen.set(e.bizType, { label: e.bizTypeLabel, n: 1 });
    }
    return [...seen];
  }, [entries]);

  if (failed) return <div className="empty"><b>{t('detail.ledgerFailed')}</b></div>;
  if (!entries) {
    return (
      <div className="flex flex-col gap-2 mt-4">
        {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-12" />)}
      </div>
    );
  }
  if (entries.length === 0) return <div className="empty"><b>{t('detail.noLedger')}</b></div>;

  const loadMore = () => {
    // 上一页还没回来就不再拉：转圈的钮键盘还按得动
    if (loadingMore) return;
    setLoadingMore(true);
    traderApi.ledger(traderId, PAGE, entries[entries.length - 1].id)
      .then(rows => {
        setEntries(prev => [...(prev ?? []), ...rows]);
        setHasMore(rows.length >= PAGE);
      })
      .catch(() => toast(t('portfolio:toast.loadMoreFailed'), 'error'))
      .finally(() => setLoadingMore(false));
  };

  return (
    <>
      <p className="tab-note">
        {t('detail.ledgerNote')}
        {/* 拉下来的流水里有老记法的那种，才多说这一句 */}
        {types.some(([name]) => name === 'CROSS_SETTLE') && t('detail.ledgerNoteLegacy')}
      </p>
      {types.length > 1 && (
        <div className="chips">
          <button type="button" className="chip" aria-pressed={type == null} onClick={() => setType(null)}>
            {t('wb.filter.all')}<em>{entries.length}</em>
          </button>
          {types.map(([name, { label, n }]) => (
            <button key={name} type="button" className="chip" aria-pressed={type === name} onClick={() => setType(name)}>
              {t('portfolio:biz.' + name, { defaultValue: label })}<em>{n}</em>
            </button>
          ))}
        </div>
      )}
      <LedgerRows entries={type ? entries.filter(e => e.bizType === type) : entries} />
      {hasMore && (
        <button type="button" className={cn('btn wide more', loadingMore && 'busy')} onClick={loadMore}>
          <span>{t('detail.loadMore')}</span>
        </button>
      )}
    </>
  );
}
