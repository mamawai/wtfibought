import { useState, useEffect, useCallback } from 'react';
import { aiTradingApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { cn } from '../lib/utils';
import {
  Bot, TrendingUp, TrendingDown, RefreshCcw, Wallet,
  Activity, BarChart3, ChevronDown, ChevronUp,
  Zap, Shield, Clock, ArrowUpRight,
} from 'lucide-react';
import { AiPnlChart } from '../components/AiPnlChart';
import type { AiTradingDashboard, AiTradingDecision, FuturesPosition } from '../types';

const ACTION_CFG: Record<string, { label: string; icon: typeof Zap; color: string; bg: string; ring: string }> = {
  OPEN_LONG:  { label: '开多', icon: TrendingUp,   color: 'text-gain',   bg: 'bg-gain/8',  ring: 'ring-gain/30' },
  OPEN_SHORT: { label: '开空', icon: TrendingDown,  color: 'text-loss',   bg: 'bg-loss/8',  ring: 'ring-loss/30' },
  CLOSE:      { label: '平仓', icon: Zap,           color: 'text-warning', bg: 'bg-warning/8', ring: 'ring-warning/30' },
  TIGHTEN_SL: { label: '收紧SL', icon: Shield,      color: 'text-primary', bg: 'bg-primary/8', ring: 'ring-primary/30' },
  EXIT_PARTIAL_PROTECT: { label: '保护平半', icon: Shield, color: 'text-warning', bg: 'bg-warning/8', ring: 'ring-warning/30' },
  EXIT_FULL_RISK: { label: '风险全平', icon: Zap,    color: 'text-loss',   bg: 'bg-loss/8', ring: 'ring-loss/30' },
  TIME_EXIT:  { label: '超时退出', icon: Clock,      color: 'text-warning', bg: 'bg-warning/8', ring: 'ring-warning/30' },
  INCREASE:   { label: '加仓', icon: ArrowUpRight,  color: 'text-primary', bg: 'bg-primary/8', ring: 'ring-primary/30' },
  ADD_MARGIN: { label: '追保', icon: Shield,         color: 'text-[#8b5cf6]', bg: 'bg-[#8b5cf6]/8', ring: 'ring-[#8b5cf6]/30' },
  HOLD:       { label: '观望', icon: Clock,          color: 'text-muted-foreground', bg: 'bg-muted/30', ring: 'ring-border' },
};

const SYMBOL_MAP: Record<string, { label: string; emoji: string }> = {
  BTCUSDT:  { label: 'BTC',  emoji: '₿' },
  ETHUSDT:  { label: 'ETH',  emoji: 'Ξ' },
  PAXGUSDT: { label: 'PAXG', emoji: '🥇' },
};
const SYMBOLS = ['ALL', 'BTCUSDT', 'ETHUSDT', 'PAXGUSDT'] as const;

function fmt$(n?: number | null, compact = false) {
  if (n == null || !Number.isFinite(n)) return '-';
  if (compact) {
    if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(2) + 'M';
    if (Math.abs(n) >= 1e4) return (n / 1e3).toFixed(1) + 'K';
  }
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function fmtTime(t: string) {
  return new Date(t).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

function fmtPct(n: number) {
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%';
}

function futuresMmrByLeverage(leverage?: number | null): number {
  const lv = leverage != null && leverage > 0 ? leverage : 1;
  if (lv >= 200) return 0.001;
  if (lv >= 126) return 0.0025;
  return 0.005;
}

function fmtRate(rate: number) {
  return `${(rate * 100).toFixed(2)}%`;
}

/* ========== Stat Card ========== */
function StatCard({ label, value, sub, icon: Icon, trend }: {
  label: string; value: string; sub?: string;
  icon: React.ElementType; trend?: 'up' | 'down' | 'neutral';
}) {
  return (
    <div className="neu-raised-sm rounded-xl p-4 flex flex-col gap-1.5">
      <div className="flex items-center gap-2 text-xs text-muted-foreground font-medium">
        <div className={cn(
          "w-7 h-7 rounded-lg flex items-center justify-center",
          trend === 'up' && "bg-gain/10 text-gain",
          trend === 'down' && "bg-loss/10 text-loss",
          !trend || trend === 'neutral' ? "bg-primary/10 text-primary" : "",
        )}>
          <Icon className="w-3.5 h-3.5" />
        </div>
        {label}
      </div>
      <div className={cn(
        "text-xl font-black tabular-nums tracking-tight",
        trend === 'up' && 'text-gain',
        trend === 'down' && 'text-loss',
      )}>{value}</div>
      {sub && <div className="text-[11px] text-muted-foreground leading-tight">{sub}</div>}
    </div>
  );
}

/* ========== Position Card ========== */
function PositionCard({ p }: { p: FuturesPosition }) {
  const isLong = p.side === 'LONG';
  const pnlUp = p.unrealizedPnl >= 0;
  const sym = SYMBOL_MAP[p.symbol] || { label: p.symbol, emoji: '' };

  return (
    <div className={cn(
      "neu-raised-sm rounded-xl p-4 transition-all",
      isLong ? "border-l-3 border-l-gain" : "border-l-3 border-l-loss",
    )}>
      {/* header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2.5">
          <div className={cn(
            "w-9 h-9 rounded-lg flex items-center justify-center text-sm font-black",
            isLong ? "bg-gain/10 text-gain" : "bg-loss/10 text-loss",
          )}>
            {sym.emoji || sym.label[0]}
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="text-sm font-bold">{sym.label}</span>
              <span className={cn(
                "text-[10px] font-black px-1.5 py-0.5 rounded",
                isLong ? "bg-gain/15 text-gain" : "bg-loss/15 text-loss",
              )}>
                {isLong ? 'LONG' : 'SHORT'} {p.leverage}x
              </span>
            </div>
            <div className="text-[11px] text-muted-foreground mt-0.5">
              {p.quantity} @ {fmt$(p.entryPrice)}
            </div>
          </div>
        </div>
        <div className="text-right">
          <div className={cn("text-lg font-black tabular-nums", pnlUp ? "text-gain" : "text-loss")}>
            {pnlUp ? '+' : ''}{fmt$(p.unrealizedPnl)}
          </div>
          <div className={cn("text-xs font-bold tabular-nums", pnlUp ? "text-gain" : "text-loss")}>
            {fmtPct(p.unrealizedPnlPct)}
          </div>
        </div>
      </div>

      {/* detail grid */}
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-2">
        {[
          { label: '现价', value: fmt$(p.currentPrice) },
          { label: '保证金', value: fmt$(p.margin) },
          { label: '强平价', value: fmt$(p.liquidationPrice), warn: true },
          { label: 'MMR', value: fmtRate(futuresMmrByLeverage(p.leverage)) },
          { label: '仓位价值', value: fmt$(p.positionValue, true) },
        ].map(item => (
          <div key={item.label} className="neu-inset rounded-lg px-2 py-1.5 text-center">
            <div className="text-[10px] text-muted-foreground">{item.label}</div>
            <div className={cn(
              "text-xs font-bold tabular-nums mt-0.5",
              item.warn && "text-loss",
            )}>{item.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ========== Decision Card ========== */
function DecisionCard({ d }: { d: AiTradingDecision }) {
  const cfg = ACTION_CFG[d.action] || ACTION_CFG.HOLD;
  const ActionIcon = cfg.icon;
  const [expanded, setExpanded] = useState(false);
  const balanceChange = d.balanceAfter - d.balanceBefore;
  const sym = SYMBOL_MAP[d.symbol] || { label: d.symbol, emoji: '' };

  return (
    <div className={cn("neu-flat rounded-xl overflow-hidden transition-all")}>
      <button onClick={() => setExpanded(!expanded)} className="w-full text-left p-3.5">
        <div className="flex items-center gap-3">
          {/* action icon */}
          <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center ring-1", cfg.bg, cfg.ring)}>
            <ActionIcon className={cn("w-4 h-4", cfg.color)} />
          </div>

          {/* content */}
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <span className={cn("text-xs font-black", cfg.color)}>{cfg.label}</span>
              <span className="text-xs font-bold">{sym.label}</span>
              <span className="text-[10px] text-muted-foreground ml-auto flex-shrink-0">{fmtTime(d.createdAt)}</span>
            </div>
            <p className="text-[13px] mt-1 leading-relaxed text-foreground/80 line-clamp-2">
              {d.reasoning || '无决策理由'}
            </p>
          </div>

          {/* balance change + expand */}
          <div className="flex items-center gap-2 flex-shrink-0">
            {balanceChange !== 0 && (
              <span className={cn(
                "text-xs font-black tabular-nums",
                balanceChange > 0 ? "text-gain" : "text-loss",
              )}>
                {balanceChange > 0 ? '+' : ''}{fmt$(balanceChange)}
              </span>
            )}
            {expanded
              ? <ChevronUp className="w-3.5 h-3.5 text-muted-foreground" />
              : <ChevronDown className="w-3.5 h-3.5 text-muted-foreground" />}
          </div>
        </div>
      </button>

      {expanded && (
        <div className="px-3.5 pb-3.5 pt-0">
          <div className="neu-inset rounded-lg p-3 text-xs text-muted-foreground space-y-1">
            <div className="flex justify-between">
              <span>周期 #{d.cycleNo}</span>
              <span>{d.marketContext}</span>
            </div>
            <div className="flex justify-between">
              <span>余额变化</span>
              <span className="font-mono">{fmt$(d.balanceBefore)} → {fmt$(d.balanceAfter)}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/* ========== Action Stats Bar ========== */
function ActionStats({ decisions }: { decisions: AiTradingDecision[] }) {
  const counts = decisions.reduce<Record<string, number>>((acc, d) => {
    const key = d.action || 'HOLD';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  const total = decisions.length || 1;

  return (
    <div className="flex gap-1.5 flex-wrap">
      {Object.entries(counts)
        .sort((a, b) => b[1] - a[1])
        .map(([action, count]) => {
          const cfg = ACTION_CFG[action] || ACTION_CFG.HOLD;
          const pct = ((count / total) * 100).toFixed(0);
          return (
            <div key={action} className={cn(
              "flex items-center gap-1.5 text-[11px] font-bold px-2.5 py-1 rounded-lg ring-1",
              cfg.bg, cfg.ring, cfg.color,
            )}>
              {cfg.label} {count} <span className="text-muted-foreground font-normal">({pct}%)</span>
            </div>
          );
        })}
    </div>
  );
}

/* ========== Main ========== */
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

  useEffect(() => {
    const t = setInterval(load, 600_000);
    return () => clearInterval(t);
  }, [load]);

  const INITIAL_BALANCE = 100000;
  const pnlTrend = dashboard ? (dashboard.totalPnl >= 0 ? 'up' : 'down') : 'neutral';
  const totalAsset = dashboard ? dashboard.totalPnl + INITIAL_BALANCE : 0;
  const totalPositionValue = dashboard ? dashboard.positions.reduce((s, p) => s + p.positionValue, 0) : 0;

  return (
    <div className="max-w-5xl mx-auto px-4 md:px-6 py-5 space-y-5">

      {/* ===== Header ===== */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-xl neu-raised-sm flex items-center justify-center bg-primary/10">
            <Bot className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">AI Trader</h1>
            <p className="text-[11px] text-muted-foreground">
              自主合约交易 · 10min/cycle
              {dashboard && <span className="ml-2 text-primary font-bold">{dashboard.positionCount} 持仓</span>}
            </p>
          </div>
        </div>
        <button
          onClick={() => { load(); toast('已刷新', 'info'); }}
          className="neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary transition-colors"
        >
          <RefreshCcw className={cn("w-4 h-4", loading && "animate-spin")} />
        </button>
      </div>

      {/* ===== Stats Grid ===== */}
      {loading && !dashboard ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="neu-raised-sm rounded-xl h-24 animate-pulse bg-muted/30" />
          ))}
        </div>
      ) : dashboard && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard
            label="总资产"
            value={`$${fmt$(totalAsset, true)}`}
            sub={`收益率 ${fmtPct((dashboard.totalPnl / INITIAL_BALANCE) * 100)}`}
            icon={Wallet}
          />
          <StatCard
            label="总盈亏"
            value={`${dashboard.totalPnl >= 0 ? '+' : ''}$${fmt$(dashboard.totalPnl, true)}`}
            sub={`未实现 ${dashboard.unrealizedPnl >= 0 ? '+' : ''}$${fmt$(dashboard.unrealizedPnl)}`}
            icon={BarChart3}
            trend={pnlTrend}
          />
          <StatCard
            label="可用余额"
            value={`$${fmt$(dashboard.balance, true)}`}
            sub={`冻结 $${fmt$(dashboard.frozenBalance, true)}`}
            icon={Activity}
          />
          <StatCard
            label="仓位总价值"
            value={`$${fmt$(totalPositionValue, true)}`}
            sub={`${dashboard.positionCount} 仓 · 今日 ${dashboard.todayTrades} 笔`}
            icon={TrendingUp}
          />
        </div>
      )}

      {/* ===== PnL Chart ===== */}
      {decisions.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 px-1">
            <BarChart3 className="w-4 h-4 text-primary" />
            <span className="text-sm font-bold">收益走势</span>
            <span className="text-[11px] text-muted-foreground">每周期余额快照</span>
          </div>
          <div className="neu-raised-sm rounded-xl p-3">
            <AiPnlChart decisions={decisions} />
          </div>
        </div>
      )}

      {/* ===== Positions ===== */}
      {dashboard && dashboard.positions.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-center gap-2 px-1">
            <Activity className="w-4 h-4 text-primary" />
            <span className="text-sm font-bold">当前持仓</span>
            <span className="text-[11px] text-muted-foreground neu-inset px-2 py-0.5 rounded-md font-bold">
              {dashboard.positions.length}
            </span>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            {dashboard.positions.map(p => <PositionCard key={p.id} p={p} />)}
          </div>
        </div>
      )}

      {/* ===== Decisions ===== */}
      <div className="space-y-3">
        <div className="flex items-center justify-between px-1">
          <div className="flex items-center gap-2">
            <Zap className="w-4 h-4 text-primary" />
            <span className="text-sm font-bold">决策记录</span>
          </div>
          {/* symbol filter */}
          <div className="flex gap-1">
            {SYMBOLS.map(s => {
              const active = filterSymbol === s;
              const sym = SYMBOL_MAP[s as string];
              return (
                <button
                  key={s}
                  onClick={() => setFilterSymbol(s)}
                  className={cn(
                    "text-[11px] font-bold px-2.5 py-1 rounded-lg transition-all",
                    active
                      ? "neu-inset text-primary"
                      : "neu-flat text-muted-foreground hover:text-foreground",
                  )}
                >
                  {s === 'ALL' ? '全部' : sym?.label || s}
                </button>
              );
            })}
          </div>
        </div>

        {/* action stats */}
        {decisions.length > 0 && <ActionStats decisions={decisions} />}

        {/* decision list */}
        <div className="space-y-2">
          {loading && decisions.length === 0 ? (
            Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="neu-flat rounded-xl h-16 animate-pulse bg-muted/20" />
            ))
          ) : decisions.length === 0 ? (
            <div className="neu-inset rounded-xl py-12 text-center text-sm text-muted-foreground">
              暂无决策记录
            </div>
          ) : (
            <>
              {decisions.map(d => <DecisionCard key={d.id} d={d} />)}
              {decisions.length >= decisionLimit && (
                <button
                  onClick={() => setDecisionLimit(l => l + 20)}
                  className="neu-btn-sm w-full py-2.5 rounded-xl text-xs font-bold text-muted-foreground hover:text-primary transition-colors"
                >
                  加载更多
                </button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
