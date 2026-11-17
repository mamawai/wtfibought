import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { futuresApi } from '../api';
import { Seg } from '../components/ui/seg';
import { Pager } from '../components/ui/pager';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { fmtDateTime, fmtNum, spinIcon } from '../lib/utils';
import { Flame, RefreshCw, TriangleAlert } from 'lucide-react';
import type { ForceOrder, PageResult } from '../types';
import { formatCoinPrice } from '../lib/coinConfig';
import './records.css';
import './ForceOrders.css';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'DOGEUSDT'] as const;
type Sym = (typeof SYMBOLS)[number];
const PAGE_SIZE = 20;

/** 顶上大数的金额：上百万缩成 ₮1.23M，省得撑出格子 */
const usd = (n: number) => n >= 1e6 ? `₮${(n / 1e6).toFixed(2)}M` : `₮${fmtNum(n, 0)}`;

/** 币安口径：强平单方向是被吃掉那侧的反向，SELL 单意味着多头被平掉 */
function SideTag({ side }: { side: string }) {
  const { t } = useTranslation('portfolio');
  return side === 'SELL'
    ? <span className="tag sm r">{t('force.longLiquidated')}</span>
    : <span className="tag sm g">{t('force.shortLiquidated')}</span>;
}

/** 爆仓记录：换币种看各自的强平单，顶上几个数只统计当前这一页（共用样式见 records.css） */
export function ForceOrders() {
  const { t } = useTranslation(['portfolio', 'common']);
  const [symbol, setSymbol] = useState<Sym>('BTCUSDT');
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [result, setResult] = useState<PageResult<ForceOrder>>({
    records: [],
    total: 0,
    size: PAGE_SIZE,
    current: 1,
    pages: 0,
  });
  // loading 由"已加载 key 是否追上请求 key"派生，不在 effect 里同步 setState
  const requestKey = `${symbol}:${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    futuresApi.forceOrders(symbol, page, PAGE_SIZE)
      .then(res => { if (!cancelled) setResult(res); })
      .catch(() => { if (!cancelled) setResult({
        records: [],
        total: 0,
        size: PAGE_SIZE,
        current: page,
        pages: 0,
      }); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, symbol, page]);

  const records = result.records;

  // 当前页多头、空头各爆了多少名义金额
  const stats = useMemo(() => {
    const longAmount = records.filter(o => o.side === 'SELL').reduce((sum, o) => sum + o.amount, 0);
    const shortAmount = records.filter(o => o.side !== 'SELL').reduce((sum, o) => sum + o.amount, 0);
    return { longAmount, shortAmount, totalAmount: longAmount + shortAmount };
  }, [records]);
  // 多空占比按金额；一笔都没有就各占一半
  const longShare = stats.totalAmount ? stats.longAmount / stats.totalAmount * 100 : 50;

  return (
    <div className="page-shell rec p-fo">
      <div className="pg-h">
        <div>
          <h1>{t('force.title')}</h1>
          <p className="lead">{t('force.desc')}</p>
        </div>
      </div>

      <div className="fbar">
        <Seg value={symbol} onChange={s => { setSymbol(s); setPage(1); }} label={t('field.symbol')}
             options={SYMBOLS.map(s => ({ value: s, label: s.replace('USDT', '') }))} />
        <span className="sp" />
        <button type="button" className="ib" aria-label={t('common:refresh')} title={t('common:refresh')}
                onClick={e => { spinIcon(e); setRefreshNonce(n => n + 1); }}>
          <RefreshCw className="ic" />
        </button>
      </div>

      {loading ? <Skeleton className="st3-sk" /> : (
        <>
          <div className="st3">
            <div>
              <span>{t('force.statLong')}</span>
              <b className="big nf dn">{usd(stats.longAmount)}</b>
              <small>{t('force.statLongHint')}</small>
            </div>
            <div>
              <span>{t('force.statShort')}</span>
              <b className="big nf up">{usd(stats.shortAmount)}</b>
              <small>{t('force.statShortHint')}</small>
            </div>
            <div>
              <span>{t('force.statAmount')}</span>
              <b className="big nf">{usd(stats.totalAmount)}</b>
              <small>{t('force.statAmountHint', { count: records.length })}</small>
            </div>
          </div>
          <div className="ratio" aria-hidden="true">
            <i className="bg-loss" style={{ width: `${longShare}%` }} />
            <i className="bg-gain" style={{ width: `${100 - longShare}%` }} />
          </div>
        </>
      )}

      <section className="sec mt-11">
        <div className="sec-h">
          <h2>{t('force.listTitle', { symbol })}</h2>
          {!loading && <span className="r">{t('force.listDesc', { size: PAGE_SIZE, total: result.total })}</span>}
        </div>

        {loading ? (
          <div className="flex flex-col gap-2">
            {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-11" />)}
          </div>
        ) : records.length === 0 ? (
          <EmptyState icon={<Flame />} text={t('force.empty')} />
        ) : (
          <>
            <div className="tw swap">
              <table className="tbl min-w-[720px]">
                <thead>
                  <tr>
                    <th>{t('field.time')}</th>
                    <th>{t('field.side')}</th>
                    <th className="r">{t('field.price')}</th>
                    <th className="r">{t('field.avgPrice')}</th>
                    <th className="r">{t('field.qty')}</th>
                    <th className="r">{t('field.notional')}</th>
                  </tr>
                </thead>
                <tbody>
                  {records.map(order => (
                    <tr key={order.id}>
                      <td className="nf mute">{fmtDateTime(order.tradeTime, true)}</td>
                      <td><SideTag side={order.side} /></td>
                      <td className="r nf">{formatCoinPrice(order.symbol, order.price)}</td>
                      <td className="r nf">{formatCoinPrice(order.symbol, order.avgPrice)}</td>
                      <td className="r nf">{fmtNum(order.quantity, 4)}</td>
                      <td className="r nf font-medium">₮{fmtNum(order.amount, 0)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 手机：一列列表 */}
            <div className="rl">
              {records.map(order => (
                <div key={order.id}>
                  <SideTag side={order.side} />
                  <span className="rr nf"><b>₮{fmtNum(order.amount, 0)}</b></span>
                  <small className="nf">
                    {fmtDateTime(order.tradeTime, true)} · {fmtNum(order.quantity, 4)} @ {formatCoinPrice(order.symbol, order.price)}
                  </small>
                </div>
              ))}
            </div>

            <Pager page={page} pages={result.pages} onChange={setPage} />
          </>
        )}

        <div className="note w mt-[18px]">
          <TriangleAlert className="ic" />
          <div>{t('force.warn')}</div>
        </div>
      </section>
    </div>
  );
}
