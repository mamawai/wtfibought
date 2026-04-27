import { Activity, Bot } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';

export interface TradeItem {
  id: string;
  orderSide: string;
  sideLabel?: string;
  sideTone?: 'buy' | 'sell';
  name: string;
  quantity: number | string;
  unit: string;
  filledAmount?: number;
  createdAt: string;
  isAi?: boolean;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

function formatTime(dateStr: string) {
  const d = new Date(dateStr);
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function formatAmount(n?: number) {
  if (!n) return '-';
  return n >= 10000 ? `${(n / 10000).toFixed(2)}万` : n.toFixed(2);
}

export function LatestTradesCard({ trades, loading }: Props) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 text-sm">
          <div className="p-1 rounded-md bg-primary/10">
            <Activity className="w-3.5 h-3.5 text-primary" />
          </div>
          最新成交
        </CardTitle>
      </CardHeader>
      <CardContent className="p-0">
        {loading ? (
          <div className="space-y-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex justify-between items-center p-3 border-b border-border/20 last:border-b-0">
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-4 w-16" />
              </div>
            ))}
          </div>
        ) : trades.length > 0 ? (
          <div className="max-h-80 overflow-y-auto">
            {trades.map((t) => {
              const tone = t.sideTone ?? (t.orderSide === 'BUY' ? 'buy' : 'sell');
              const sideLabel = t.sideLabel ?? (tone === 'buy' ? '买' : '卖');
              return (
                <div key={t.id} className="flex items-center justify-between px-4 py-2.5 border-b border-border/20 last:border-b-0 text-sm">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                      {sideLabel}
                    </span>
                    {t.isAi && (
                      <span title="AI交易员" className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 shrink-0">
                        <Bot className="w-3 h-3" />
                      </span>
                    )}
                    <span className="font-medium truncate">{t.name}</span>
                    <span className="text-muted-foreground text-xs">{t.quantity}{t.unit}</span>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    <span className="text-muted-foreground text-xs">{formatAmount(t.filledAmount)}</span>
                    <span className="text-muted-foreground text-xs w-14 text-right">{formatTime(t.createdAt)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="p-8 text-center text-muted-foreground">暂无成交</div>
        )}
      </CardContent>
    </Card>
  );
}
