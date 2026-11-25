import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ledgerApi } from '../api';
import { Select } from '../components/ui/select';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { EmptyState } from '../components/EmptyState';
import { LedgerRows } from '../components/LedgerRows';
import { cn, spinIcon } from '../lib/utils';
import { ChevronLeft, CloudOff, RefreshCw, Wallet } from 'lucide-react';
import type { LedgerEntry, LedgerBizTypeOption } from '../types';
import './records.css';
import './Ledger.css';

/** 后端默认 30、封顶 100。取默认值即可，一屏够看还省流量 */
const PAGE_SIZE = 30;

/**
 * 账单业务类型的下拉分组名。后端下发的 group 是中文串，这里按枚举名前缀反推一个 key 去查词表——
 * 跟 LedgerBizType.getGroup() 是同一套前缀规则，那边加了新分组这边也要跟着补一行。
 */
function bizGroupKey(bizName: string): string {
  if (/^(FUTURES_|FUNDING_|CROSS_)/.test(bizName)) return 'futures';
  if (/^(SPOT_|BSTOCK_)/.test(bizName)) return 'spot';
  if (/^(MINES_|POKER_|PREDICTION_|BLACKJACK_)/.test(bizName)) return 'games';
  if (/^WALLET_TRANSFER_/.test(bizName)) return 'transfer';
  if (/^(MARGIN_|CASH_)/.test(bizName)) return 'margin';
  return 'other';
}

export function Ledger() {
  const { t } = useTranslation(['portfolio', 'common']);
  const { toast } = useToast();

  const [bizType, setBizType] = useState('');
  const [bizOptions, setBizOptions] = useState<LedgerBizTypeOption[]>([]);
  const [entries, setEntries] = useState<LedgerEntry[]>([]);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  // 到底的判据只能是"返回空数组"：服务端把 limit 封顶到 100，
  // 按"返回条数 < limit"判的话传个大 limit 第一页就会被误判成到底
  const [done, setDone] = useState(false);
  // 首屏失败与"确实没流水"要分开显示，否则请求挂了会被当成"你没有任何流水"
  const [failed, setFailed] = useState(false);

  // loading 由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 ForceOrders）
  const requestKey = `${bizType}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;
  /** 当前列表是哪个筛选/哪次刷新的：「加载更多」回来时对一下，换过了就丢 */
  const listKeyRef = useRef(requestKey);

  useEffect(() => {
    ledgerApi.bizTypes().then(setBizOptions).catch(() => setBizOptions([]));
  }, []);

  // 换筛选/点刷新都从头拉第一页，游标作废
  useEffect(() => {
    let cancelled = false;
    listKeyRef.current = requestKey;
    ledgerApi.list({ bizType: bizType || undefined, limit: PAGE_SIZE })
      .then(rows => {
        if (cancelled) return;
        setEntries(rows);
        setDone(rows.length === 0);
        setFailed(false);
      })
      .catch(() => {
        if (cancelled) return;
        setEntries([]);
        setDone(true);
        setFailed(true);
      })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, bizType]);

  const loadMore = () => {
    const last = entries[entries.length - 1];
    if (!last || loadingMore || done) return;
    setLoadingMore(true);
    const key = requestKey;
    ledgerApi.list({ bizType: bizType || undefined, beforeId: last.id, limit: PAGE_SIZE })
      .then(rows => {
        if (listKeyRef.current !== key) return;
        setEntries(prev => [...prev, ...rows]);
        if (rows.length === 0) setDone(true);
      })
      .catch(() => { if (listKeyRef.current === key) toast(t('toast.loadMoreFailed'), 'error'); })
      .finally(() => setLoadingMore(false));
  };

  // 下拉分组：后端已按枚举声明序返回，这里只按 group 归并，不重排
  const optionGroups = useMemo(() => {
    const groups: { name: string; options: LedgerBizTypeOption[] }[] = [];
    for (const o of bizOptions) {
      const hit = groups.find(g => g.name === o.group);
      if (hit) hit.options.push(o);
      else groups.push({ name: o.group, options: [o] });
    }
    return groups;
  }, [bizOptions]);
  const selectOptions = [
    { value: '', label: t('ledger.allTypes') },
    ...optionGroups.flatMap(g => g.options.map(o => ({
      value: o.name,
      label: t('biz.' + o.name, { defaultValue: o.label }),
      group: t('bizGroup.' + bizGroupKey(g.options[0].name)),
    }))),
  ];

  return (
    <div className="page-shell rec p-lg">
      <div className="pg-h">
        <div>
          <Link className="back" to="/portfolio"><ChevronLeft className="ic" />{t('history.back')}</Link>
          <h1>{t('ledger.title')}</h1>
          <p className="lead">{t('ledger.desc')}</p>
        </div>
      </div>

      <div className="fbar">
        <Select value={bizType} onChange={setBizType} options={selectOptions} label={t('ledger.filterLabel')} />
        <span className="sp" />
        <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')}
                onClick={e => { spinIcon(e); setRefreshNonce(n => n + 1); }}>
          <RefreshCw className="ic" />
        </button>
      </div>

      {loading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-12" />)}
        </div>
      ) : failed ? (
        <EmptyState icon={<CloudOff />} text={t('ledger.loadFailed')} />
      ) : entries.length === 0 ? (
        <EmptyState icon={<Wallet />} text={bizType ? t('ledger.emptyFiltered') : t('ledger.empty')} />
      ) : (
        <>
          <LedgerRows entries={entries} />
          {done
            ? <div className="end">{t('ledger.noMore')}</div>
            : <button type="button" className={cn('btn wide mt-4', loadingMore && 'busy')} onClick={loadMore}>{t('ledger.loadMore')}</button>}
        </>
      )}
    </div>
  );
}
