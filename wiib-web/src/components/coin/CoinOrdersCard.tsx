import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { cryptoOrderApi, futuresApi } from '../../api';
import { useToast } from '../ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Skeleton } from '../ui/skeleton';
import { fmtNum, fmtDateTime } from '../../lib/utils';
import { formatCoinPrice } from '../../lib/coinConfig';
import type { CryptoOrder, FuturesOrder, PageResult } from '../../types';

const ORDER_STATUS_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '结算中', value: 'SETTLING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_ORDER_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_SIDE_MAP: Record<string, { label: string; color: string }> = {
  OPEN_LONG: { label: '多头开仓', color: 'text-green-500' },
  OPEN_SHORT: { label: '空头开仓', color: 'text-red-500' },
  CLOSE_LONG: { label: '多头平仓', color: 'text-red-500' },
  CLOSE_SHORT: { label: '空头平仓', color: 'text-green-500' },
  INCREASE_LONG: { label: '多头加仓', color: 'text-green-500' },
  INCREASE_SHORT: { label: '空头加仓', color: 'text-red-500' },
};

const STATUS_MAP: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning' }> = {
  PENDING: { label: '待成交', variant: 'warning' },
  TRIGGERED: { label: '已触发', variant: 'default' },
  PROCESSING: { label: '处理中', variant: 'warning' },
  SETTLING: { label: '结算中', variant: 'warning' },
  FILLED: { label: '已成交', variant: 'success' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
  EXPIRED: { label: '已过期', variant: 'secondary' },
  LIQUIDATED: { label: '已强平', variant: 'destructive' },
  STOP_LOSS: { label: '已止损', variant: 'warning' },
  TAKE_PROFIT: { label: '已止盈', variant: 'success' },
};

/**
 * 订单卡：按模式显示现货/合约订单表（状态筛选 + 分页 + 撤单）。
 * 父级成交后 bump 对应 refreshKey 触发回到第一页重拉。
 */
