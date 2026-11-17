import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import i18n from '../i18n';
import { ledgerApi } from '../api';
import { Select } from '../components/ui/select';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { EmptyState } from '../components/EmptyState';
import { cn, fmtNum, fmtTime, parseServerTime, spinIcon } from '../lib/utils';
import { ChevronLeft, CloudOff, RefreshCw, Wallet } from 'lucide-react';
import type { LedgerEntry, LedgerBizTypeOption, LedgerWallet } from '../types';
import './records.css';
import './Ledger.css';

/** 后端默认 30、封顶 100。取默认值即可，一屏够看还省流量 */
const PAGE_SIZE = 30;

/**
 * 钱包名的词表 key。这份映射刻意留在前端——后端只下发枚举名，
 * 不像 bizType 那样平铺 label（见 LedgerControllerTest：wallet 没有 label 也不需要一份）。
 */
const WALLET_LABEL: Record<LedgerWallet, string> = {
  BALANCE: 'wallet.balance',
  FROZEN: 'wallet.frozen',
  GAME: 'wallet.game',
  LOAN_PRINCIPAL: 'wallet.loanPrincipal',
  LOAN_INTEREST: 'wallet.loanInterest',
  POSITION_MARGIN: 'wallet.positionMargin',
};

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

/**
 * 借款本金/应计利息这两个钱包，数字变大 = 欠得更多。
 * 一律按"正数标绿"会把"又借了 5000"画成好事，所以这两个钱包反过来：减少才标绿。
 */
const DEBT_WALLETS: LedgerWallet[] = ['LOAN_PRINCIPAL', 'LOAN_INTEREST'];

/** 变动额颜色：进账标绿，花出去的保持正文色不标红 */
function deltaTone(entry: LedgerEntry): string | undefined {
  if (entry.delta === 0) return 'mute';
  const good = DEBT_WALLETS.includes(entry.wallet) ? entry.delta < 0 : entry.delta > 0;
  return good ? 'up' : undefined;
}

/** 分组日期键。必须跟 fmtTime 同一时区（新加坡），否则跨零点的行会被分进错误的日期组 */
function dayKey(ts: string): string {
  return parseServerTime(ts).toLocaleDateString('zh-CN', {
    timeZone: 'Asia/Singapore', year: 'numeric', month: '2-digit', day: '2-digit',
  });
}

function dayLabel(key: string): string {
  const today = dayKey(new Date().toISOString());
  const yesterday = dayKey(new Date(Date.now() - 86400_000).toISOString());
  // 词表必须在函数体里现查：存成模块级常量的话切语言后不会变
  if (key === today) return i18n.t('portfolio:ledger.today');
  if (key === yesterday) return i18n.t('portfolio:ledger.yesterday');
  return key;
}

/** 连续同一天的行归一组，保持后端给的 id 倒序，不重排 */
function groupByDay(entries: LedgerEntry[]): { key: string; rows: LedgerEntry[] }[] {
  const groups: { key: string; rows: LedgerEntry[] }[] = [];
  for (const e of entries) {
    const key = dayKey(e.createdAt);
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.rows.push(e);
    else groups.push({ key, rows: [e] });
  }
  return groups;
}

/** 一条流水：时刻 | 类型 + 钱包 + 币 + 手续费/备注 | 变动额和变动后余额 */
function EntryRow({ entry }: { entry: LedgerEntry }) {
  const { t } = useTranslation('portfolio');
  return (
    <div className="le2">
      <span className="tm nf">{fmtTime(entry.createdAt)}</span>
      <span className="nm">
        {/* 类型文案走前端词表，认不出的新枚举兜底显示后端下发的 label */}
        {t('biz.' + entry.bizType, { defaultValue: entry.bizTypeLabel })}
        <span className="tag sm">{t(WALLET_LABEL[entry.wallet])}</span>
        {entry.symbol && <span className="tag sm">{entry.symbol.replace('USDT', '')}</span>}
        {entry.fee != null && <small className="nf">{t('ledger.fee', { value: fmtNum(entry.fee) })}</small>}
        {entry.remark && <small>{entry.remark}</small>}
      </span>
      <span className="am nf">
        <b className={deltaTone(entry)}>{entry.delta > 0 ? '+' : ''}{fmtNum(entry.delta)}</b>
        <span>{t('ledger.balanceAfter', { value: fmtNum(entry.balanceAfter) })}</span>
      </span>
    </div>
  );
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

  const dayGroups = useMemo(() => groupByDay(entries), [entries]);

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
          {/* 日期分隔头 */}
          {dayGroups.map(g => (
            <Fragment key={g.key}>
              <div className="day">{dayLabel(g.key)}<small className="nf">{t('ledger.dayCount', { count: g.rows.length })}</small></div>
              {g.rows.map(e => <EntryRow key={e.id} entry={e} />)}
            </Fragment>
          ))}
          {done
            ? <div className="end">{t('ledger.noMore')}</div>
            : <button type="button" className={cn('btn wide mt-4', loadingMore && 'busy')} onClick={loadMore}>{t('ledger.loadMore')}</button>}
        </>
      )}
    </div>
  );
}
