import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RefreshCw } from 'lucide-react';
import { cryptoOrderApi, futuresApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { Dialog, DialogContent, DialogHeader } from '../ui/dialog';
import { Skeleton } from '../ui/skeleton';
import { useStagger } from '../../hooks/useStagger';
import { fmtNum, fmtDateTime } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';
import { orderSideView } from '../../lib/orderSide';
import type { CryptoOrder, FuturesOrder, PageResult } from '../../types';

// 筛选/状态表都存词表 key（模块常量拿不到 t，渲染时现查，切语言才跟着变）
const ORDER_STATUS_FILTERS = [
  { labelKey: 'common:all', value: '' },
  { labelKey: 'status.pending', value: 'PENDING' },
  { labelKey: 'status.filled', value: 'FILLED' },
  { labelKey: 'status.cancelled', value: 'CANCELLED' },
];

const FUTURES_ORDER_FILTERS = [
  { labelKey: 'common:all', value: '' },
  { labelKey: 'status.pending', value: 'PENDING' },
  { labelKey: 'status.processing', value: 'PROCESSING' },
  { labelKey: 'status.filled', value: 'FILLED' },
  { labelKey: 'status.cancelled', value: 'CANCELLED' },
];

/** 状态 → 词表 key + chip 修饰：挂单灰框、撤销/强平/止损红框、止盈绿框，其余素框 */
const STATUS_MAP: Record<string, { labelKey: string; chip: string }> = {
  PENDING: { labelKey: 'status.pending', chip: 'mute' },
  TRIGGERED: { labelKey: 'status.triggered', chip: '' },
  PROCESSING: { labelKey: 'status.processing', chip: 'mute' },
  FILLED: { labelKey: 'status.filled', chip: '' },
  CANCELLED: { labelKey: 'status.cancelled', chip: 'dn' },
  LIQUIDATED: { labelKey: 'status.liquidated', chip: 'dn' },
  STOP_LOSS: { labelKey: 'status.stopLoss', chip: 'dn' },
  TAKE_PROFIT: { labelKey: 'status.takeProfit', chip: 'up' },
};

/** 详情弹窗里的一行 */
function DRow({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div className="kv"><span className="k">{k}</span><span className="v num">{v}</span></div>
  );
}

/** 表格行：现货/合约两种订单都先摊平成这个形状，表体只写一遍 */
interface Row {
  id: number;
  time: string;
  type: string;
  side: { label: string; cls: string };
  price: string;
  qty: string;
  amount: string;
  /** 合约已实现盈亏，非 0 时跟在成交额下面一行 */
  pnl: number | null;
  status: string;
  chip: string;
  pending: boolean;
}

/** 订单行点开的完整详情 */
type OrderDetail = { kind: 'spot'; o: CryptoOrder } | { kind: 'futures'; o: FuturesOrder };

/**
 * 订单节：按模式显示现货/合约订单表（状态筛选 + 分页 + 撤单 + 详情）。
 * 父级成交后 bump 对应 refreshKey 触发回到第一页重拉；撤单成功调 onCancelled 让父级刷余额/持仓。
 */
export function CoinOrdersCard({ symbol, mode, spotRefreshKey, futuresRefreshKey, onCancelled }: {
  symbol: string;
  mode: 'spot' | 'futures';
  spotRefreshKey: number;
  futuresRefreshKey: number;
  /** 撤单会解冻余额/持仓、释放全仓挂单占用 */
  onCancelled?: (kind: 'spot' | 'futures') => void;
}) {
  const { t } = useTranslation(['trade', 'common', 'market']);
  const { toast } = useToast();
  const rowsRef = useStagger<HTMLTableSectionElement>();
  const fmtPrice = (n?: number | null) => formatCoinPrice(symbol, n);
  const base = symbol.replace('USDT', '');
  /** 状态徽标：认不出的状态原样显示后端值 */
  const statusOf = (status: string) => {
    const st = STATUS_MAP[status];
    return st ? { label: t(st.labelKey), chip: st.chip } : { label: status, chip: '' };
  };
  /** 方向：与首页最新成交、成交页同一份口径（orderSideView 自带语言感知与涨跌色） */
  const sideOf = (orderSide: string) => {
    const v = orderSideView(orderSide);
    return { label: v.label, cls: v.tone === 'buy' ? 'up' : 'dn' };
  };
  const typeOf = (orderType: string, leverage: number) =>
    `${orderType === 'MARKET' ? t('orderType.market') : t('orderType.limit')}${leverage > 1 ? ` ${leverage}x` : ''}`;

  // 现货订单
  const [orderFilter, setOrderFilter] = useState('');
  const [orders, setOrders] = useState<CryptoOrder[]>([]);
  const [orderPage, setOrderPage] = useState(1);
  const [orderPages, setOrderPages] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);

  // 合约订单
  const [futuresOrderFilter, setFuturesOrderFilter] = useState('');
  const [futuresOrders, setFuturesOrders] = useState<FuturesOrder[]>([]);
  const [futuresOrderPage, setFuturesOrderPage] = useState(1);
  const [futuresOrderPages, setFuturesOrderPages] = useState(0);
  const [futuresOrdersLoading, setFuturesOrdersLoading] = useState(false);

  const [detail, setDetail] = useState<OrderDetail | null>(null);

  // 请求序号：翻页/筛选/成交刷新/撤单刷新可能叠着发，只认最后发出的那次
  const spotSeqRef = useRef(0);
  const futuresSeqRef = useRef(0);

  const fetchOrders = useCallback(async (status: string, page: number) => {
    const seq = ++spotSeqRef.current;
    setOrdersLoading(true);
    try {
      const res = await cryptoOrderApi.list(status || undefined, page, 10, symbol) as unknown as PageResult<CryptoOrder>;
      if (seq !== spotSeqRef.current) return;
      setOrders(res.records);
      setOrderPages(res.pages);
    } catch { if (seq === spotSeqRef.current) setOrders([]); }
    finally { if (seq === spotSeqRef.current) setOrdersLoading(false); }
  }, [symbol]);

  const fetchFuturesOrders = useCallback(async (status: string, page: number) => {
    const seq = ++futuresSeqRef.current;
    setFuturesOrdersLoading(true);
    try {
      const res = await futuresApi.orders(status || undefined, page, 10, symbol) as unknown as PageResult<FuturesOrder>;
      if (seq !== futuresSeqRef.current) return;
      setFuturesOrders(res.records);
      setFuturesOrderPages(res.pages);
    } catch (e) {
      if (seq !== futuresSeqRef.current) return;
      console.error('查询合约订单失败', e);
      setFuturesOrders([]);
    } finally {
      if (seq === futuresSeqRef.current) setFuturesOrdersLoading(false);
    }
  }, [symbol]);

  // 成交后回到第一页（key 变化时；filter/page 常规变化走下面的拉取 effect）
  useEffect(() => { setOrderPage(1); }, [spotRefreshKey]);
  useEffect(() => { setFuturesOrderPage(1); }, [futuresRefreshKey]);

  useEffect(() => { fetchOrders(orderFilter, orderPage); }, [orderFilter, orderPage, spotRefreshKey, fetchOrders]);

  useEffect(() => {
    if (mode === 'futures') fetchFuturesOrders(futuresOrderFilter, futuresOrderPage);
  }, [futuresOrderFilter, futuresOrderPage, futuresRefreshKey, mode, fetchFuturesOrders]);

  const handleCancel = async (orderId: number) => {
    try {
      await cryptoOrderApi.cancel(orderId);
      toast(t('toast.cancelled'), 'success');
      fetchOrders(orderFilter, orderPage);
      onCancelled?.('spot');
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.cancelFailed'), 'error');
    }
  };

  const handleFuturesCancel = async (orderId: number) => {
    try {
      await futuresApi.cancel(orderId);
      toast(t('toast.cancelled'), 'success');
      fetchFuturesOrders(futuresOrderFilter, futuresOrderPage);
      onCancelled?.('futures');
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.cancelFailed'), 'error');
    }
  };

  const isFutures = mode === 'futures';
  const loading = isFutures ? futuresOrdersLoading : ordersLoading;
  const filters = isFutures ? FUTURES_ORDER_FILTERS : ORDER_STATUS_FILTERS;
  const filter = isFutures ? futuresOrderFilter : orderFilter;
  const page = isFutures ? futuresOrderPage : orderPage;
  const pages = isFutures ? futuresOrderPages : orderPages;
  const empty = isFutures ? futuresOrders.length === 0 : orders.length === 0;

  const refresh = () => isFutures
    ? fetchFuturesOrders(futuresOrderFilter, futuresOrderPage)
    : fetchOrders(orderFilter, orderPage);
  const setFilter = (v: string) => {
    if (isFutures) { setFuturesOrderFilter(v); setFuturesOrderPage(1); }
    else { setOrderFilter(v); setOrderPage(1); }
  };
  const setPage = (fn: (p: number) => number) =>
    isFutures ? setFuturesOrderPage(fn) : setOrderPage(fn);
  const openDetail = (id: number) => setDetail(isFutures
    ? { kind: 'futures', o: futuresOrders.find(o => o.orderId === id)! }
    : { kind: 'spot', o: orders.find(o => o.orderId === id)! });
  const cancelRow = (id: number) => isFutures ? handleFuturesCancel(id) : handleCancel(id);

  // 合约：价格取成交价，没成交就显示挂单价；数量是纯张数。现货：数量带币名
  const rows: Row[] = isFutures
    ? futuresOrders.map(o => {
      const st = statusOf(o.status);
      return {
        id: o.orderId,
        time: fmtDateTime(o.createdAt),
        type: typeOf(o.orderType, o.leverage),
        side: sideOf(o.orderSide),
        price: fmtPrice(o.filledPrice ?? o.limitPrice),
        qty: String(o.quantity),
        amount: o.filledAmount != null ? fmtNum(o.filledAmount) : '-',
        pnl: o.realizedPnl != null && o.realizedPnl !== 0 ? o.realizedPnl : null,
        status: st.label,
        chip: st.chip,
        pending: o.status === 'PENDING',
      };
    })
    : orders.map(o => {
      const st = statusOf(o.status);
      return {
        id: o.orderId,
        time: fmtDateTime(o.createdAt),
        type: typeOf(o.orderType, o.leverage),
        side: sideOf(o.orderSide),
        price: fmtPrice(o.filledPrice ?? o.limitPrice ?? o.triggerPrice),
        qty: `${o.quantity} ${base}`,
        amount: o.filledAmount != null ? fmtNum(o.filledAmount) : '-',
        pnl: null,
        status: st.label,
        chip: st.chip,
        pending: o.status === 'PENDING',
      };
    });

  return (
    <section className="sec">
      <div className="sec-h flex-wrap">
        <h2>
          {t('orders.title')}
          <small>{symbol} · {isFutures ? t('market:coin.futures') : t('market:coin.spot')}</small>
        </h2>
        <span className="self-center flex items-center gap-2">
          <span className="seg">
            {filters.map(f => (
              <button key={f.value} type="button" className={filter === f.value ? 'on' : ''} onClick={() => setFilter(f.value)}>
                {t(f.labelKey)}
              </button>
            ))}
          </span>
          <button type="button" className="ibtn" onClick={refresh} title={t('common:refresh')}>
            <RefreshCw className={`ic ${loading ? 'animate-spin' : ''}`} />
          </button>
        </span>
      </div>

      {loading && empty ? (
        <Skeleton className="w-full h-32" />
      ) : empty ? (
        <div className="py-10 text-center text-[14px] mute">{isFutures ? t('orders.emptyFutures') : t('orders.emptySpot')}</div>
      ) : (
        <>
          {/* 8 列在手机上挤不下，整表横向滚动 */}
          <div className="overflow-x-auto">
            <table className="tbl num min-w-[760px]">
              <thead>
                <tr>
                  <th>{t('col.time')}</th>
                  <th>{t('col.type')}</th>
                  <th>{t('col.side')}</th>
                  <th className="r">{t('col.price')}</th>
                  <th className="r">{t('col.qty')}</th>
                  <th className="r">{t('col.amount')}</th>
                  <th className="r">{t('col.status')}</th>
                  <th className="r">{t('col.actions')}</th>
                </tr>
              </thead>
              <tbody ref={rowsRef}>
                {rows.map(r => (
                  <tr key={r.id}>
                    <td className="mute whitespace-nowrap">{r.time}</td>
                    <td>{r.type}</td>
                    <td className={`font-bold ${r.side.cls}`}>{r.side.label}</td>
                    <td className="r">{r.price}</td>
                    <td className="r whitespace-nowrap">{r.qty}</td>
                    <td className="r">
                      {r.amount}
                      {r.pnl != null && (
                        <span className={`sub ${r.pnl > 0 ? 'up' : 'dn'}`}>{r.pnl > 0 ? '+' : ''}{fmtNum(r.pnl)}</span>
                      )}
                    </td>
                    <td className="r"><span className={`chip ${r.chip}`}>{r.status}</span></td>
                    <td className="r">
                      <span className="inline-flex gap-1.5">
                        {r.pending && (
                          <button type="button" className="btn xs" onClick={() => cancelRow(r.id)}>{t('orders.cancelShort')}</button>
                        )}
                        <button type="button" className="btn xs" onClick={() => openDetail(r.id)}>{t('orders.detailBtn')}</button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {pages > 1 && (
            <div className="flex items-center gap-3 pt-3.5">
              <button type="button" className="btn xs disabled:opacity-40 disabled:cursor-default" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>{t('orders.prev')}</button>
              <button type="button" className="btn xs disabled:opacity-40 disabled:cursor-default" disabled={page >= pages} onClick={() => setPage(p => p + 1)}>{t('orders.next')}</button>
              <span className="text-[12.5px] mute">{t('orders.page', { page, pages })}</span>
            </div>
          )}
        </>
      )}

      {/* 订单详情 */}
      <Dialog open={detail != null} onClose={() => setDetail(null)} className="max-w-sm bg-background border border-foreground shadow-none">
        <DialogHeader>
          <span className="text-[15px] font-bold">{t('orders.detail')}</span>
        </DialogHeader>
        <DialogContent>
          {detail?.kind === 'futures' && (() => {
            const o = detail.o;
            const sm = sideOf(o.orderSide);
            const st = statusOf(o.status);
            const hasPnl = o.realizedPnl != null && o.realizedPnl !== 0;
            return (
              <div className="pb-2">
                <DRow k={t('col.time')} v={fmtDateTime(o.createdAt)} />
                <DRow k={t('col.side')} v={<span className={sm.cls}>{sm.label}</span>} />
                <DRow k={t('col.type')} v={o.orderType === 'MARKET' ? t('orderType.market') : t('orderType.limit')} />
                <DRow k={t('col.qty')} v={o.quantity} />
                <DRow k={t('col.leverage')} v={`${o.leverage}x`} />
                <DRow k={t('col.limit')} v={o.limitPrice != null ? fmtPrice(o.limitPrice) : '-'} />
                <DRow k={t('col.fillPrice')} v={o.filledPrice != null ? fmtPrice(o.filledPrice) : '-'} />
                <DRow k={t('col.amount')} v={o.filledAmount != null ? fmtNum(o.filledAmount) : '-'} />
                <DRow k={t('col.pnl')} v={hasPnl
                  ? <span className={o.realizedPnl! > 0 ? 'up' : 'dn'}>{o.realizedPnl! > 0 ? '+' : ''}{fmtNum(o.realizedPnl!)}</span>
                  : '-'} />
                <DRow k={t('col.status')} v={<span className={`chip ${st.chip}`}>{st.label}</span>} />
                {o.status === 'PENDING' && (
                  <button
                    type="button"
                    className="btn loss w-full mt-4"
                    onClick={async () => { await handleFuturesCancel(o.orderId); setDetail(null); }}
                  >
                    {t('orders.cancelOrder')}
                  </button>
                )}
              </div>
            );
          })()}
          {detail?.kind === 'spot' && (() => {
            const o = detail.o;
            const sm = sideOf(o.orderSide);
            const st = statusOf(o.status);
            return (
              <div className="pb-2">
                <DRow k={t('col.time')} v={fmtDateTime(o.createdAt)} />
                <DRow k={t('col.side')} v={<span className={sm.cls}>{sm.label}</span>} />
                <DRow k={t('col.type')} v={typeOf(o.orderType, o.leverage)} />
                <DRow k={t('col.qty')} v={o.quantity} />
                <DRow k={t('col.orderPrice')} v={o.limitPrice != null ? fmtPrice(o.limitPrice) : '-'} />
                <DRow k={t('col.triggerPrice')} v={o.triggerPrice != null ? fmtPrice(o.triggerPrice) : '-'} />
                <DRow k={t('col.amount')} v={o.filledAmount != null ? fmtNum(o.filledAmount) : '-'} />
                <DRow k={t('col.status')} v={<span className={`chip ${st.chip}`}>{st.label}</span>} />
                {o.status === 'PENDING' && (
                  <button
                    type="button"
                    className="btn loss w-full mt-4"
                    onClick={async () => { await handleCancel(o.orderId); setDetail(null); }}
                  >
                    {t('orders.cancelOrder')}
                  </button>
                )}
              </div>
            );
          })()}
        </DialogContent>
      </Dialog>
    </section>
  );
}
