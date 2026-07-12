import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity, ArrowDownRight, ArrowUpRight, Bot, ChevronDown, ChevronUp, Crosshair,
  History, Loader2, PowerOff, RefreshCcw, TrendingUp, Wallet, X,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { strategyAccountApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useToast } from '../components/ui/use-toast';
import { EquityChart } from '../components/EquityChart';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import type { FuturesPosition, StrategyAccountView, StrategyClosedPosition } from '../types';
import type { TnEquityPoint } from '../types/testnet';

const ADMIN_USER_ID = 1;
const REFRESH_MS = 60_000;

/** 策略展示名与一句话说明（id 来自 TradingStrategySpi.id()）+ 专属点缀色（图标底/识别用，不入图表） */
const STRATEGY_META: Record<string, { name: string; desc: string; accent: string }> = {
  FIBO: { name: 'Fibo 回撤', desc: '斐波那契回撤挂单', accent: '#F97316' },
  LIQFADE: { name: 'Liq Fade', desc: '清算级联反向', accent: '#3b82f6' },
  SQZMOM: { name: 'Sqz Momentum', desc: '挤压动量突破', accent: '#a855f7' },
};


function PnlText({ value, className }: { value: number | null | undefined; className?: string }) {
  if (value == null) return <span className={className}>-</span>;
  return (
    <span className={cn(value >= 0 ? 'text-gain' : 'text-loss', className)}>
      {value >= 0 ? '+' : ''}{fmtNum(value)}
    </span>
  );
}

/** 持仓卡：策略当前敞口 + SL/TP + 浮盈；管理员额外渲染平仓按钮（点击即市价平，无二次确认）。 */
function PositionCard({ pos, canClose, closing, onClose }: {
  pos: FuturesPosition;
  canClose: boolean;
  closing: boolean;
  onClose: (pos: FuturesPosition) => void;
}) {
  const isLong = pos.side === 'LONG';
  const sl = pos.stopLosses?.[0]?.price;
  const tp = pos.takeProfits?.[0]?.price;
  return (
    <div className="rounded-lg neu-flat p-3 space-y-2">
      <div className="flex items-center gap-2">
        {isLong
          ? <ArrowUpRight className="w-3.5 h-3.5 text-gain shrink-0" />
          : <ArrowDownRight className="w-3.5 h-3.5 text-loss shrink-0" />}
        <span className="text-xs font-black">{pos.symbol}</span>
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
          {isLong ? '多' : '空'} {pos.leverage}x
        </span>
        <span className="ml-auto text-[10px] text-muted-foreground">{fmtDateTime(pos.createdAt)}</span>
      </div>
      <div className="grid grid-cols-3 gap-1.5 text-[11px]">
        <span className="text-muted-foreground">数量 <span className="font-bold text-foreground tabular-nums">{pos.quantity}</span></span>
        <span className="text-muted-foreground">开仓 <span className="font-bold text-foreground tabular-nums">{fmtNum(pos.entryPrice)}</span></span>
        <span className="text-muted-foreground">标记 <span className="font-bold text-foreground tabular-nums">{fmtNum(pos.markPrice)}</span></span>
        <span className="text-muted-foreground">保证金 <span className="font-bold text-foreground tabular-nums">{fmtNum(pos.margin)}</span></span>
        {sl != null && <span className="text-muted-foreground">SL <span className="font-bold text-loss tabular-nums">{fmtNum(sl)}</span></span>}
        {tp != null && <span className="text-muted-foreground">TP <span className="font-bold text-gain tabular-nums">{fmtNum(tp)}</span></span>}
      </div>
      <div className="flex items-center justify-between">
        <span className="text-xs">
          浮动盈亏 <PnlText value={pos.unrealizedPnl} className="font-black tabular-nums" />
          {pos.unrealizedPnlPct != null && (
            <span className={cn('text-[10px] ml-1', pos.unrealizedPnl >= 0 ? 'text-gain' : 'text-loss')}>
              ({pos.unrealizedPnl >= 0 ? '+' : ''}{pos.unrealizedPnlPct.toFixed(1)}%)
            </span>
          )}
        </span>
        {canClose && (
          <button
            disabled={closing}
            onClick={() => onClose(pos)}
            className="neu-btn-sm px-2.5 py-1 rounded-lg text-[11px] font-bold text-loss flex items-center gap-1 disabled:opacity-50"
          >
            {closing ? <Loader2 className="w-3 h-3 animate-spin" /> : <X className="w-3 h-3" />} 平仓
          </button>
        )}
      </div>
    </div>
  );
}

/** 拟物空态：内凹面板 + 浮起图标圆盘，替代光秃秃一行灰字。 */
function EmptyState({ icon: Icon, title, hint, className }: {
  icon: LucideIcon; title: string; hint?: string; className?: string;
}) {
  return (
    <div className={cn('rounded-xl neu-inset flex flex-col items-center justify-center text-center gap-2.5 px-4', className || 'py-10')}>
      <div className="w-11 h-11 rounded-full neu-flat bg-background flex items-center justify-center text-muted-foreground/70">
        <Icon className="w-5 h-5" />
      </div>
      <div className="text-xs font-bold text-muted-foreground">{title}</div>
      {hint && <div className="text-[10px] text-muted-foreground/70 leading-relaxed max-w-[230px] -mt-1">{hint}</div>}
    </div>
  );
}

