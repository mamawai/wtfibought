import { useState, useEffect } from 'react';
import { futuresApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { cn } from '../lib/utils';
import { Flame, RefreshCw } from 'lucide-react';
import type { ForceOrder } from '../types';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'PAXGUSDT'];

export function ForceOrders() {
  const [symbol, setSymbol] = useState('BTCUSDT');
  const [orders, setOrders] = useState<ForceOrder[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchOrders = () => {
    setLoading(true);
    futuresApi.forceOrders(symbol, 100)
      .then(setOrders)
      .catch(() => setOrders([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchOrders(); }, [symbol]);

  const formatTime = (ts: string) => {
    const d = new Date(ts);
    return d.toLocaleString('zh-CN', { hour12: false });
  };

  const formatPrice = (p: number) => p.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const formatAmount = (p: number) => p.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 });
  const formatQty = (p: number) => p.toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 });

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <div className="p-1.5 rounded-lg bg-red-500/10">
              <Flame className="w-5 h-5 text-red-500" />
            </div>
            爆仓单查看
          </CardTitle>
          <div className="flex items-center gap-3">
            <div className="flex gap-1">
              {SYMBOLS.map(s => (
                <button key={s} onClick={() => setSymbol(s)}
                  className={cn('px-3 py-1 rounded-lg text-xs font-bold transition-colors',
                    symbol === s ? 'bg-foreground text-background' : 'bg-surface text-foreground hover:bg-surface-hover')}>
                  {s.replace('USDT', '')}
                </button>
              ))}
            </div>
            <button onClick={fetchOrders} className="p-1.5 rounded-lg bg-surface hover:bg-surface-hover transition-colors">
              <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
            </button>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-2">
              {[...Array(10)].map((_, i) => <Skeleton key={i} className="h-12 w-full" />)}
            </div>
          ) : orders.length === 0 ? (
            <div className="py-12 text-center text-muted-foreground">暂无爆仓记录</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="px-4 py-3 text-left font-bold text-muted-foreground">时间</th>
                    <th className="px-4 py-3 text-center font-bold text-muted-foreground">类型</th>
                    <th className="px-4 py-3 text-right font-bold text-muted-foreground">价格</th>
                    <th className="px-4 py-3 text-right font-bold text-muted-foreground">数量</th>
                    <th className="px-4 py-3 text-right font-bold text-muted-foreground">金额</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map(o => (
                    <tr key={o.id} className="border-b border-border/50 hover:bg-surface-hover/50 transition-colors">
                      <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{formatTime(o.tradeTime)}</td>
                      <td className="px-4 py-3 text-center">
                        <Badge className={cn('font-black text-xs',
                          o.side === 'SELL' ? 'bg-red-500/10 text-red-500 border-red-500/20' : 'bg-green-500/10 text-green-500 border-green-500/20')}>
                          {o.side === 'SELL' ? '多头爆仓' : '空头爆仓'}
                        </Badge>
                      </td>
                      <td className="px-4 py-3 text-right font-mono font-bold">{formatPrice(o.price)}</td>
                      <td className="px-4 py-3 text-right font-mono">{formatQty(o.quantity)}</td>
                      <td className="px-4 py-3 text-right font-mono font-bold text-loss">${formatAmount(o.amount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
