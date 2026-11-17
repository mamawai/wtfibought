import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { publicTradeApi } from '../api';
import { Seg } from '../components/ui/seg';
import { Select } from '../components/ui/select';
import { Pager } from '../components/ui/pager';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { SideTag, SymIcon } from '../components/TradeBits';
import { fmtDateTime, fmtNum, spinIcon } from '../lib/utils';
import { formatCoinPrice } from '../lib/coinConfig';
import { tradeHref, tradeSymbolName } from '../lib/orderSide';
import { Bot, Receipt, RefreshCw } from 'lucide-react';
import type { PageResult, PublicTrade } from '../types';
import './records.css';

const PAGE_SIZE = 20;

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT', 'BNBUSDT', 'ZECUSDT', 'HYPEUSDT'] as const;
type Kind = '' | 'SPOT' | 'FUTURES';

const EMPTY: PageResult<PublicTrade> = { records: [], total: 0, size: PAGE_SIZE, current: 1, pages: 0 };

/** 交易者：假名；策略账户是机器人不是人，单独标出来比一串假名有信息量 */
function Trader({ alias, isAi }: { alias: string; isAi: boolean }) {
  const { t } = useTranslation('portfolio');
  if (isAi) return <span className="tag sm g"><Bot className="ic" />{t('trades.aiTrader')}</span>;
  return <span className="nf">#{alias}</span>;
}

/** 全站成交：所有人的现货与合约成交按时间倒序混排，点一行进那个标的的交易页（共用样式见 records.css） */
export function Trades() {
  const navigate = useNavigate();
  const { t } = useTranslation(['portfolio', 'common']);

  const [symbol, setSymbol] = useState('');
  const [kind, setKind] = useState<Kind>('');
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [result, setResult] = useState<PageResult<PublicTrade>>(EMPTY);

  // loading 由"已加载 key 是否追上请求 key"派生，不在 effect 里同步 setState（同 ForceOrders）
  const requestKey = `${symbol}:${kind}:${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    publicTradeApi.list({ symbol: symbol || undefined, kind: kind || undefined, pageNum: page, pageSize: PAGE_SIZE })
      .then(res => { if (!cancelled) setResult(res); })
      .catch(() => { if (!cancelled) setResult({ ...EMPTY, current: page }); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, symbol, kind, page]);

  const records = result.records;
  const go = (row: PublicTrade) => navigate(tradeHref(row.symbol));

  return (
    <div className="page-shell rec">
      <div className="pg-h">
        <div>
          <h1>{t('trades.title')}{!loading && <small>{t('trades.total', { count: result.total })}</small>}</h1>
          <p className="lead">{t('trades.desc')}</p>
        </div>
      </div>

      <div className="fbar">
        <Seg value={kind} onChange={k => { setKind(k); setPage(1); }} label={t('trade:col.type')} options={[
          { value: '', label: t('common:all') },
          { value: 'SPOT', label: t('trades.spot') },
          { value: 'FUTURES', label: t('trades.futures') },
        ]} />
        <Select value={symbol} onChange={s => { setSymbol(s); setPage(1); }} label={t('field.symbol')} options={[
          { value: '', label: t('trades.allSymbols') },
          ...SYMBOLS.map(s => ({ value: s, label: tradeSymbolName(s) })),
        ]} />
        <span className="sp" />
        <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')}
                onClick={e => { spinIcon(e); setRefreshNonce(n => n + 1); }}>
          <RefreshCw className="ic" />
        </button>
      </div>

      {loading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 10 }, (_, i) => <Skeleton key={i} className="h-11" />)}
        </div>
      ) : records.length === 0 ? (
        <EmptyState icon={<Receipt />} text={t('trades.empty')} />
      ) : (
        <>
          {/* 桌面：表格 */}
          <div className="tw swap">
            <table className="tbl hov min-w-[820px]">
              <thead>
                <tr>
                  <th>{t('field.time')}</th>
                  <th>{t('field.trader')}</th>
                  <th>{t('field.symbol')}</th>
                  <th>{t('field.side')}</th>
                  <th className="r">{t('field.price')}</th>
                  <th className="r">{t('field.qty')}</th>
                  <th className="r">{t('field.amount')}</th>
                </tr>
              </thead>
              <tbody>
                {records.map(row => (
                  <tr key={`${row.kind}-${row.tradeId}`} onClick={() => go(row)}>
                    <td className="nf mute">{fmtDateTime(row.createdAt, true)}</td>
                    <td><Trader alias={row.alias} isAi={row.isAi} /></td>
                    <td>
                      <span className="sym">
                        <SymIcon symbol={row.symbol} />
                        {tradeSymbolName(row.symbol)}
                        {row.kind === 'FUTURES' && <small>{t('trades.futures')}</small>}
                      </span>
                    </td>
                    <td><SideTag orderSide={row.orderSide} /></td>
                    <td className="r nf">{formatCoinPrice(row.symbol, row.filledPrice)}</td>
                    <td className="r nf">{fmtNum(row.quantity, 4)}</td>
                    <td className="r nf font-medium">₮{fmtNum(row.filledAmount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 手机：一列列表 */}
          <div className="rl">
            {records.map(row => (
              <button key={`${row.kind}-${row.tradeId}`} type="button" onClick={() => go(row)}>
                <SideTag orderSide={row.orderSide} />
                <b>{tradeSymbolName(row.symbol)}</b>
                {row.kind === 'FUTURES' && <span className="mute text-[12px]">{t('trades.futures')}</span>}
                {row.isAi && <Bot className="ic mute" />}
                <span className="rr nf"><b>₮{fmtNum(row.filledAmount)}</b></span>
                <small className="nf">
                  {fmtDateTime(row.createdAt)} · {row.isAi ? t('trades.aiTrader') : `#${row.alias}`} · {fmtNum(row.quantity, 4)} @ {formatCoinPrice(row.symbol, row.filledPrice)}
                </small>
              </button>
            ))}
          </div>

          <Pager page={page} pages={result.pages} onChange={setPage} />
        </>
      )}
    </div>
  );
}
