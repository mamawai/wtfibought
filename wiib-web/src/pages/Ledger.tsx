import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '../i18n';
import { ledgerApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { EmptyState } from '../components/EmptyState';
import { cn, fmtNum, fmtTime, parseServerTime } from '../lib/utils';
import { Receipt, RefreshCw, Loader2, TriangleAlert } from 'lucide-react';
import type { LedgerEntry, LedgerBizTypeOption, LedgerWallet } from '../types';

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
 * 一律按"正绿负红"上色会把"又借了 5000"画成好事，所以这两个钱包的配色反过来。
 */
const DEBT_WALLETS: LedgerWallet[] = ['LOAN_PRINCIPAL', 'LOAN_INTEREST'];

function deltaTone(entry: LedgerEntry): string {
  if (entry.delta === 0) return 'text-muted-foreground';
  const good = DEBT_WALLETS.includes(entry.wallet) ? entry.delta < 0 : entry.delta > 0;
  return good ? 'text-gain' : 'text-loss';
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

function EntryRow({ entry }: { entry: LedgerEntry }) {
  const { t } = useTranslation('portfolio');
  const up = entry.delta > 0;
  return (
    <div className="flex items-center gap-3 px-4 py-3 border-b border-border/25 last:border-b-0 hover:bg-accent/25 transition-colors">
      <span className="num text-[11px] text-muted-foreground w-11 shrink-0 tabular-nums">
        {fmtTime(entry.createdAt)}
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
          {/* 类型文案走前端词表，认不出的新枚举兜底显示后端下发的 label */}
          <span className="text-[13px] font-semibold truncate">
            {t('biz.' + entry.bizType, { defaultValue: entry.bizTypeLabel })}
          </span>
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-secondary text-muted-foreground shrink-0">
            {t(WALLET_LABEL[entry.wallet])}
          </span>
          {entry.symbol && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary font-medium shrink-0">
              {entry.symbol.replace('USDT', '')}
            </span>
          )}
        </div>
        {(entry.remark || entry.fee != null) && (
          <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-[10px] text-muted-foreground">
            {entry.remark && <span className="truncate">{entry.remark}</span>}
            {entry.fee != null && <span className="num shrink-0">{t('ledger.fee', { value: fmtNum(entry.fee) })}</span>}
          </div>
        )}
      </div>

      <div className="text-right shrink-0">
        <div className={cn('num text-[13px] font-bold tabular-nums', deltaTone(entry))}>
          {up ? '+' : ''}{fmtNum(entry.delta)}
        </div>
        <div className="num text-[10px] text-muted-foreground tabular-nums">
          {t('ledger.balanceAfter', { value: fmtNum(entry.balanceAfter) })}
        </div>
      </div>
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

  // 换筛选/点刷新都从头拉第一页，游标作废。
  // 失败走 failed 状态而不是 toast：toast 每次渲染是新引用，进不了依赖数组，
  // 而挂 eslint-disable 会让整个组件被 react-hooks 规则跳过（v6 是编译器驱动的），
  // 等于把这个组件将来所有 hooks 问题一起屏蔽掉——为一句错误提示不值当
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

  const dayGroups = useMemo(() => groupByDay(entries), [entries]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div className="space-y-1.5">
              <CardTitle className="flex items-center gap-2.5 text-lg font-black tracking-tight">
                <span className="p-1.5 rounded-xl bg-primary/10 text-primary">
                  <Receipt className="w-4 h-4" />
                </span>
                {t('ledger.title')}
              </CardTitle>
              <p className="text-xs text-muted-foreground leading-relaxed">
                {t('ledger.desc')}
              </p>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <Select
                className="h-9 w-44 text-xs"
                value={bizType}
                onChange={e => setBizType(e.target.value)}
                aria-label={t('ledger.filterLabel')}
              >
                <option value="">{t('ledger.allTypes')}</option>
                {optionGroups.map(g => (
                  <optgroup key={g.name} label={t('bizGroup.' + bizGroupKey(g.options[0].name))}>
                    {g.options.map(o => (
                      <option key={o.name} value={o.name}>{t('biz.' + o.name, { defaultValue: o.label })}</option>
                    ))}
                  </optgroup>
                ))}
              </Select>
              <Button
                variant="outline"
                size="sm"
                className="h-9 gap-2"
                onClick={() => setRefreshNonce(n => n + 1)}
              >
                <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
                {t('common:refresh')}
              </Button>
            </div>
          </div>
        </CardHeader>
      </Card>

      <Card className="overflow-hidden">
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-3">
              {[...Array(8)].map((_, i) => <Skeleton key={i} className="h-12 w-full rounded-lg" />)}
            </div>
          ) : failed ? (
            <EmptyState icon={<TriangleAlert />} text={t('ledger.loadFailed')} />
          ) : entries.length === 0 ? (
            <EmptyState
              icon={<Receipt />}
              text={bizType ? t('ledger.emptyFiltered') : t('ledger.empty')}
            />
          ) : (
            <>
              {dayGroups.map(g => (
                <div key={g.key}>
                  {/* 日期分隔头。没做吸顶：顶栏高度是动态的（桌面多一条26px行情副条、
                      刘海屏还有 safe-area、掉线时多一条横幅），sticky 得写死偏移量，
                      写多写少都会穿帮。为一个装饰性吸顶去引全局高度变量不划算 */}
                  <div className="px-4 py-1.5 bg-card-2 border-y border-border/30">
                    <span className="microlabel font-bold">{dayLabel(g.key)}</span>
                    <span className="ml-2 text-[10px] text-muted-foreground">{t('ledger.dayCount', { count: g.rows.length })}</span>
                  </div>
                  {g.rows.map(e => <EntryRow key={e.id} entry={e} />)}
                </div>
              ))}

              <div className="p-4 flex justify-center border-t border-border/30">
                {done ? (
                  <span className="text-xs text-muted-foreground">{t('ledger.noMore')}</span>
                ) : (
                  <Button variant="outline" size="sm" onClick={loadMore} disabled={loadingMore}>
                    {loadingMore ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('ledger.loadMore')}
                  </Button>
                )}
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