export function CoinOrdersCard({ symbol, mode, spotRefreshKey, futuresRefreshKey }: {
  symbol: string;
  mode: 'spot' | 'futures';
  spotRefreshKey: number;
  futuresRefreshKey: number;
}) {
  const { toast } = useToast();
  const fmtPrice = (n?: number | null) => formatCoinPrice(symbol, n);

  // 现货订单
  const [orderFilter, setOrderFilter] = useState('');
  const [orders, setOrders] = useState<CryptoOrder[]>([]);
  const [orderPage, setOrderPage] = useState(1);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderPages, setOrderPages] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);

  // 合约订单
  const [futuresOrderFilter, setFuturesOrderFilter] = useState('');
  const [futuresOrders, setFuturesOrders] = useState<FuturesOrder[]>([]);
  const [futuresOrderPage, setFuturesOrderPage] = useState(1);
  const [futuresOrderTotal, setFuturesOrderTotal] = useState(0);
  const [futuresOrderPages, setFuturesOrderPages] = useState(0);
  const [futuresOrdersLoading, setFuturesOrdersLoading] = useState(false);

  const fetchOrders = useCallback(async (status: string, page: number) => {
    setOrdersLoading(true);
    try {
      const res = await cryptoOrderApi.list(status || undefined, page, 10, symbol) as unknown as PageResult<CryptoOrder>;
      setOrders(res.records);
      setOrderTotal(res.total);
      setOrderPages(res.pages);
    } catch { setOrders([]); }
    finally { setOrdersLoading(false); }
  }, [symbol]);

  const fetchFuturesOrders = useCallback(async (status: string, page: number) => {
    setFuturesOrdersLoading(true);
    try {
      const res = await futuresApi.orders(status || undefined, page, 10, symbol) as unknown as PageResult<FuturesOrder>;
      setFuturesOrders(res.records);
      setFuturesOrderTotal(res.total);
      setFuturesOrderPages(res.pages);
    } catch (e) {
      console.error('查询合约订单失败', e);
      setFuturesOrders([]);
    } finally {
      setFuturesOrdersLoading(false);
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
      toast('已取消', 'success');
      fetchOrders(orderFilter, orderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  const handleFuturesCancel = async (orderId: number) => {
    try {
      await futuresApi.cancel(orderId);
      toast('已取消', 'success');
      fetchFuturesOrders(futuresOrderFilter, futuresOrderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  const isFutures = mode === 'futures';

  return (
    <Card>
      <CardHeader className="pb-2">
        {/* 手机上标题+5个筛选钮一行放不下：允许换行，筛选组自身也可换行 */}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-2">
            {isFutures ? '合约订单' : '订单'}
            <button onClick={() => isFutures ? fetchFuturesOrders(futuresOrderFilter, futuresOrderPage) : fetchOrders(orderFilter, orderPage)} className="text-muted-foreground hover:text-foreground transition-colors" title="刷新">
              <RefreshCw className={`w-3.5 h-3.5 ${(isFutures ? futuresOrdersLoading : ordersLoading) ? 'animate-spin' : ''}`} />
            </button>
          </CardTitle>
          <div className="flex flex-wrap gap-1">
            {isFutures ? (
              FUTURES_ORDER_FILTERS.map(f => (
                <Button key={f.value} variant={futuresOrderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-9 sm:h-7 px-2.5 text-xs" onClick={() => { setFuturesOrderFilter(f.value); setFuturesOrderPage(1); }}>
                  {f.label}
                </Button>
              ))
            ) : (
              ORDER_STATUS_FILTERS.map(f => (
                <Button key={f.value} variant={orderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-9 sm:h-7 px-2.5 text-xs" onClick={() => { setOrderFilter(f.value); setOrderPage(1); }}>
                  {f.label}
                </Button>
              ))
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="p-0">
        {isFutures ? (
          /* 合约订单表 */
          futuresOrdersLoading && futuresOrders.length === 0 ? (
            <div className="p-4"><Skeleton className="w-full h-32" /></div>
          ) : futuresOrders.length === 0 ? (
            <div className="p-8 text-center text-sm text-muted-foreground">暂无合约订单</div>
          ) : (
            <>
              <div className="overflow-x-auto">
                {/* min-w：10 列在手机上不再互相挤压，整表横向滚动 */}
                <table className="w-full min-w-[640px] text-xs">
                  <thead>
                    <tr className="border-b border-border/50 text-muted-foreground">
                      <th className="text-left px-4 py-2.5 font-medium">时间</th>
                      <th className="text-left px-2 py-2.5 font-medium">方向</th>
                      <th className="text-left px-2 py-2.5 font-medium">类型</th>
                      <th className="text-right px-2 py-2.5 font-medium">数量</th>
                      <th className="text-right px-2 py-2.5 font-medium">杠杆</th>
                      <th className="text-right px-2 py-2.5 font-medium">限价</th>
                      <th className="text-right px-2 py-2.5 font-medium">成交价</th>
                      <th className="text-right px-2 py-2.5 font-medium">盈亏</th>
                      <th className="text-center px-2 py-2.5 font-medium">状态</th>
                      <th className="text-center px-4 py-2.5 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {futuresOrders.map(o => {
                      const sm = FUTURES_SIDE_MAP[o.orderSide] || { label: o.orderSide, color: 'text-foreground' };
                      const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                      const hasPnl = o.realizedPnl != null && o.realizedPnl !== 0;
                      return (
                        <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                          <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{fmtDateTime(o.createdAt)}</td>
                          <td className={`px-2 py-2.5 font-medium ${sm.color}`}>{sm.label}</td>
                          <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.leverage}x</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? fmtPrice(o.limitPrice) : '-'}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.filledPrice != null ? fmtPrice(o.filledPrice) : '-'}</td>
                          <td className={`px-2 py-2.5 text-right font-mono ${hasPnl ? (o.realizedPnl! > 0 ? 'text-green-500' : 'text-red-500') : ''}`}>
                            {hasPnl ? `${o.realizedPnl! > 0 ? '+' : ''}${fmtNum(o.realizedPnl!)}` : '-'}
                          </td>
                          <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                          <td className="px-4 py-2.5 text-center">
                            {o.status === 'PENDING' ? (
                              <button onClick={() => handleFuturesCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                            ) : <span className="text-muted-foreground/30">-</span>}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {futuresOrderPages > 1 && (
                <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                  <span className="text-xs text-muted-foreground">共 {futuresOrderTotal} 条</span>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage <= 1} onClick={() => setFuturesOrderPage(p => p - 1)}>
                      <ChevronLeft className="w-3.5 h-3.5" />
                    </Button>
                    <span className="text-xs px-2">{futuresOrderPage} / {futuresOrderPages}</span>
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage >= futuresOrderPages} onClick={() => setFuturesOrderPage(p => p + 1)}>
                      <ChevronRight className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </div>
              )}
            </>
          )
        ) : (
          /* 现货订单表 */
          ordersLoading && orders.length === 0 ? (
            <div className="p-4"><Skeleton className="w-full h-32" /></div>
          ) : orders.length === 0 ? (
            <div className="p-8 text-center text-sm text-muted-foreground">暂无订单</div>
          ) : (
            <>
              <div className="overflow-x-auto">
                {/* min-w：9 列在手机上不再互相挤压，整表横向滚动 */}
                <table className="w-full min-w-[560px] text-xs">
                  <thead>
                    <tr className="border-b border-border/50 text-muted-foreground">
                      <th className="text-left px-4 py-2.5 font-medium">时间</th>
                      <th className="text-left px-2 py-2.5 font-medium">方向</th>
                      <th className="text-left px-2 py-2.5 font-medium">类型</th>
                      <th className="text-right px-2 py-2.5 font-medium">数量</th>
                      <th className="text-right px-2 py-2.5 font-medium">挂单价</th>
                      <th className="text-right px-2 py-2.5 font-medium">触发价</th>
                      <th className="text-right px-2 py-2.5 font-medium">金额</th>
                      <th className="text-center px-2 py-2.5 font-medium">状态</th>
                      <th className="text-center px-4 py-2.5 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map(o => {
                      const isBuy = o.orderSide === 'BUY';
                      const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                      return (
                        <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                          <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{fmtDateTime(o.createdAt)}</td>
                          <td className={`px-2 py-2.5 font-medium ${isBuy ? 'text-green-500' : 'text-red-500'}`}>{isBuy ? '买入' : '卖出'}</td>
                          <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}{o.leverage > 1 ? ` ${o.leverage}x` : ''}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? fmtPrice(o.limitPrice) : '-'}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.triggerPrice != null ? fmtPrice(o.triggerPrice) : '-'}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{o.filledAmount != null ? fmtNum(o.filledAmount) : '-'}</td>
                          <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                          <td className="px-4 py-2.5 text-center">
                            {o.status === 'PENDING' ? (
                              <button onClick={() => handleCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                            ) : <span className="text-muted-foreground/30">-</span>}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {orderPages > 1 && (
                <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                  <span className="text-xs text-muted-foreground">共 {orderTotal} 条</span>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage <= 1} onClick={() => setOrderPage(p => p - 1)}>
                      <ChevronLeft className="w-3.5 h-3.5" />
                    </Button>
                    <span className="text-xs px-2">{orderPage} / {orderPages}</span>
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage >= orderPages} onClick={() => setOrderPage(p => p + 1)}>
                      <ChevronRight className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </div>
              )}
            </>
          )
        )}
      </CardContent>
    </Card>
  );
}
