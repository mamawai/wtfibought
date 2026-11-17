import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Receipt, RefreshCw } from 'lucide-react';
import { cryptoOrderApi, futuresApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { Dialog, DialogFooter, DialogHeader } from '../ui/dialog';
import { Seg } from '../ui/seg';
import { Pager } from '../ui/pager';
import { Skeleton } from '../ui/skeleton';
import { EmptyState } from '../EmptyState';
import { SureButton } from '../SureButton';
import { cn, fmtNum, fmtDateTime, spinIcon } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';
import { orderSideView } from '../../lib/orderSide';
import type { CryptoOrder, FuturesOrder } from '../../types';

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

/** 状态 → 词表 key + 标签颜色：撤销/强平/止损红、止盈绿，其余灰 */
const STATUS_MAP: Record<string, { labelKey: string; tone: string }> = {
  PENDING: { labelKey: 'status.pending', tone: '' },
  TRIGGERED: { labelKey: 'status.triggered', tone: '' },
  PROCESSING: { labelKey: 'status.processing', tone: '' },
  FILLED: { labelKey: 'status.filled', tone: '' },
  CANCELLED: { labelKey: 'status.cancelled', tone: 'r' },
  LIQUIDATED: { labelKey: 'status.liquidated', tone: 'r' },
  STOP_LOSS: { labelKey: 'status.stopLoss', tone: 'r' },
  TAKE_PROFIT: { labelKey: 'status.takeProfit', tone: 'g' },
};

/** 详情弹窗里的一行 */
function DRow({ k, v }: { k: string; v: ReactNode }) {
  return <div><span>{k}</span><b>{v}</b></div>;
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
  tone: string;
  pending: boolean;
}

/** 订单行点开的完整详情 */
type OrderDetail = { kind: 'spot'; o: CryptoOrder } | { kind: 'futures'; o: FuturesOrder };

/**
 * 订单节：按模式显示现货/合约订单表（状态筛选 + 分页 + 撤单 + 详情）。
 * 父级成交后 bump 对应 refreshKey 触发回到第一页重拉；撤单成功调 onCancelled 让父级刷余额/持仓。
 * 撤单两段确认：点一下变「确认撤单」，3 秒没点第二下自动还原；头上的「一键撤单」同理。
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
  const fmtPrice = (n?: number | null) => formatCoinPrice(symbol, n);
  const base = symbol.replace('USDT', '');
  /** 状态标签：认不出的状态原样显示后端值 */
  const statusOf = (status: string) => {
    const st = STATUS_MAP[status];
    return st ? { label: t(st.labelKey), tone: st.tone } : { label: status, tone: '' };
  };
  /** 方向：与首页最新成交、成交页同一份口径（orderSideView 自带语言感知与涨跌色） */
  const sideOf = (orderSide: string) => {
    const v = orderSideView(orderSide);
    return { label: v.label, cls: v.tone === 'buy' ? 'up' : 'dn' };
  };
  const typeOf = (orderType: string, leverage: number) =>
    `${orderType === 'MARKET' ? t('orderType.market') : t('orderType.limit')}${leverage > 1 ? ` · ${leverage}x` : ''}`;

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
  // 撤单：等第二下确认的是哪一行；正在撤的是哪一单
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  // 一键撤单：正在一单单撤
  const [cancellingAll, setCancellingAll] = useState(false);

  // 请求序号：翻页/筛选/成交刷新/撤单刷新可能叠着发，只认最后发出的那次
  const spotSeqRef = useRef(0);
  const futuresSeqRef = useRef(0);

  const fetchOrders = useCallback(async (status: string, page: number) => {
    const seq = ++spotSeqRef.current;
    setOrdersLoading(true);
    try {
      const res = await cryptoOrderApi.list(status || undefined, page, 10, symbol);
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
      const res = await futuresApi.orders(status || undefined, page, 10, symbol);
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

  // 撤单确认 3 秒没点第二下自动还原
  useEffect(() => {
    if (confirmId == null) return;
    const timer = window.setTimeout(() => setConfirmId(null), 3000);
    return () => window.clearTimeout(timer);
  }, [confirmId]);

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
  const setPage = (p: number) => isFutures ? setFuturesOrderPage(p) : setOrderPage(p);
  const openDetail = (id: number) => setDetail(isFutures
    ? { kind: 'futures', o: futuresOrders.find(o => o.orderId === id)! }
    : { kind: 'spot', o: orders.find(o => o.orderId === id)! });
  const cancelOrder = async (kind: 'spot' | 'futures', id: number) => {
    setConfirmId(null);
    setCancellingId(id);
    await (kind === 'futures' ? handleFuturesCancel(id) : handleCancel(id));
    setCancellingId(null);
  };

  /**
   * 一键撤单：把这个币在当前模式下还挂着的单全撤掉。先拉一遍待成交的（不止眼前这一页），再一单单撤；
   * 中途成交了的那单撤不掉，最后报撤了几单、几单没撤掉。止盈止损挂在仓位上不是订单，不归这里管
   */
  const handleCancelAll = async () => {
    setCancellingAll(true);
    try {
      const pending = isFutures
        ? (await futuresApi.orders('PENDING', 1, 200, symbol)).records
        : (await cryptoOrderApi.list('PENDING', 1, 200, symbol)).records;
      let failed = 0;
      for (const o of pending) {
        try {
          await (isFutures ? futuresApi.cancel(o.orderId) : cryptoOrderApi.cancel(o.orderId));
        } catch { failed++; }
      }
      const done = pending.length - failed;
      if (pending.length === 0) toast(t('toast.noPending'), 'info');
      else if (failed > 0) toast(t('toast.cancelledAllPartial', { ok: done, failed }), 'error');
      else toast(t('toast.cancelledAll', { n: done }), 'success');
      if (done > 0) {
        refresh();
        onCancelled?.(mode);
      }
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.cancelAllFailed'), 'error');
    } finally {
      setCancellingAll(false);
    }
  };

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
        tone: st.tone,
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
        tone: st.tone,
        pending: o.status === 'PENDING',
      };
    });

  /** 详情里挂单的那一单：底下给一颗「撤销订单」 */
  const detailPending = detail?.o.status === 'PENDING';

  return (
    <section className="sec">
      <div className="sec-h">
        <h2 className="mr-auto">
          {t('orders.title')}
          <small>{symbol} · {isFutures ? t('market:coin.futures') : t('market:coin.spot')}</small>
        </h2>
        {/* 眼前有挂着的单才出；两段确认同「一键全平」。电脑上排在最右边；手机上那一排放不下，挪到标题这一行 */}
        {rows.some(r => r.pending) && (
          <SureButton className="linkb order-1 max-sm:order-none text-[13px]" sureClassName="sure" disabled={cancellingAll}
                      sureLabel={t('orders.cancelAllConfirm')} onConfirm={() => void handleCancelAll()}>
            {cancellingAll ? <Loader2 className="ic animate-spin" /> : t('orders.cancelAll')}
          </SureButton>
        )}
        <span className="r">
          <Seg value={filter} onChange={setFilter} options={filters.map(f => ({ value: f.value, label: t(f.labelKey) }))} />
          <button type="button" className="ib" onClick={e => { spinIcon(e); void refresh(); }} aria-label={t('common:refresh')} title={t('common:refresh')}>
            <RefreshCw className="ic" />
          </button>
        </span>
      </div>

      {loading && empty ? (
        <div className="flex flex-col gap-2.5">
          {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-[46px]" />)}
        </div>
      ) : empty ? (
        <EmptyState icon={<Receipt />} text={isFutures ? t('orders.emptyFutures') : t('orders.emptySpot')} />
      ) : (
        <>
          {/* 8 列在手机上挤不下，整表在自己的框里横着滑 */}
          <div className="tw">
            <table className="tbl min-w-[760px]">
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
              <tbody>
                {rows.map(r => (
                  <tr key={r.id}>
                    <td className="mute">{r.time}</td>
                    <td>{r.type}</td>
                    <td className={cn('font-medium', r.side.cls)}>{r.side.label}</td>
                    <td className="r">{r.price}</td>
                    <td className="r">{r.qty}</td>
                    <td className="r">
                      {r.amount}
                      {r.pnl != null && <span className={cn('sub', r.pnl > 0 ? 'up' : 'dn')}>{r.pnl > 0 ? '+' : ''}{fmtNum(r.pnl)}</span>}
                    </td>
                    <td className="r"><span className={cn('tag sm', r.tone)}>{r.status}</span></td>
                    <td className="r">
                      <span className="inline-flex gap-1.5">
                        {r.pending && (
                          <button type="button"
                                  className={cn('btn sm', confirmId === r.id && 'danger', cancellingId === r.id && 'busy')}
                                  onClick={() => {
                                    if (cancellingId === r.id) return;
                                    if (confirmId === r.id) void cancelOrder(mode, r.id);
                                    else setConfirmId(r.id);
                                  }}>
                            {confirmId === r.id ? t('orders.cancelConfirm') : t('orders.cancelShort')}
                          </button>
                        )}
                        <button type="button" className="btn sm" onClick={() => openDetail(r.id)}>{t('orders.detailBtn')}</button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} pages={pages} onChange={setPage} />
        </>
      )}

      {/* 订单详情 */}
      <Dialog open={detail != null} onClose={() => setDetail(null)} className="sm">
        <DialogHeader><h3>{t('orders.detail')}</h3></DialogHeader>
        {detail?.kind === 'futures' && (() => {
          const o = detail.o;
          const sm = sideOf(o.orderSide);
          const st = statusOf(o.status);
          const hasPnl = o.realizedPnl != null && o.realizedPnl !== 0;
          return (
            <div className="kv">
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
              <DRow k={t('col.status')} v={<span className={cn('tag sm', st.tone)}>{st.label}</span>} />
            </div>
          );
        })()}
        {detail?.kind === 'spot' && (() => {
          const o = detail.o;
          const sm = sideOf(o.orderSide);
          const st = statusOf(o.status);
          return (
            <div className="kv">
              <DRow k={t('col.time')} v={fmtDateTime(o.createdAt)} />
              <DRow k={t('col.side')} v={<span className={sm.cls}>{sm.label}</span>} />
              <DRow k={t('col.type')} v={typeOf(o.orderType, o.leverage)} />
              <DRow k={t('col.qty')} v={o.quantity} />
              <DRow k={t('col.orderPrice')} v={o.limitPrice != null ? fmtPrice(o.limitPrice) : '-'} />
              <DRow k={t('col.triggerPrice')} v={o.triggerPrice != null ? fmtPrice(o.triggerPrice) : '-'} />
              <DRow k={t('col.amount')} v={o.filledAmount != null ? fmtNum(o.filledAmount) : '-'} />
              <DRow k={t('col.status')} v={<span className={cn('tag sm', st.tone)}>{st.label}</span>} />
            </div>
          );
        })()}
        {detail && detailPending && (
          <DialogFooter>
            <button type="button" className={cn('btn danger wide', cancellingId === detail.o.orderId && 'busy')}
                    onClick={async () => {
                      if (cancellingId === detail.o.orderId) return;
                      await cancelOrder(detail.kind, detail.o.orderId);
                      setDetail(null);
                    }}>
              {t('orders.cancelOrder')}
            </button>
          </DialogFooter>
        )}
      </Dialog>
    </section>
  );
}
