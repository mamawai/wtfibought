import { useEffect, useMemo, useState } from 'react';
import { futuresApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { cn } from '../lib/utils';
import { AlertTriangle, ChevronLeft, ChevronRight, Flame, RefreshCw } from 'lucide-react';
import type { ForceOrder, PageResult } from '../types';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'PAXGUSDT'] as const;
const PAGE_SIZE = 20;

function formatDateTime(ts: string): string {
  const d = new Date(ts);
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

function formatPrice(value: number): string {
  return value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatQty(value: number): string {
  return value.toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
}

function formatAmount(value: number): string {
  return value.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 });
}

function sideLabel(side: string): string {
  return side === 'SELL' ? '多头爆仓' : '空头爆仓';
}

export function ForceOrders() {
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<PageResult<ForceOrder>>({
    records: [],
    total: 0,
    size: PAGE_SIZE,
    current: 1,
    pages: 0,
  });
  const [loading, setLoading] = useState(true);

  const fetchOrders = (targetPage = page) => {
    setLoading(true);
    futuresApi.forceOrders(symbol, targetPage, PAGE_SIZE)
      .then(res => setResult(res))
      .catch(() => setResult({
        records: [],
        total: 0,
        size: PAGE_SIZE,
        current: targetPage,
        pages: 0,
      }))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchOrders(page);
  }, [symbol, page]);

  const records = result.records;

  const stats = useMemo(() => {
    const longLiquidations = records.filter(o => o.side === 'SELL').length;
    const shortLiquidations = records.filter(o => o.side !== 'SELL').length;
    const totalAmount = records.reduce((sum, o) => sum + o.amount, 0);
    return { longLiquidations, shortLiquidations, totalAmount };
  }, [records]);

  return (
    <div className="max-w-6xl mx-auto p-4 md:p-6 space-y-5">
      <Card className="relative overflow-hidden">
        <div className="absolute -top-16 -right-12 w-48 h-48 rounded-full bg-red-500/10 blur-3xl" />
        <CardHeader className="relative space-y-4">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div className="space-y-3">
              <CardTitle className="flex items-center gap-3 text-xl md:text-2xl font-black tracking-tight">
                <span className="p-2 rounded-2xl bg-red-500/10 text-red-500">
                  <Flame className="w-5 h-5" />
                </span>
                爆仓记录
              </CardTitle>
              <div className="max-w-3xl text-sm leading-6 text-muted-foreground">
                这里展示 Binance 合约市场的最近爆仓记录。`SELL` 代表多头被强平，`BUY` 代表空头被强平。大额连续爆仓通常意味着短时波动和情绪放大，但它不是独立交易信号，最好结合价格、持仓和资金费率一起看。
              </div>
            </div>
            <Button variant="outline" size="sm" className="h-9 w-fit gap-2" onClick={() => fetchOrders(page)}>
              <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
              刷新
            </Button>
          </div>

          <div className="flex flex-wrap gap-2">
            {SYMBOLS.map(item => (
              <button
                key={item}
                onClick={() => {
                  setSymbol(item);
                  setPage(1);
                }}
                className={cn(
                  'px-3.5 py-2 rounded-xl text-xs font-black border transition-colors',
                  symbol === item
                    ? 'bg-foreground text-background border-foreground'
                    : 'bg-card neu-raised text-foreground border-border/60 hover:bg-surface-hover',
                )}
              >
                {item.replace('USDT', '')}
              </button>
            ))}
          </div>
        </CardHeader>
      </Card>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">当前页多头爆仓</div>
            <div className="text-2xl font-black text-red-500 tabular-nums">{stats.longLiquidations}</div>
            <div className="text-xs text-muted-foreground">对应 `SELL` 强平单</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">当前页空头爆仓</div>
            <div className="text-2xl font-black text-green-500 tabular-nums">{stats.shortLiquidations}</div>
            <div className="text-xs text-muted-foreground">对应 `BUY` 强平单</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">当前页总名义金额</div>
            <div className="text-2xl font-black tabular-nums">${formatAmount(stats.totalAmount)}</div>
            <div className="text-xs text-muted-foreground">仅统计当前页 {records.length} 条记录</div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
            <div className="space-y-1">
              <CardTitle className="text-base font-black">{symbol} 强平列表</CardTitle>
              <div className="text-xs text-muted-foreground">
                按成交时间倒序展示，每页 {PAGE_SIZE} 条，当前共 {result.total} 条记录。
              </div>
            </div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <AlertTriangle className="w-3.5 h-3.5 text-red-500" />
              爆仓金额大并不等于马上反转，只说明该方向刚经历了强制出清。
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-3">
              {[...Array(8)].map((_, idx) => <Skeleton key={idx} className="h-16 w-full rounded-xl" />)}
            </div>
          ) : records.length === 0 ? (
            <div className="py-16 text-center text-sm text-muted-foreground">暂无爆仓记录</div>
          ) : (
            <>
              <div className="hidden md:block overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border/50 text-muted-foreground">
                      <th className="px-5 py-3 text-left font-bold">时间</th>
                      <th className="px-4 py-3 text-left font-bold">方向</th>
                      <th className="px-4 py-3 text-right font-bold">成交价</th>
                      <th className="px-4 py-3 text-right font-bold">均价</th>
                      <th className="px-4 py-3 text-right font-bold">数量</th>
                      <th className="px-5 py-3 text-right font-bold">名义金额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.map(order => (
                      <tr key={order.id} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                        <td className="px-5 py-3 text-xs font-mono text-muted-foreground whitespace-nowrap">{formatDateTime(order.tradeTime)}</td>
                        <td className="px-4 py-3">
                          <Badge
                            className={cn(
                              'font-black text-xs',
                              order.side === 'SELL'
                                ? 'bg-red-500/10 text-red-500 border-red-500/20'
                                : 'bg-green-500/10 text-green-500 border-green-500/20',
                            )}
                          >
                            {sideLabel(order.side)}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-right font-mono font-bold">{formatPrice(order.price)}</td>
                        <td className="px-4 py-3 text-right font-mono text-muted-foreground">{formatPrice(order.avgPrice)}</td>
                        <td className="px-4 py-3 text-right font-mono">{formatQty(order.quantity)}</td>
                        <td className="px-5 py-3 text-right font-mono font-bold">${formatAmount(order.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="md:hidden p-3 space-y-3">
                {records.map(order => (
                  <div key={order.id} className="rounded-2xl border border-border/50 bg-card p-4 space-y-3 neu-raised-sm">
                    <div className="flex items-start justify-between gap-3">
                      <div className="space-y-1">
                        <div className="text-xs text-muted-foreground font-mono">{formatDateTime(order.tradeTime)}</div>
                        <div className="text-sm font-black">{symbol.replace('USDT', '')}</div>
                      </div>
                      <Badge
                        className={cn(
                          'font-black text-xs',
                          order.side === 'SELL'
                            ? 'bg-red-500/10 text-red-500 border-red-500/20'
                            : 'bg-green-500/10 text-green-500 border-green-500/20',
                        )}
                      >
                        {sideLabel(order.side)}
                      </Badge>
                    </div>
                    <div className="grid grid-cols-2 gap-3 text-xs">
                      <div className="space-y-1">
                        <div className="text-muted-foreground">成交价</div>
                        <div className="font-mono font-bold">{formatPrice(order.price)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">均价</div>
                        <div className="font-mono">{formatPrice(order.avgPrice)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">数量</div>
                        <div className="font-mono">{formatQty(order.quantity)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">名义金额</div>
                        <div className="font-mono font-bold">${formatAmount(order.amount)}</div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                <span className="text-xs text-muted-foreground">
                  第 {result.current} / {Math.max(result.pages, 1)} 页
                </span>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 w-8 p-0"
                    disabled={page <= 1}
                    onClick={() => setPage(prev => prev - 1)}
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 w-8 p-0"
                    disabled={page >= result.pages}
                    onClick={() => setPage(prev => prev + 1)}
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
