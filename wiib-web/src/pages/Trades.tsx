import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { publicTradeApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { orderSideView, tradeHref, tradeSymbolName } from '../lib/orderSide';
import { Activity, Bot, ChevronLeft, ChevronRight, RefreshCw, Shield } from 'lucide-react';
import type { PageResult, PublicTrade } from '../types';

const PAGE_SIZE = 20;

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT', 'BNBUSDT', 'ZECUSDT', 'HYPEUSDT'] as const;
// 静态数组存词表 key 不存文案，渲染时才 t()
const KINDS = [
  { key: 'all', value: undefined, labelKey: 'common:all' },
  { key: 'spot', value: 'SPOT' as const, labelKey: 'trades.spot' },
  { key: 'futures', value: 'FUTURES' as const, labelKey: 'trades.futures' },
];

const EMPTY: PageResult<PublicTrade> = { records: [], total: 0, size: PAGE_SIZE, current: 1, pages: 0 };

/** 假名胶囊。策略账户是机器人不是人，单独标出来比一串假名有信息量 */
function Trader({ alias, isAi }: { alias: string; isAi: boolean }) {
  const { t } = useTranslation('portfolio');
  if (isAi) {
    return (
      <span className="inline-flex items-center gap-1 text-[11px] font-medium px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
        <Bot className="w-3 h-3" />
        {t('trades.aiTrader')}
      </span>
    );
  }
  return (
    <span className="num text-[11px] text-muted-foreground px-1.5 py-0.5 rounded bg-secondary">
      #{alias}
    </span>
  );
}

function SideTag({ orderSide }: { orderSide: string }) {
  // 方向词表在 labels ns（orderSideView 里查），这里订一份 t 只为切语言时跟着重渲染
  useTranslation('labels');
  const { label, tone } = orderSideView(orderSide);
  return (
    <span className={cn(
      'text-[11px] font-bold px-1.5 py-0.5 rounded whitespace-nowrap',
      tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
    )}>
      {label}
    </span>
  );
}

export function Trades() {
  const navigate = useNavigate();
  const { t } = useTranslation(['portfolio', 'common']);

  const [symbol, setSymbol] = useState<string | undefined>(undefined);
  const [kind, setKind] = useState<'SPOT' | 'FUTURES' | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [result, setResult] = useState<PageResult<PublicTrade>>(EMPTY);

  // loading 由"已加载 key 是否追上请求 key"派生，不在 effect 里同步 setState（同 ForceOrders）
  const requestKey = `${symbol ?? ''}:${kind ?? ''}:${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    publicTradeApi.list({ symbol, kind, pageNum: page, pageSize: PAGE_SIZE })
      .then(res => { if (!cancelled) setResult(res); })
      .catch(() => { if (!cancelled) setResult({ ...EMPTY, current: page }); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, symbol, kind, page]);

  const records = result.records;
  const go = (row: PublicTrade) => navigate(tradeHref(row.symbol));

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div className="space-y-1.5">
              <CardTitle className="flex items-center gap-2.5 text-lg font-black tracking-tight">
                <span className="p-1.5 rounded-xl bg-primary/10 text-primary">
                  <Activity className="w-4 h-4" />
                </span>
                {t('trades.title')}
              </CardTitle>
              <p className="flex items-start gap-1.5 text-xs text-muted-foreground leading-relaxed">
                <Shield className="w-3.5 h-3.5 mt-0.5 shrink-0" />
                <span>{t('trades.desc')}</span>
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              className="h-9 w-fit gap-2 shrink-0"
              onClick={() => setRefreshNonce(n => n + 1)}
            >
              <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
              {t('common:refresh')}
            </Button>
          </div>

          <div className="flex flex-wrap items-center gap-1.5 pt-1">
            {KINDS.map(k => (
              <button
                key={k.key}
                onClick={() => { setKind(k.value); setPage(1); }}
                className={cn(
                  'px-3 py-1.5 rounded-lg text-xs font-bold border transition-colors',
                  kind === k.value
                    ? 'bg-foreground text-background border-foreground'
                    : 'bg-card border-border text-foreground hover:bg-surface-hover',
                )}
              >
                {t(k.labelKey)}
              </button>
            ))}
            <span className="w-px h-5 bg-border mx-1" />
            <button
              onClick={() => { setSymbol(undefined); setPage(1); }}
              className={cn(
                'px-3 py-1.5 rounded-lg text-xs font-bold border transition-colors',
                symbol === undefined
                  ? 'bg-foreground text-background border-foreground'
                  : 'bg-card border-border text-foreground hover:bg-surface-hover',
              )}
            >
              {t('trades.allSymbols')}
            </button>
            {SYMBOLS.map(s => (
              <button
                key={s}
                onClick={() => { setSymbol(s); setPage(1); }}
                className={cn(
                  'px-3 py-1.5 rounded-lg text-xs font-bold border transition-colors',
                  symbol === s
                    ? 'bg-foreground text-background border-foreground'
                    : 'bg-card border-border text-foreground hover:bg-surface-hover',
                )}
              >
                {tradeSymbolName(s)}
              </button>
            ))}
          </div>
        </CardHeader>
      </Card>

      <Card className="overflow-hidden">
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-3">
              {[...Array(8)].map((_, i) => <Skeleton key={i} className="h-14 w-full rounded-lg" />)}
            </div>
          ) : records.length === 0 ? (
            <EmptyState icon={<Activity />} text={t('trades.empty')} />
          ) : (
            <>
              {/* 桌面：表格 */}
              <div className="hidden md:block overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border/50 text-muted-foreground">
                      <th className="px-5 py-3 text-left font-bold">{t('field.time')}</th>
                      <th className="px-4 py-3 text-left font-bold">{t('field.trader')}</th>
                      <th className="px-4 py-3 text-left font-bold">{t('field.symbol')}</th>
                      <th className="px-4 py-3 text-left font-bold">{t('field.side')}</th>
                      <th className="px-4 py-3 text-right font-bold">{t('field.price')}</th>
                      <th className="px-4 py-3 text-right font-bold">{t('field.qty')}</th>
                      <th className="px-5 py-3 text-right font-bold">{t('field.amount')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.map(row => (
                      <tr
                        key={`${row.kind}-${row.tradeId}`}
                        onClick={() => go(row)}
                        className="border-b border-border/30 hover:bg-accent/30 transition-colors cursor-pointer"
                      >
                        <td className="px-5 py-3 num text-xs text-muted-foreground whitespace-nowrap">
                          {fmtDateTime(row.createdAt, true)}
                        </td>
                        <td className="px-4 py-3"><Trader alias={row.alias} isAi={row.isAi} /></td>
                        <td className="px-4 py-3">
                          <span className="font-bold">{tradeSymbolName(row.symbol)}</span>
                          {row.kind === 'FUTURES' && (
                            <span className="ml-1.5 text-[10px] text-muted-foreground">{t('trades.futures')}</span>
                          )}
                        </td>
                        <td className="px-4 py-3"><SideTag orderSide={row.orderSide} /></td>
                        <td className="px-4 py-3 text-right num font-bold">
                          {formatCoinPrice(row.symbol, row.filledPrice)}
                        </td>
                        <td className="px-4 py-3 text-right num text-muted-foreground">
                          {fmtNum(row.quantity, 4)}
                        </td>
                        <td className="px-5 py-3 text-right num font-bold">${fmtNum(row.filledAmount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* 手机：卡片列表 */}
              <div className="md:hidden divide-y divide-border/30">
                {records.map(row => (
                  <button
                    key={`${row.kind}-${row.tradeId}`}
                    type="button"
                    onClick={() => go(row)}
                    className="w-full text-left px-4 py-3 space-y-2 hover:bg-accent/30 active:bg-accent/50 transition-colors"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <SideTag orderSide={row.orderSide} />
                        <span className="font-bold text-[13px] truncate">{tradeSymbolName(row.symbol)}</span>
                        {row.kind === 'FUTURES' && (
                          <span className="text-[10px] text-muted-foreground shrink-0">{t('trades.futures')}</span>
                        )}
                      </div>
                      <span className="num text-[13px] font-bold shrink-0">${fmtNum(row.filledAmount)}</span>
                    </div>
                    <div className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
                      <Trader alias={row.alias} isAi={row.isAi} />
                      <span className="num">
                        {fmtNum(row.quantity, 4)} @ {formatCoinPrice(row.symbol, row.filledPrice)}
                      </span>
                      <span className="num shrink-0">{fmtDateTime(row.createdAt)}</span>
                    </div>
                  </button>
                ))}
              </div>

              <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                <span className="text-xs text-muted-foreground">
                  {t('pager.page', { page: result.current, pages: Math.max(result.pages, 1) })}
                  {' · '}
                  {t('trades.total', { count: result.total })}
                </span>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost" size="sm" className="h-8 w-8 p-0"
                    disabled={page <= 1}
                    onClick={() => setPage(p => p - 1)}
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </Button>
                  <Button
                    variant="ghost" size="sm" className="h-8 w-8 p-0"
                    disabled={page >= result.pages}
                    onClick={() => setPage(p => p + 1)}
                  >
                    <ChevronRight className="w-4 h-4" />
                  </Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