function TradeRow({ t }: { t: StrategyClosedPosition }) {
  const isLong = t.side === 'LONG';
  return (
    <div className="flex items-center gap-2.5 py-2 px-2 -mx-2 rounded-lg text-[11px] border-b border-border/40 last:border-0 hover:bg-surface-hover/50 transition-colors">
      <span className={cn('w-1 self-stretch rounded-full shrink-0', isLong ? 'bg-gain' : 'bg-loss')} />
      <div className="min-w-0 shrink-0">
        <div className="font-bold leading-tight">
          {t.symbol.replace('USDT', '')}
          <span className={cn('ml-1 text-[9px] font-black', isLong ? 'text-gain' : 'text-loss')}>{isLong ? '多' : '空'}</span>
          {t.status === 'LIQUIDATED' && <span className="ml-1 text-[9px] font-bold px-1 py-px rounded bg-loss/15 text-loss">强平</span>}
        </div>
        <div className="text-[10px] text-muted-foreground tabular-nums leading-tight mt-0.5">
          {fmtNum(t.entryPrice)} → {fmtNum(t.closedPrice)}
        </div>
      </div>
      <div className="ml-auto text-right shrink-0">
        <PnlText value={t.closedPnl} className="font-black tabular-nums block leading-tight" />
        <div className="text-[9px] text-muted-foreground/70 leading-tight mt-0.5">{fmtDateTime(t.updatedAt)}</div>
      </div>
    </div>
  );
}

