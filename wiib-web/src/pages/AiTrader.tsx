import { useState, useEffect, useCallback } from 'react';
import { aiTradingApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { cn } from '../lib/utils';
import {
  Bot, TrendingUp, TrendingDown, RefreshCcw, Wallet,
  Activity, BarChart3, MessageSquare, ChevronDown, ChevronUp,
} from 'lucide-react';
import type { AiTradingDashboard, AiTradingDecision, FuturesPosition } from '../types';

const ACTION_STYLE: Record<string, { label: string; color: string; bg: string }> = {
  OPEN_LONG: { label: '开多', color: 'text-green-600', bg: 'bg-green-500/10 border-green-500/30' },
  OPEN_SHORT: { label: '开空', color: 'text-red-600', bg: 'bg-red-500/10 border-red-500/30' },
  CLOSE: { label: '平仓', color: 'text-amber-600', bg: 'bg-amber-500/10 border-amber-500/30' },
  INCREASE: { label: '加仓', color: 'text-blue-600', bg: 'bg-blue-500/10 border-blue-500/30' },
  ADD_MARGIN: { label: '追加保证金', color: 'text-violet-600', bg: 'bg-violet-500/10 border-violet-500/30' },
  HOLD: { label: '观望', color: 'text-muted-foreground', bg: 'bg-muted/50 border-border' },
};

const SYMBOL_LABELS: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH', PAXGUSDT: 'PAXG' };
const SYMBOLS = ['ALL', 'BTCUSDT', 'ETHUSDT', 'PAXGUSDT'] as const;

function fmtMoney(n: number) {
  if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(2) + 'K';
  return n.toFixed(2);
}

function fmtTime(t: string) {
  return new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function StatCard({ label, value, sub, icon: Icon, trend }: {
  label: string; value: string; sub?: string;
  icon: React.ElementType; trend?: 'up' | 'down' | 'neutral';
}) {
  return (
    <div className="p-3 rounded-xl bg-card border space-y-1">
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Icon className="w-3.5 h-3.5" /> {label}
      </div>
      <div className={cn("text-lg font-black tabular-nums",
        trend === 'up' && 'text-green-500',
        trend === 'down' && 'text-red-500',
      )}>{value}</div>
      {sub && <div className="text-[11px] text-muted-foreground">{sub}</div>}
    </div>
  );
}

function PositionCard({ p }: { p: FuturesPosition }) {
  const isLong = p.side === 'LONG';
  const pnlUp = p.unrealizedPnl >= 0;
  return (
    <div className={cn("p-3 rounded-xl border", isLong ? "border-green-500/30 bg-green-500/5" : "border-red-500/30 bg-red-500/5")}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <Badge variant={isLong ? 'default' : 'destructive'} className="text-xs">
            {isLong ? '多' : '空'} {p.leverage}x
          </Badge>
          <span className="text-sm font-bold">{SYMBOL_LABELS[p.symbol] || p.symbol}</span>
        </div>
        <span className={cn("text-sm font-black tabular-nums", pnlUp ? "text-green-500" : "text-red-500")}>
          {pnlUp ? '+' : ''}{p.unrealizedPnl.toFixed(2)}
        </span>
      </div>
      <div className="grid grid-cols-3 gap-2 text-xs text-muted-foreground">
        <div>数量 <span className="text-foreground font-bold">{p.quantity}</span></div>
        <div>入场 <span className="text-foreground font-mono">{p.entryPrice.toLocaleString()}</span></div>
        <div>现价 <span className="text-foreground font-mono">{p.currentPrice.toLocaleString()}</span></div>
        <div>保证金 <span className="text-foreground font-bold">{p.margin.toFixed(2)}</span></div>
        <div>强平 <span className="text-foreground font-mono text-red-500">{p.liquidationPrice.toLocaleString()}</span></div>
        <div>收益率 <span className={cn("font-bold", pnlUp ? "text-green-500" : "text-red-500")}>
          {pnlUp ? '+' : ''}{p.unrealizedPnlPct.toFixed(2)}%
        </span></div>
      </div>
    </div>
  );
}

function DecisionCard({ d }: { d: AiTradingDecision }) {
  const style = ACTION_STYLE[d.action] || ACTION_STYLE.HOLD;
  const [expanded, setExpanded] = useState(false);
  const balanceChange = d.balanceAfter - d.balanceBefore;

  return (
    <div className={cn("p-3 rounded-xl border transition-all", style.bg)}>
      <button onClick={() => setExpanded(!expanded)} className="w-full text-left">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Badge variant="outline" className={cn("text-xs font-black", style.color)}>
              {style.label}
            </Badge>
            <span className="text-xs font-bold">{SYMBOL_LABELS[d.symbol] || d.symbol}</span>
            <span className="text-[10px] text-muted-foreground">{fmtTime(d.createdAt)}</span>
          </div>
          <div className="flex items-center gap-1.5">
            {balanceChange !== 0 && (
              <span className={cn("text-xs font-bold tabular-nums", balanceChange > 0 ? "text-green-500" : "text-red-500")}>
                {balanceChange > 0 ? '+' : ''}{balanceChange.toFixed(2)}
              </span>
            )}
            {expanded ? <ChevronUp className="w-3.5 h-3.5 text-muted-foreground" /> : <ChevronDown className="w-3.5 h-3.5 text-muted-foreground" />}
          </div>
        </div>
        <p className="text-sm mt-1.5 leading-relaxed">{d.reasoning || '无决策理由'}</p>
      </button>
      {expanded && (
        <div className="mt-2 pt-2 border-t border-border/50 text-xs text-muted-foreground space-y-1">
          <div>周期 #{d.cycleNo} | {d.marketContext}</div>
          <div>余额 {d.balanceBefore.toFixed(2)} {'->'} {d.balanceAfter.toFixed(2)}</div>
        </div>
      )}
    </div>
  );
}

export function AiTrader() {
  const { toast } = useToast();
  const [dashboard, setDashboard] = useState<AiTradingDashboard | null>(null);
  const [decisions, setDecisions] = useState<AiTradingDecision[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterSymbol, setFilterSymbol] = useState<string>('ALL');
  const [decisionLimit, setDecisionLimit] = useState(20);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const sym = filterSymbol === 'ALL' ? undefined : filterSymbol;
      const [db, dec] = await Promise.all([
        aiTradingApi.dashboard(),
        aiTradingApi.decisions(sym, decisionLimit),
      ]);
      setDashboard(db);
      setDecisions(dec);
    } catch (e) {
      toast((e as Error).message || '加载失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [filterSymbol, decisionLimit, toast]);

  useEffect(() => { load(); }, [load]);

  // 自动刷新 60s
  useEffect(() => {
    const t = setInterval(load, 60_000);
    return () => clearInterval(t);
  }, [load]);

  const pnlTrend = dashboard ? (dashboard.totalPnl >= 0 ? 'up' : 'down') : 'neutral';

  return (
    <div className="max-w-5xl mx-auto px-4 md:px-6 py-4 space-y-5">

      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-primary/15 flex items-center justify-center">
            <Bot className="w-5 h-5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-extrabold tracking-tight">AI Trader</h1>
            <p className="text-xs text-muted-foreground">自主交易 | 每10分钟决策</p>
          </div>
        </div>
        <Button variant="ghost" size="icon" onClick={() => { load(); toast('已刷新', 'info'); }}>
          <RefreshCcw className="w-4 h-4" />
        </Button>
      </div>

      {/* Stats */}
      {loading && !dashboard ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-20 rounded-xl" />
          ))}
        </div>
      ) : dashboard && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="总盈亏" value={`${dashboard.totalPnl >= 0 ? '+' : ''}$${fmtMoney(dashboard.totalPnl)}`} icon={BarChart3} trend={pnlTrend as 'up'|'down'} />
          <StatCard label="可用余额" value={`$${fmtMoney(dashboard.balance)}`} sub={`冻结 $${fmtMoney(dashboard.frozenBalance)}`} icon={Wallet} />
          <StatCard label="未实现盈亏" value={`${dashboard.unrealizedPnl >= 0 ? '+' : ''}$${fmtMoney(dashboard.unrealizedPnl)}`} icon={Activity} trend={dashboard.unrealizedPnl >= 0 ? 'up' : 'down'} />
          <StatCard label="今日交易" value={`${dashboard.todayTrades} 笔`} sub={`${dashboard.positionCount} 个持仓`} icon={TrendingUp} />
        </div>
      )}

      {/* Positions */}
      {dashboard && dashboard.positions.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-base flex items-center gap-2">
              <Activity className="w-4 h-4" /> 当前持仓
              <Badge variant="outline" className="text-xs ml-auto">{dashboard.positions.length}</Badge>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {dashboard.positions.map(p => <PositionCard key={p.id} p={p} />)}
          </CardContent>
        </Card>
      )}

      {/* Decisions */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base flex items-center gap-2">
            <MessageSquare className="w-4 h-4" /> 决策记录
          </CardTitle>
          <div className="flex gap-1.5 mt-2">
            {SYMBOLS.map(s => (
              <Button
                key={s}
                variant={filterSymbol === s ? 'default' : 'outline'}
                size="sm"
                className="h-7 text-xs px-2.5"
                onClick={() => setFilterSymbol(s)}
              >
                {s === 'ALL' ? '全部' : SYMBOL_LABELS[s] || s}
              </Button>
            ))}
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {loading && decisions.length === 0 ? (
            Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-16 rounded-xl" />)
          ) : decisions.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">暂无决策记录</div>
          ) : (
            <>
              {decisions.map(d => <DecisionCard key={d.id} d={d} />)}
              {decisions.length >= decisionLimit && (
                <Button variant="ghost" className="w-full text-xs" onClick={() => setDecisionLimit(l => l + 20)}>
                  加载更多
                </Button>
              )}
            </>
          )}
        </CardContent>
      </Card>

      {/* PnL Summary */}
      {decisions.length > 0 && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">决策统计</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {Object.entries(
                decisions.reduce<Record<string, number>>((acc, d) => {
                  const key = d.action || 'HOLD';
                  acc[key] = (acc[key] || 0) + 1;
                  return acc;
                }, {})
              ).sort((a, b) => b[1] - a[1]).map(([action, count]) => {
                const style = ACTION_STYLE[action] || ACTION_STYLE.HOLD;
                return (
                  <Badge key={action} variant="outline" className={cn("text-xs", style.color)}>
                    {style.label} {count}
                  </Badge>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
