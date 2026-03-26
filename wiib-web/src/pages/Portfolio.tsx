import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { userApi, orderApi, settlementApi, cryptoOrderApi, cryptoApi, futuresApi, optionApi, predictionApi } from '../api';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { PortfolioChart } from '../components/PortfolioChart';
import { PortfolioWallet } from '../components/PortfolioWallet';
import { ProfitChart } from '../components/ProfitChart';
import { RadarChart } from '../components/RadarChart';
import type { WalletAsset } from '../components/PortfolioWallet';
import { cn } from '../lib/utils';
import {
  Wallet,
  TrendingUp,
  TrendingDown,
  Briefcase,
  ClipboardList,
  Clock,
  X,
  RefreshCcw,
  ChevronRight,
  PieChart,
  BarChart3,
  LineChart,
  Radar,
  CircleDollarSign,
  Scale,
  Layers,
  Target,
} from 'lucide-react';
import type { Position, Order, Settlement, CryptoPosition, FuturesPosition, OptionPosition, PredictionPnl, AssetSnapshot, CategoryAverages } from '../types';
import { useDedupedEffect } from '../hooks/useDedupedEffect';
import { getCoin } from '../lib/coinConfig';

interface CryptoRow extends CryptoPosition {
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

function fmt(n: number) {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function AnimNum({ value, prefix = '', suffix = '', duration = 600 }: { value: number; prefix?: string; suffix?: string; duration?: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  const prev = useRef(0);
  useEffect(() => {
    const from = prev.current;
    const to = value;
    prev.current = to;
    if (from === to) {
      if (ref.current) ref.current.textContent = prefix + fmt(to) + suffix;
      return;
    }
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const ease = 1 - (1 - t) ** 3;
      const v = from + (to - from) * ease;
      if (ref.current) ref.current.textContent = prefix + fmt(v) + suffix;
      if (t < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, prefix, suffix, duration]);
  return <span ref={ref}>{prefix}{fmt(value)}{suffix}</span>;
}

export function Portfolio() {
  const navigate = useNavigate();
  const { user, token} = useUserStore();
  const { toast } = useToast();
  const [positions, setPositions] = useState<Position[]>([]);
  const [cryptoRows, setCryptoRows] = useState<CryptoRow[]>([]);
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [optionPositions, setOptionPositions] = useState<OptionPosition[]>([]);
  const [predictionPnl, setPredictionPnl] = useState<PredictionPnl | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderPage, setOrderPage] = useState(1);
  const [orderPageSize, setOrderPageSize] = useState(10);
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [tab, setTab] = useState<'positions' | 'orders' | 'settlements'>('positions');
  const [loading, setLoading] = useState(true);
  const activeRequestKey = useRef('');
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [cancelOrder, setCancelOrder] = useState<Order | null>(null);
  const [cancelSubmitting, setCancelSubmitting] = useState(false);
  const [panel, setPanel] = useState<'chart' | 'wallet' | 'profit' | 'radar' | null>('wallet');
  const [chartReady, setChartReady] = useState(false);
  const [walletReady, setWalletReady] = useState(false);
  const [profitData, setProfitData] = useState<AssetSnapshot[]>([]);
  const [profitLoaded, setProfitLoaded] = useState(false);
  const [realtimeSnapshot, setRealtimeSnapshot] = useState<AssetSnapshot | null>(null);
  const [categoryAverages, setCategoryAverages] = useState<CategoryAverages | null>(null);

  useEffect(() => {
    if (!token) {
      navigate('/login');
    }
  }, [token, navigate]);

  useEffect(() => {
    if (panel === 'profit' && !profitLoaded) {
      Promise.all([
        userApi.assetHistory(30),
        userApi.assetRealtime(),
      ]).then(([history, realtime]) => {
        setProfitData(history);
        setRealtimeSnapshot(realtime);
      }).catch(() => {
        setProfitData([]);
        setRealtimeSnapshot(null);
      }).finally(() => setProfitLoaded(true));
    }
  }, [panel, profitLoaded]);

  useEffect(() => {
    if (panel === 'radar') {
      userApi.categoryAverages(30)
        .then(setCategoryAverages)
        .catch(() => setCategoryAverages(null));
    }
  }, [panel]);

  const loadCryptoPositions = useCallback(async () => {
    try {
      const cps = await cryptoOrderApi.positions();
      if (!cps || cps.length === 0) { setCryptoRows([]); setChartReady(true); return; }
      const rows = await Promise.all(cps.map(async (cp) => {
        let currentPrice = 0;
        try {
          const res = await cryptoApi.price(cp.symbol);
          if (res && res.price) currentPrice = parseFloat(res.price);
        } catch { /* skip */ }
        const marketValue = currentPrice * cp.quantity;
        const costValue = cp.avgCost * cp.quantity;
        const profit = marketValue - costValue;
        const profitPct = costValue > 0 ? (profit / costValue) * 100 : 0;
        return { ...cp, currentPrice, marketValue, profit, profitPct };
      }));
      setCryptoRows(rows);
    } catch {
      setCryptoRows([]);
    } finally {
      setChartReady(true);
    }
  }, []);

  const requestKey = user ? `portfolio:user=${user.id}:refresh=${refreshNonce}:page=${orderPage}:size=${orderPageSize}` : null;
  useDedupedEffect(
    requestKey,
    () => {
      if (!user) return;
      let cancelled = false;
      activeRequestKey.current = requestKey ?? '';

      setLoading(true);
      setChartReady(false);
      setWalletReady(false);
      Promise.all([
          userApi.portfolio(),
          userApi.positions(),
          orderApi.list(undefined, orderPage, orderPageSize),
          settlementApi.pending(),
      ])
        .then(([u, p, o, s]) => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          useUserStore.setState({ user: u });
          setPositions(p);
          setOrders(o.records);
          setOrderTotal(o.total);
          setSettlements(s);
          Promise.all([
            loadCryptoPositions(),
            futuresApi.positions().then(setFuturesPositions).catch(() => setFuturesPositions([])),
            optionApi.positions().then(setOptionPositions).catch(() => setOptionPositions([])),
            predictionApi.pnl().then(setPredictionPnl).catch(() => setPredictionPnl(null)),
          ]).then(() => {
            if (!cancelled && activeRequestKey.current === (requestKey ?? ''))
              setWalletReady(true);
          });
        })
        .catch(() => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          setPositions([]);
          setOrders([]);
          setOrderTotal(0);
          setSettlements([]);
          setCryptoRows([]);
          setFuturesPositions([]);
          setOptionPositions([]);
          setPredictionPnl(null);
          toast('获取账户数据失败', 'error', { description: '请稍后重试' });
          setWalletReady(true);
        })
        .finally(() => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
    },
    [requestKey],
  );

  const handleCancelOrder = async (orderId: number) => {
    try {
      await orderApi.cancel(orderId);
      setOrders((prev) => prev.filter((o) => o.orderId !== orderId));
      toast('订单已取消', 'success');
      return true;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '取消失败';
      toast(msg, 'error');
      return false;
    }
  };

  if (!user) return null;

  const isProfit = user.profit >= 0;
  const stockTotal = positions.reduce((s, p) => s + (p.marketValue || 0), 0);
  const cryptoTotal = cryptoRows.reduce((s, c) => s + c.marketValue, 0);
  const stockProfit = positions.reduce((s, p) => s + (p.profit || 0), 0);
  const cryptoProfit = cryptoRows.reduce((s, c) => s + c.profit, 0);
  const futuresMargin = futuresPositions.reduce((s, f) => s + f.margin, 0);
  const futuresProfit = futuresPositions.reduce((s, f) => s + f.unrealizedPnl, 0);
  const futuresTotal = futuresMargin + futuresProfit;
  const optionTotal = optionPositions.reduce((s, o) => s + o.marketValue, 0);
  const optionProfit = optionPositions.reduce((s, o) => s + o.pnl, 0);
  const hasPrediction = predictionPnl != null && predictionPnl.totalBets > 0;
  const predictionProfit = predictionPnl?.totalPnl ?? 0;
  const hasStock = positions.length > 0;
  const hasCrypto = cryptoRows.length > 0;
  const hasFutures = futuresPositions.length > 0;
  const hasOptions = optionPositions.length > 0;
  const hasPositions = hasStock || hasCrypto || hasFutures || hasOptions || hasPrediction;

  const holdingsTotal = stockTotal + cryptoTotal + futuresTotal + optionTotal;
  const holdingsProfit = stockProfit + cryptoProfit + futuresProfit + optionProfit;
  const holdingsItems = [
    { label: '股票', value: stockTotal },
    { label: '币种', value: cryptoTotal },
    { label: '合约', value: futuresTotal },
  ];
  const walletAssets: WalletAsset[] = [
    { name: '持仓', count: holdingsItems, value: holdingsTotal, profit: holdingsProfit, bg: 'linear-gradient(135deg, #4338ca, #635bff)' },
  ];

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      {user.bankrupt && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="p-4 text-sm">
            <div className="font-medium text-destructive">已爆仓，交易已禁用</div>
            <div className="mt-1 text-muted-foreground">
              破产次数 {user.bankruptCount} · 将在 {user.bankruptResetDate ?? '下个交易日'} 09:00 恢复初始资金
            </div>
          </CardContent>
        </Card>
      )}

      {/* 总资产概览 */}
      <div className={cn("relative rounded-2xl border border-primary/15", panel === 'wallet' || panel === 'profit' ? "" : "overflow-hidden")}
        style={{
          backgroundImage: `linear-gradient(135deg, color-mix(in oklab, var(--color-primary) 12%, var(--color-card)) 0%, var(--color-card) 60%), repeating-linear-gradient(0deg, transparent, transparent 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 25px), repeating-linear-gradient(90deg, transparent, transparent 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 25px)`,
        }}
      >
        {/* 顶部光晕 */}
        <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent" />

        <div className="relative p-4 sm:p-5">
          {/* 头部行：头像+用户名 / 切换按钮 */}
          <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 mb-4 sm:mb-5">
            <div className="flex items-center gap-3">
              {user.avatar ? (
                <img src={user.avatar} alt="" className="w-10 h-10 rounded-full ring-1 ring-primary/30 ring-offset-1 ring-offset-card" />
              ) : (
                <div className="w-10 h-10 rounded-full bg-primary/15 flex items-center justify-center ring-1 ring-primary/20">
                  <Wallet className="w-5 h-5 text-primary" />
                </div>
              )}
              <div>
                <h2 className="text-sm font-semibold leading-tight">{user.username}</h2>
                <p className="text-[11px] text-muted-foreground leading-tight mt-0.5 tracking-wide uppercase">模拟账户</p>
              </div>
            </div>
            <div className="flex gap-1.5">
              <button
                onClick={() => setPanel(p => p === 'chart' ? null : 'chart')}
                className={cn(
                  "flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all",
                  panel === 'chart'
                    ? "bg-primary/15 text-primary border border-primary/25"
                    : "text-muted-foreground border border-border/50 hover:border-border hover:text-foreground"
                )}
              >
                <PieChart className="w-3.5 h-3.5" />
                分布
              </button>
              <button
                onClick={() => setPanel(p => p === 'profit' ? null : 'profit')}
                className={cn(
                  "flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all",
                  panel === 'profit'
                    ? "bg-primary/15 text-primary border border-primary/25"
                    : "text-muted-foreground border border-border/50 hover:border-border hover:text-foreground"
                )}
              >
                <LineChart className="w-3.5 h-3.5" />
                收益
              </button>
              <button
                onClick={() => setPanel(p => p === 'radar' ? null : 'radar')}
                className={cn(
                  "flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all",
                  panel === 'radar'
                    ? "bg-primary/15 text-primary border border-primary/25"
                    : "text-muted-foreground border border-border/50 hover:border-border hover:text-foreground"
                )}
              >
                <Radar className="w-3.5 h-3.5" />
                能力
              </button>
              <button
                onClick={() => setPanel(p => p === 'wallet' ? null : 'wallet')}
                className={cn(
                  "flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all",
                  panel === 'wallet'
                    ? "bg-primary/15 text-primary border border-primary/25"
                    : "text-muted-foreground border border-border/50 hover:border-border hover:text-foreground"
                )}
              >
                <Wallet className="w-3.5 h-3.5" />
                钱包
              </button>
            </div>
          </div>

          {/* 主体：饼图左 + 数据右 */}
          <div className="flex flex-col sm:flex-row gap-0">
            {/* 左：饼图/收益/钱包（profit模式全宽） */}
            {panel && (
              <div className={cn("flex items-center justify-center", panel === 'profit' || panel === 'radar' ? "w-full" : "sm:w-[48%] sm:border-r border-border/30 sm:pr-4")}>
                {panel === 'chart' ? (
                  chartReady ? (
                    <div className="w-full animate-in fade-in duration-300">
                      <PortfolioChart
                        positions={positions}
                        cryptoPositions={cryptoRows}
                        balance={user.balance}
                        pendingSettlement={user.pendingSettlement}
                      />
                    </div>
                  ) : (
                    <div className="w-full h-48 sm:h-56 flex items-center justify-center">
                      <svg className="w-7 h-7 text-muted-foreground/40 animate-spin" viewBox="0 0 24 24" fill="none">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                      </svg>
                    </div>
                  )
                ) : panel === 'profit' ? (
                  <div className="w-full animate-in fade-in duration-300 px-1 sm:px-0 py-3">
                    {realtimeSnapshot && (
                      <div className="mb-3">
                        <div className="flex items-center justify-between mb-3">
                          <span className="text-xs sm:text-[11px] text-muted-foreground uppercase tracking-wider">今日收益</span>
                          <span className={cn(
                            "text-sm sm:text-xs font-bold tabular-nums",
                            realtimeSnapshot.dailyProfit >= 0 ? "text-green-400" : "text-red-400"
                          )}>
                            {realtimeSnapshot.dailyProfit >= 0 ? '+' : ''}{fmt(realtimeSnapshot.dailyProfit)}
                            <span className="ml-1 opacity-70">
                              ({realtimeSnapshot.dailyProfitPct >= 0 ? '+' : ''}{realtimeSnapshot.dailyProfitPct?.toFixed(2)}%)
                            </span>
                          </span>
                        </div>
                        <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-3 gap-y-2 text-xs">
                          {[
                            { label: '股票', value: realtimeSnapshot.dailyStockProfit },
                            { label: '加密', value: realtimeSnapshot.dailyCryptoProfit },
                            { label: '合约', value: realtimeSnapshot.dailyFuturesProfit },
                            { label: '期权', value: realtimeSnapshot.dailyOptionProfit },
                            { label: '预测', value: realtimeSnapshot.dailyPredictionProfit },
                            { label: '游戏', value: realtimeSnapshot.dailyGameProfit },
                          ].filter(item => item.value !== 0).map(item => (
                            <div key={item.label} className="flex justify-between">
                              <span className="text-muted-foreground">{item.label}</span>
                              <span className={cn("tabular-nums font-medium", item.value >= 0 ? "text-green-400" : "text-red-400")}>
                                {item.value >= 0 ? '+' : ''}{fmt(item.value)}
                              </span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                    <ProfitChart data={profitData} />
                  </div>
                ) : panel === 'radar' ? (
                  <div className="w-full animate-in fade-in duration-300 px-1 sm:px-0 py-3">
                    {categoryAverages ? (
                      <RadarChart userData={categoryAverages} />
                    ) : (
                      <div className="w-full h-56 sm:h-72 flex items-center justify-center">
                        <svg className="w-7 h-7 text-muted-foreground/40 animate-spin" viewBox="0 0 24 24" fill="none">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                        </svg>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="animate-in fade-in duration-300 py-4">
                    <PortfolioWallet
                      totalAssets={user.totalAssets}
                      balance={user.balance}
                      username={user.username}
                      assets={walletAssets}
                      ready={walletReady}
                    />
                  </div>
                )}
              </div>
            )}

            {/* 右：指标区（profit/radar模式不显示） */}
            {panel && panel !== 'profit' && panel !== 'radar' && (
              <div className={cn("flex flex-col justify-center", panel ? "sm:flex-1 sm:pl-5 pt-3 sm:pt-0" : "w-full")}>
              {/* 总资产主指标 */}
              <div className="mb-4">
                <span className="text-[11px] text-muted-foreground uppercase tracking-widest">总资产</span>
                <div className="flex items-baseline gap-2 mt-0.5">
                  <span className="text-3xl font-bold tabular-nums tracking-tight"><AnimNum value={user.totalAssets} /></span>
                  <span className="text-xs text-muted-foreground font-normal">USDT</span>
                </div>
              </div>

              {/* 分隔线 */}
              <div className="h-px bg-gradient-to-r from-border/60 to-transparent mb-3" />

              {/* 次要指标列表 */}
              <div className="space-y-0 divide-y divide-border/20">
                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">可用余额</span>
                  <span className="text-[13px] font-semibold tabular-nums"><AnimNum value={user.balance} /></span>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">总盈亏</span>
                  <div className={cn(
                    "flex items-center gap-1 px-2 py-0.5 rounded-md text-[12px] font-bold tabular-nums",
                    isProfit ? "bg-green-500/10 text-green-400" : "bg-red-500/10 text-red-400"
                  )}>
                    {isProfit
                      ? <TrendingUp className="w-3 h-3" />
                      : <TrendingDown className="w-3 h-3" />
                    }
                    <AnimNum value={user.profitPct} prefix={isProfit ? '+' : ''} suffix="%" />
                    <span className="opacity-60 font-normal ml-0.5">(<AnimNum value={user.profit} prefix={isProfit ? '+' : ''} />)</span>
                  </div>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">杠杆借款</span>
                  <span className={cn(
                    "text-[13px] font-semibold tabular-nums",
                    user.marginLoanPrincipal > 0 ? "text-warning" : "text-muted-foreground"
                  )}><AnimNum value={user.marginLoanPrincipal} /></span>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">应计利息</span>
                  <span className={cn(
                    "text-[13px] font-semibold tabular-nums",
                    user.marginInterestAccrued > 0 ? "text-destructive/80" : "text-muted-foreground"
                  )}><AnimNum value={user.marginInterestAccrued} /></span>
                </div>

                {hasFutures && (
                  <>
                    <div className="flex items-center justify-between py-2">
                      <span className="text-[12px] text-muted-foreground">合约保证金</span>
                      <span className="text-[13px] font-semibold tabular-nums"><AnimNum value={futuresMargin} /></span>
                    </div>
                    <div className="flex items-center justify-between py-2">
                      <span className="text-[12px] text-muted-foreground">合约浮盈</span>
                      <span className={cn(
                        "text-[13px] font-semibold tabular-nums",
                        futuresProfit >= 0 ? "text-green-400" : "text-red-400"
                      )}><AnimNum value={futuresProfit} prefix={futuresProfit >= 0 ? '+' : ''} /></span>
                    </div>
                  </>
                )}

                {hasOptions && (
                  <div className="flex items-center justify-between py-2">
                    <span className="text-[12px] text-muted-foreground">期权持仓</span>
                    <div className="flex items-center gap-2">
                      <span className="text-[13px] font-semibold tabular-nums"><AnimNum value={optionTotal} /></span>
                      <span className={cn(
                        "text-[11px] font-medium tabular-nums",
                        optionProfit >= 0 ? "text-green-400" : "text-red-400"
                      )}>(<AnimNum value={optionProfit} prefix={optionProfit >= 0 ? '+' : ''} />)</span>
                    </div>
                  </div>
                )}
              </div>
            </div>
            )}
          </div>
        </div>

        {/* 底部线 */}
        <div className="h-px bg-gradient-to-r from-transparent via-border/40 to-transparent" />
      </div>

      {/* Tabs */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex gap-2 p-1.5 rounded-xl neu-inset">
          <TabButton active={tab === 'positions'} onClick={() => setTab('positions')} icon={<Briefcase className="w-4 h-4" />}>
            持仓
          </TabButton>
          <TabButton active={tab === 'orders'} onClick={() => setTab('orders')} icon={<ClipboardList className="w-4 h-4" />}>
            订单
          </TabButton>
          <TabButton active={tab === 'settlements'} onClick={() => setTab('settlements')} icon={<Clock className="w-4 h-4" />}>
            待结算
          </TabButton>
        </div>

        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setRefreshNonce((n) => n + 1);
            setProfitLoaded(false);
            setRealtimeSnapshot(null);
            toast('已刷新', 'info');
          }}
        >
          <RefreshCcw className="w-4 h-4" />
          刷新
        </Button>
      </div>

      {/* Tab Content */}
      {tab === 'positions' && (
        <div className="space-y-3">
          {loading ? (
            <Card>
              <CardContent className="p-5 space-y-5">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="flex items-center gap-3">
                    <Skeleton className="h-9 w-9 rounded-lg shrink-0" />
                    <div className="flex-1 space-y-2">
                      <Skeleton className="h-4 w-28" />
                      <Skeleton className="h-3 w-40" />
                    </div>
                    <div className="space-y-2 text-right">
                      <Skeleton className="h-4 w-20 ml-auto" />
                      <Skeleton className="h-3 w-16 ml-auto" />
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          ) : !hasPositions ? (
            <Card><CardContent className="p-0"><EmptyState icon={<Briefcase />} text="暂无持仓" /></CardContent></Card>
          ) : (
            <>
              {/* 股票持仓 */}
              {hasStock && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-blue-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-blue-500/10 flex items-center justify-center ring-1 ring-blue-500/20">
                        <BarChart3 className="w-4 h-4 text-blue-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">股票持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{positions.length}只</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[13px] font-bold tabular-nums tracking-tight"><AnimNum value={stockTotal} /></div>
                      <div className={cn("text-[11px] tabular-nums font-medium", stockProfit >= 0 ? "text-green-400" : "text-red-400")}>
                        <AnimNum value={stockProfit} prefix={stockProfit >= 0 ? '+' : ''} />
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {positions.map((p) => {
                      const up = p.profit >= 0;
                      return (
                        <button
                          type="button"
                          key={p.id}
                          onClick={() => navigate(`/stock/${p.stockId}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5 mb-1">
                                <span className="font-semibold text-[13px] truncate group-hover:text-primary transition-colors">{p.stockName}</span>
                                <span className="text-[11px] text-muted-foreground/70 shrink-0">{p.stockCode}</span>
                              </div>
                              <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                <span>{p.quantity}股</span>
                                <span className="text-border">·</span>
                                <span>成本 {p.avgCost.toFixed(2)}</span>
                                <span className="text-border">·</span>
                                <span>现价 {p.currentPrice.toFixed(2)}</span>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-green-400" : "text-red-400")}>
                                  {up ? '+' : ''}{p.profit.toFixed(2)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-green-400/70" : "text-red-400/70")}>
                                  {up ? '+' : ''}{p.profitPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 币种持仓 */}
              {hasCrypto && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-orange-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-orange-500/10 flex items-center justify-center ring-1 ring-orange-500/20">
                        <CircleDollarSign className="w-4 h-4 text-orange-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">币种持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{cryptoRows.length}种</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[13px] font-bold tabular-nums tracking-tight"><AnimNum value={cryptoTotal} /></div>
                      <div className={cn("text-[11px] tabular-nums font-medium", cryptoProfit >= 0 ? "text-green-400" : "text-red-400")}>
                        <AnimNum value={cryptoProfit} prefix={cryptoProfit >= 0 ? '+' : ''} />
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {cryptoRows.map((c) => {
                      const coin = getCoin(c.symbol);
                      const Icon = coin.icon;
                      const up = c.profit >= 0;
                      return (
                        <button
                          type="button"
                          key={c.id}
                          onClick={() => navigate(`/coin/${c.symbol}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex items-center gap-2.5 min-w-0 flex-1">
                              <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ring-1", coin.bgClass, `ring-current/20 ${coin.colorClass}`)}>
                                <Icon className={cn("w-4 h-4", coin.colorClass)} />
                              </div>
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5 mb-1">
                                  <span className="font-semibold text-[13px] group-hover:text-primary transition-colors">{coin.name}</span>
                                  <span className="text-[11px] text-muted-foreground/60">/ USDT</span>
                                  {coin.unitLabel && (
                                    <span className={`text-[10px] ${coin.colorClass}/60`}>1枚=1盎司（{coin.unitFactor}g）</span>
                                  )}
                                </div>
                                <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                  <span>持有 {c.quantity}{coin.unitLabel && <span className={`${coin.colorClass}/60 ml-0.5`}>约合 {(c.quantity * coin.unitFactor!).toFixed(1)} {coin.unitLabel}</span>}</span>
                                  <span className="text-border">·</span>
                                  <span>均价 {fmt(c.avgCost)}</span>
                                  {c.currentPrice > 0 && (<><span className="text-border">·</span><span>现价 {fmt(c.currentPrice)}</span></>)}
                                </div>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-green-400" : "text-red-400")}>
                                  {up ? '+' : ''}{fmt(c.profit)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-green-400/70" : "text-red-400/70")}>
                                  {up ? '+' : ''}{c.profitPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 合约持仓 */}
              {hasFutures && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-purple-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-purple-500/10 flex items-center justify-center ring-1 ring-purple-500/20">
                        <Scale className="w-4 h-4 text-purple-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">合约持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{futuresPositions.length}个</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[11px] text-muted-foreground">保证金 <span className="text-foreground font-bold tabular-nums"><AnimNum value={futuresMargin} /></span></div>
                      <div className={cn("text-[11px] tabular-nums font-medium", futuresProfit >= 0 ? "text-green-400" : "text-red-400")}>
                        浮盈 <AnimNum value={futuresProfit} prefix={futuresProfit >= 0 ? '+' : ''} />
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {futuresPositions.map((f) => {
                      const up = f.unrealizedPnl >= 0;
                      const isLong = f.side === 'LONG';
                      const coin = getCoin(f.symbol);
                      const Icon = coin.icon;
                      return (
                        <button
                          type="button"
                          key={f.id}
                          onClick={() => navigate(`/coin/${f.symbol}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex items-center gap-2.5 min-w-0 flex-1">
                              <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ring-1", coin.bgClass, `ring-current/20 ${coin.colorClass}`)}>
                                <Icon className={cn("w-4 h-4", coin.colorClass)} />
                              </div>
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5 mb-1">
                                  <span className="font-semibold text-[13px] group-hover:text-primary transition-colors">{coin.name}</span>
                                  <Badge className={cn("text-[9px] px-1 py-0", isLong ? "bg-green-500" : "bg-red-500")}>{isLong ? '多' : '空'}</Badge>
                                  <span className="text-[11px] text-muted-foreground">{f.leverage}x</span>
                                </div>
                                <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                  <span>数量 {f.quantity}</span>
                                  <span className="text-border">·</span>
                                  <span>开仓 {fmt(f.entryPrice)}</span>
                                  <span className="text-border">·</span>
                                  <span>保证金 {fmt(f.margin)}</span>
                                </div>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-green-400" : "text-red-400")}>
                                  {up ? '+' : ''}{fmt(f.unrealizedPnl)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-green-400/70" : "text-red-400/70")}>
                                  {up ? '+' : ''}{f.unrealizedPnlPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 期权持仓 */}
              {hasOptions && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-teal-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-teal-500/10 flex items-center justify-center ring-1 ring-teal-500/20">
                        <Layers className="w-4 h-4 text-teal-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">期权持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{optionPositions.length}个</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[13px] font-bold tabular-nums tracking-tight"><AnimNum value={optionTotal} /></div>
                      <div className={cn("text-[11px] tabular-nums font-medium", optionProfit >= 0 ? "text-green-400" : "text-red-400")}>
                        <AnimNum value={optionProfit} prefix={optionProfit >= 0 ? '+' : ''} />
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {optionPositions.map((o) => {
                      const up = o.pnl >= 0;
                      const isCall = o.optionType === 'CALL';
                      return (
                        <div
                          key={o.positionId}
                          className="w-full text-left px-4 py-3.5"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5 mb-1">
                                <span className="font-semibold text-[13px]">{o.stockName}</span>
                                <Badge className={cn("text-[9px] px-1 py-0", isCall ? "bg-green-500" : "bg-red-500")}>{isCall ? 'CALL' : 'PUT'}</Badge>
                                <span className="text-[11px] text-muted-foreground">@{o.strike}</span>
                              </div>
                              <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                <span>{o.quantity}张</span>
                                <span className="text-border">·</span>
                                <span>成本 {fmt(o.avgCost)}</span>
                                <span className="text-border">·</span>
                                <span>现价 {fmt(o.currentPremium)}</span>
                                <span className="text-border">·</span>
                                <span>到期 {o.expireAt.substring(0, 10)}</span>
                              </div>
                            </div>
                            <div className="text-right shrink-0">
                              <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-green-400" : "text-red-400")}>
                                {up ? '+' : ''}{fmt(o.pnl)}
                              </div>
                              <div className="text-[11px] tabular-nums text-muted-foreground">
                                市值 {fmt(o.marketValue)}
                              </div>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 预测盈亏 */}
              {hasPrediction && predictionPnl && (
                <Card className="overflow-hidden">
                  <button
                    type="button"
                    onClick={() => navigate('/prediction')}
                    className="w-full text-left"
                  >
                    <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-amber-500/[0.06] to-transparent">
                      <div className="flex items-center gap-2.5">
                        <div className="w-8 h-8 rounded-lg bg-amber-500/10 flex items-center justify-center ring-1 ring-amber-500/20">
                          <Target className="w-4 h-4 text-amber-400" />
                        </div>
                        <div>
                          <span className="text-sm font-semibold tracking-tight">BTC涨跌预测</span>
                          <span className="text-[11px] text-muted-foreground ml-1.5">{predictionPnl.totalBets}笔</span>
                        </div>
                      </div>
                      <div className="text-right flex items-center gap-2">
                        <div>
                          <div className={cn("text-[13px] font-bold tabular-nums", predictionProfit >= 0 ? "text-green-400" : "text-red-400")}>
                            {predictionProfit >= 0 ? '+' : ''}{fmt(predictionProfit)}
                          </div>
                          <div className="text-[11px] text-muted-foreground tabular-nums">
                            胜率 {predictionPnl.winRate}%
                          </div>
                        </div>
                        <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40" />
                      </div>
                    </div>
                  </button>
                  <CardContent className="p-0 divide-y divide-border/30">
                    <div className="px-4 py-3 flex items-center justify-between">
                      <span className="text-[12px] text-muted-foreground">已实现盈亏</span>
                      <span className={cn("text-[13px] font-semibold tabular-nums", predictionPnl.realizedPnl >= 0 ? "text-green-400" : "text-red-400")}>
                        {predictionPnl.realizedPnl >= 0 ? '+' : ''}{fmt(predictionPnl.realizedPnl)}
                      </span>
                    </div>
                    {predictionPnl.activeBets > 0 && (
                      <div className="px-4 py-3 flex items-center justify-between">
                        <span className="text-[12px] text-muted-foreground">活跃持仓 ({predictionPnl.activeBets}笔)</span>
                        <div className="flex items-center gap-2">
                          <span className="text-[12px] text-muted-foreground tabular-nums">成本 {fmt(predictionPnl.activeCost)}</span>
                          <span className="text-[13px] font-semibold tabular-nums">市值 {fmt(predictionPnl.activeValue)}</span>
                        </div>
                      </div>
                    )}
                    <div className="px-4 py-3 flex items-center justify-between">
                      <span className="text-[12px] text-muted-foreground">胜/负</span>
                      <span className="text-[13px] tabular-nums">
                        <span className="text-green-400 font-semibold">{predictionPnl.wonBets}</span>
                        <span className="text-muted-foreground mx-1">/</span>
                        <span className="text-red-400 font-semibold">{predictionPnl.lostBets}</span>
                      </span>
                    </div>
                  </CardContent>
                </Card>
              )}

              {/* 合计汇总 */}
              {[hasStock, hasCrypto, hasFutures, hasOptions, hasPrediction].filter(Boolean).length > 1 && (() => {
                const allProfit = stockProfit + cryptoProfit + futuresProfit + optionProfit + predictionProfit;
                const allTotal = stockTotal + cryptoTotal + futuresTotal + optionTotal + (predictionPnl?.activeValue ?? 0);
                const up = allProfit >= 0;
                return (
                  <div className="rounded-xl border border-dashed border-border/60 bg-card/50 backdrop-blur-sm px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Briefcase className="w-3.5 h-3.5" />
                      <span>持仓合计</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="text-sm font-bold tabular-nums"><AnimNum value={allTotal} /></span>
                      <span className={cn("text-xs font-semibold tabular-nums px-1.5 py-0.5 rounded", up ? "text-green-400 bg-green-500/10" : "text-red-400 bg-red-500/10")}>
                        <AnimNum value={allProfit} prefix={up ? '+' : ''} />
                      </span>
                    </div>
                  </div>
                );
              })()}
            </>
          )}
        </div>
      )}

      {tab !== 'positions' && (
        <Card>
          <CardContent className="p-0">
            {loading ? (
              <div className="p-4 space-y-4">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="flex justify-between items-center">
                    <div className="space-y-2"><Skeleton className="h-4 w-24" /><Skeleton className="h-3 w-16" /></div>
                    <Skeleton className="h-8 w-20" />
                  </div>
                ))}
              </div>
            ) : (
              <>
                {tab === 'orders' && (
                  orders.length === 0 ? (
                    <EmptyState icon={<ClipboardList />} text="暂无订单" />
                  ) : (
                    orders.map((o) => (
                      <div key={o.orderId} className="p-4 border-b border-border last:border-b-0">
                        <div className="flex justify-between items-start mb-2">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{o.stockName}</span>
                            <Badge variant={o.orderSide === 'BUY' ? 'success' : 'destructive'} className="text-xs">
                              {o.orderSide === 'BUY' ? '买入' : '卖出'}
                            </Badge>
                            <Badge variant={o.status === 'PENDING' || o.status === 'SETTLING' ? 'warning' : o.status === 'FILLED' ? 'success' : 'secondary'}>
                              {o.status}
                            </Badge>
                          </div>
                          {o.status === 'PENDING' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 text-destructive hover:text-destructive hover:bg-destructive/10"
                              onClick={() => setCancelOrder(o)}
                              disabled={user.bankrupt}
                            >
                              <X className="w-3 h-3" />
                              取消
                            </Button>
                          )}
                        </div>
                        <div className="text-sm text-muted-foreground space-y-1">
                          <div>数量: {o.quantity}股 · {o.orderType === 'MARKET' ? '市价单' : '限价单'}</div>
                          {o.orderType === 'MARKET' ? (
                            o.filledPrice && (
                              <div>
                                成交: {o.filledPrice.toFixed(2)} · 总价: {o.filledAmount?.toFixed(2)}
                                {o.commission && ` · 手续费: ${o.commission.toFixed(2)}`}
                              </div>
                            )
                          ) : (
                            <>
                              <div>限价: {o.limitPrice?.toFixed(2)}{o.triggerPrice ? ` · 触发价: ${o.triggerPrice.toFixed(2)}` : ''}</div>
                              {o.filledPrice && (
                                <div>
                                  成交: {o.filledPrice.toFixed(2)} · 总价: {o.filledAmount?.toFixed(2)}
                                  {o.commission && ` · 手续费: ${o.commission.toFixed(2)}`}
                                </div>
                              )}
                            </>
                          )}
                        </div>
                      </div>
                    ))
                  )
                )}
                 {tab === 'orders' && orders.length > 0 && (
                     <div className="p-4 border-t flex items-center justify-between">
                       <div className="text-sm text-muted-foreground">
                         共 {orderTotal} 条订单
                       </div>
                       <div className="flex items-center gap-2">
                         <select
                             className="h-8 w-16 rounded-md border border-input bg-background px-2 py-1 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                             value={orderPageSize}
                             onChange={(e) => {
                                 setOrderPageSize(Number(e.target.value));
                                 setOrderPage(1);
                             }}
                         >
                             <option value="10">10</option>
                             <option value="20">20</option>
                             <option value="50">50</option>
                         </select>
                         <div className="flex items-center gap-1">
                             <Button
                                 variant="outline"
                                 size="sm"
                                 className="h-8 w-8 p-0"
                                 onClick={() => setOrderPage(p => Math.max(1, p - 1))}
                                 disabled={orderPage === 1}
                             >
                                 <ChevronRight className="h-4 w-4 rotate-180" />
                             </Button>
                             <div className="text-sm font-medium w-8 text-center">
                                 {orderPage}
                             </div>
                             <Button
                                 variant="outline"
                                 size="sm"
                                 className="h-8 w-8 p-0"
                                 onClick={() => setOrderPage(p => p + 1)}
                                 disabled={orderPage * orderPageSize >= orderTotal}
                             >
                                 <ChevronRight className="h-4 w-4" />
                             </Button>
                         </div>
                       </div>
                     </div>
                 )}

                {tab === 'settlements' && (
                  settlements.length === 0 ? (
                    <EmptyState icon={<Clock />} text="暂无待结算" />
                  ) : (
                    settlements.map((s) => {
                      const relatedOrder = orders.find(o => o.orderId === s.orderId);
                      return (
                        <div key={s.id} className="p-4 border-b border-border last:border-b-0">
                          <div className="flex justify-between items-start mb-2">
                            <div className="flex items-center gap-2">
                              <span className="font-medium">{relatedOrder?.stockName || `订单 #${s.orderId}`}</span>
                              {relatedOrder && (
                                <Badge variant="secondary" className="text-xs">
                                  {relatedOrder.stockCode}
                                </Badge>
                              )}
                            </div>
                            <Badge variant="secondary">{s.status}</Badge>
                          </div>
                          <div className="flex justify-between text-sm text-muted-foreground">
                            <span>
                              {relatedOrder ? `${relatedOrder.quantity}股 @ ${relatedOrder.filledPrice?.toFixed(2) || '-'}` : '-'}
                            </span>
                            <span className="font-medium tabular-nums text-foreground">
                              +{s.amount.toFixed(2)}
                            </span>
                          </div>
                          {s.settleTime && (
                            <div className="text-xs text-muted-foreground mt-1">
                              预计到账: {s.settleTime.replace('T', ' ').substring(0, 16)}
                            </div>
                          )}
                        </div>
                      );
                    })
                  )
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      <Dialog
        open={!!cancelOrder}
        onClose={() => {
          if (cancelSubmitting) return;
          setCancelOrder(null);
        }}
      >
        <DialogHeader>
          <h3 className="text-lg font-semibold leading-tight pr-6">取消订单</h3>
        </DialogHeader>
        <DialogContent>
          <p className="text-sm text-muted-foreground">
            {cancelOrder ? `确定取消 ${cancelOrder.stockName} 的订单（#${cancelOrder.orderId}）？` : ''}
          </p>
        </DialogContent>
        <DialogFooter className="justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCancelOrder(null)}
            disabled={cancelSubmitting}
          >
            先不取消
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={async () => {
              if (!cancelOrder) return;
              setCancelSubmitting(true);
              try {
                const ok = await handleCancelOrder(cancelOrder.orderId);
                if (ok) setCancelOrder(null);
              } finally {
                setCancelSubmitting(false);
              }
            }}
            disabled={cancelSubmitting}
          >
            确认取消
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-lg text-sm font-medium transition-all whitespace-nowrap",
        active
          ? "bg-primary text-primary-foreground neu-raised-sm"
          : "text-muted-foreground hover:text-foreground"
      )}
    >
      {icon}
      {children}
    </button>
  );
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="p-12 text-center text-muted-foreground">
      <div className="w-12 h-12 mx-auto mb-3 rounded-full bg-muted flex items-center justify-center">
        {icon}
      </div>
      {text}
    </div>
  );
}