/** 单策略账户栏：stat 行 → 收益曲线 → 持仓 → 交易记录（折叠）。 */
function StrategyColumn({ view, canClose, closingId, onClose }: {
  view: StrategyAccountView;
  canClose: boolean;
  closingId: number | null;
  onClose: (strategyId: string, pos: FuturesPosition) => void;
}) {
  const meta = STRATEGY_META[view.strategyId] || { name: view.strategyId, desc: '', accent: '#F97316' };
  const [showAllTrades, setShowAllTrades] = useState(false);

  // 收益曲线：已平仓 closedPnl 按平仓时间升序累加（testnet 页同口径，从 0 起）
  const equityPoints = useMemo<TnEquityPoint[]>(() => {
    const asc = [...view.closedPositions].reverse();
    // 前缀和写法：闭包内累加变量违反 react-hooks/immutability（条数少，O(n²) 无所谓）
    const pnls = asc.map(p => p.closedPnl ?? 0);
    return asc.map((p, i) => ({
      time: new Date(p.updatedAt).getTime(),
      cumPnl: pnls.slice(0, i + 1).reduce((a, b) => a + b, 0),
    }));
  }, [view.closedPositions]);

  const trades = showAllTrades ? view.closedPositions : view.closedPositions.slice(0, 8);

  return (
    <div className="rounded-xl neu-raised-sm p-5 space-y-4 min-w-0">
      {/* 头：策略专属点缀色只用在图标底，识别度够又不破坏拟物基调 */}
      <div className="flex items-center gap-3">
        <div
          className="w-10 h-10 rounded-xl neu-flat flex items-center justify-center shrink-0"
          style={{ background: `${meta.accent}1f`, color: meta.accent }}
        >
          <Bot className="w-5 h-5" />
        </div>
        <div className="min-w-0">
          <div className="text-sm font-black truncate">{meta.name}</div>
          <div className="text-[10px] text-muted-foreground truncate">{meta.desc} · quant-{view.strategyId}</div>
        </div>
        {!view.available && (
          <span className="ml-auto text-[10px] font-bold px-2 py-0.5 rounded-full bg-muted text-muted-foreground shrink-0">离线</span>
        )}
      </div>

      {!view.available ? (
        <EmptyState icon={PowerOff} title="账户不可用" hint="sim 未启动或尚未开仓过，启动后自动恢复" />
      ) : (
        <>
          {/* Stats：内凹"仪表窗"，主数字加大一档，标签退后 */}
          <div className="grid grid-cols-2 gap-2.5">
            <div className="rounded-lg neu-inset px-3 py-2.5">
              <div className="text-lg font-black tabular-nums truncate leading-tight">${fmtNum(view.equity)}</div>
              <div className="text-[10px] text-muted-foreground flex items-center gap-1 mt-1">
                <Wallet className="w-3 h-3" /> 权益 · 可用 ${fmtNum(view.balance, 0)}
              </div>
            </div>
            <div className="rounded-lg neu-inset px-3 py-2.5">
              <div className="text-lg font-black tabular-nums truncate leading-tight"><PnlText value={view.cumPnl} /></div>
              <div className="text-[10px] text-muted-foreground flex items-center gap-1 mt-1">
                <TrendingUp className="w-3 h-3" /> 累计已实现 · {view.tradeCount} 笔
              </div>
            </div>
            <div className="rounded-lg neu-inset px-3 py-2.5">
              <div className={cn('text-lg font-black tabular-nums leading-tight', view.tradeCount > 0 && view.winRate >= 0.5 ? 'text-gain' : view.tradeCount > 0 ? 'text-loss' : '')}>
                {view.tradeCount > 0 ? `${(view.winRate * 100).toFixed(1)}%` : '—'}
              </div>
              <div className="text-[10px] text-muted-foreground mt-1">胜率 · {view.winCount}/{view.tradeCount}</div>
            </div>
            <div className="rounded-lg neu-inset px-3 py-2.5">
              <div className="text-lg font-black tabular-nums truncate leading-tight"><PnlText value={view.unrealizedPnl} /></div>
              <div className="text-[10px] text-muted-foreground mt-1">持仓浮盈 · {view.positions.length} 仓</div>
            </div>
          </div>

          {/* 收益曲线 */}
          {equityPoints.length > 0 ? (
            <div className="rounded-xl neu-inset px-1 py-2">
              <EquityChart points={equityPoints} />
            </div>
          ) : (
            <EmptyState icon={TrendingUp} title="暂无收益曲线" hint="首笔平仓后自动出现" className="py-8" />
          )}

          {/* 持仓 */}
          <div className="space-y-2 pt-1 border-t border-border/40">
            <div className="text-[11px] font-black text-muted-foreground tracking-wide pt-1.5">当前持仓</div>
            {view.positions.length === 0 ? (
              <EmptyState icon={Crosshair} title="空仓 · 等待新信号" className="py-6" />
            ) : (
              view.positions.map(p => (
                <PositionCard key={p.id} pos={p} canClose={canClose} closing={closingId === p.id}
                  onClose={pos => onClose(view.strategyId, pos)} />
              ))
            )}
          </div>

          {/* 交易记录 */}
          <div className="space-y-1 pt-1 border-t border-border/40">
            <div className="text-[11px] font-black text-muted-foreground tracking-wide pt-1.5">交易记录</div>
            {view.closedPositions.length === 0 ? (
              <EmptyState icon={History} title="暂无交易记录" className="py-6" />
            ) : (
              <>
                {trades.map(t => <TradeRow key={t.id} t={t} />)}
                {view.closedPositions.length > 8 && (
                  <button
                    onClick={() => setShowAllTrades(v => !v)}
                    className="w-full py-1.5 text-[11px] font-bold text-muted-foreground hover:text-primary flex items-center justify-center gap-1"
                  >
                    {showAllTrades ? <>收起 <ChevronUp className="w-3 h-3" /></> : <>展开全部 {view.closedPositions.length} 笔 <ChevronDown className="w-3 h-3" /></>}
                  </button>
                )}
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/**
 * 三策略账户监控：FIBO / LIQFADE / SQZMOM 各绑独立模拟盘账户（盈亏归因互不污染）。
 * PC 三栏并排对比，移动端竖排。平仓按钮仅 userId=1 渲染，后端二次校验才是真正的门。
 */
export function Strategies() {
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const isAdmin = user?.id === ADMIN_USER_ID;

  const [views, setViews] = useState<StrategyAccountView[]>([]);
  const [loading, setLoading] = useState(false);
  const [closingId, setClosingId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setViews(await strategyAccountApi.overview());
    } catch (e) {
      toast((e as Error).message || '加载策略账户失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  // 点击即市价整仓平（服务端实时取仓位数量+userId==1 校验），无二次确认
  const handleClose = useCallback(async (strategyId: string, pos: FuturesPosition) => {
    if (closingId != null) return;
    setClosingId(pos.id);
    try {
      await strategyAccountApi.close(strategyId, pos.id);
      toast('平仓指令已执行', 'success');
      void load();
    } catch (e) {
      toast((e as Error).message || '平仓失败', 'error');
    } finally {
      setClosingId(null);
    }
  }, [closingId, load, toast]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-xl neu-raised-sm flex items-center justify-center bg-primary/10">
            <Activity className="w-5.5 h-5.5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight">策略账户</h1>
            <p className="text-[11px] text-muted-foreground">三策略独立账户 · 本平台模拟盘执行 · 决策价与撮合价同源</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Link to="/testnet" className="neu-btn-sm px-3 py-1.5 rounded-lg text-[11px] font-bold text-muted-foreground hover:text-primary">
            Testnet 监测 →
          </Link>
          <button onClick={() => { void load(); }}
            className="neu-btn-sm w-9 h-9 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="刷新">
            <RefreshCcw className={cn('w-4 h-4', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      {loading && views.length === 0 ? (
        <div className="flex items-center justify-center py-20 gap-2 text-sm text-muted-foreground">
          <Loader2 className="w-4 h-4 animate-spin" /> 加载三策略账户...
        </div>
      ) : (
        <div className="grid gap-5 lg:grid-cols-3">
          {views.map(v => (
            <StrategyColumn
              key={v.strategyId}
              view={v}
              canClose={isAdmin}
              closingId={closingId}
              onClose={handleClose}
            />
          ))}
        </div>
      )}

    </div>
  );
}
