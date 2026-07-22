import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { userApi, cryptoOrderApi, cryptoApi, futuresApi, predictionApi, bstockApi } from '../api';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { useToast } from '../components/ui/use-toast';
import { PortfolioChart } from '../components/PortfolioChart';
import { PortfolioWallet } from '../components/PortfolioWallet';
import { WalletTransferModal } from '../components/WalletTransferModal';
import { FuturesPositionsCard } from '../components/coin/FuturesPositionsCard';
import { ProfitChart } from '../components/ProfitChart';
import { RadarChart } from '../components/RadarChart';
import type { WalletAsset } from '../components/PortfolioWallet';
import { cn, fmtNum } from '../lib/utils';
import { EmptyState } from '../components/EmptyState';
import {
  Wallet,
  TrendingUp,
  TrendingDown,
  Briefcase,
  RefreshCcw,
  ChevronRight,
  PieChart,
  LineChart,
  Radar,
  CircleDollarSign,
  Target,
  Landmark,
} from 'lucide-react';
import type { CryptoPosition, FuturesPosition, PredictionPnl, AssetSnapshot, CategoryAverages, BStock } from '../types';
import { formatCoinPrice, getCoin } from '../lib/coinConfig';

interface CryptoRow extends CryptoPosition {
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

interface BStockRow extends CryptoRow {
  name: string;
  ticker: string;
}

function AnimNum({ value, prefix = '', suffix = '', duration = 600 }: { value: number; prefix?: string; suffix?: string; duration?: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  const prev = useRef(value);
  const mounted = useRef(false);
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      if (ref.current) ref.current.textContent = prefix + fmtNum(value) + suffix;
      return;
    }
    const from = prev.current;
    const to = value;
    prev.current = to;
    if (from === to) {
      if (ref.current) ref.current.textContent = prefix + fmtNum(to) + suffix;
      return;
    }
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const ease = 1 - (1 - t) ** 3;
      const v = from + (to - from) * ease;
      if (ref.current) ref.current.textContent = prefix + fmtNum(v) + suffix;
      if (t < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, prefix, suffix, duration]);
  return <span ref={ref} />;
}

export function Portfolio() {
  const navigate = useNavigate();
  const { user } = useUserStore();
  const { toast } = useToast();
  const [cryptoRows, setCryptoRows] = useState<CryptoRow[]>([]);
  const [bstockRows, setBstockRows] = useState<BStockRow[]>([]);
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [predictionPnl, setPredictionPnl] = useState<PredictionPnl | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [panel, setPanel] = useState<'chart' | 'wallet' | 'profit' | 'radar' | null>('wallet');
  const [chartReady, setChartReady] = useState(false);
  const [walletReady, setWalletReady] = useState(false);
  const [profitData, setProfitData] = useState<AssetSnapshot[]>([]);
  const [profitLoaded, setProfitLoaded] = useState(false);
  const [realtimeSnapshot, setRealtimeSnapshot] = useState<AssetSnapshot | null>(null);
  const [categoryAverages, setCategoryAverages] = useState<CategoryAverages | null>(null);
  const [transferOpen, setTransferOpen] = useState(false);

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

  // 现货持仓：crypto_position 里混着 crypto 与 bStock，按 bstock 列表符号拆分
  const loadSpotPositions = useCallback(async () => {
    try {
      const [cps, blist] = await Promise.all([
        cryptoOrderApi.positions(),
        bstockApi.list().catch(() => [] as BStock[]),
      ]);
      const bmap = new Map<string, BStock>((blist ?? []).map(b => [b.symbol, b]));
      const all = cps ?? [];

      // 纯 crypto：逐只取现价
      const cryptoCps = all.filter(cp => !bmap.has(cp.symbol));
      const crows = await Promise.all(cryptoCps.map(async (cp) => {
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
      setCryptoRows(crows);

      // bStock：现价/名称取自 bstock 列表（已含实时价）
      const brows: BStockRow[] = all.filter(cp => bmap.has(cp.symbol)).map(cp => {
        const b = bmap.get(cp.symbol)!;
        const currentPrice = b.price ?? 0;
        const marketValue = currentPrice * cp.quantity;
        const costValue = cp.avgCost * cp.quantity;
        const profit = marketValue - costValue;
        const profitPct = costValue > 0 ? (profit / costValue) * 100 : 0;
        return { ...cp, name: b.name, ticker: b.ticker, currentPrice, marketValue, profit, profitPct };
      });
      setBstockRows(brows);
    } catch {
      setCryptoRows([]);
      setBstockRows([]);
    } finally {
      setChartReady(true);
    }
  }, []);

  const requestKey = user ? `portfolio:user=${user.id}:refresh=${refreshNonce}` : null;
  useEffect(() => {
      if (requestKey == null) return;
      if (!user) return;
      let cancelled = false;

      setLoading(true);
      setChartReady(false);
      setWalletReady(false);
      userApi.portfolio()
        .then((u) => {
          if (cancelled) return;
          useUserStore.setState({ user: u });
          Promise.all([
            loadSpotPositions(),
            futuresApi.positions().then(setFuturesPositions).catch(() => setFuturesPositions([])),
            predictionApi.pnl().then(setPredictionPnl).catch(() => setPredictionPnl(null)),
          ]).then(() => {
            if (!cancelled) setWalletReady(true);
          });
        })
        .catch(() => {
          if (cancelled) return;
          setCryptoRows([]);
          setBstockRows([]);
          setFuturesPositions([]);
          setPredictionPnl(null);
          toast('获取账户数据失败', 'error', { description: '请稍后重试' });
          setWalletReady(true);
        })
        .finally(() => {
          if (cancelled) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps -- requestKey 已编码 user/refresh 全部刷新条件；effect 内会回写 user store，加 user 依赖会自触发死循环
    }, [requestKey]);

  if (!user) return null;

  const isProfit = user.profit >= 0;
  const cryptoTotal = cryptoRows.reduce((s, c) => s + c.marketValue, 0);
  const cryptoProfit = cryptoRows.reduce((s, c) => s + c.profit, 0);
  const bstockTotal = bstockRows.reduce((s, b) => s + b.marketValue, 0);
  const bstockProfit = bstockRows.reduce((s, b) => s + b.profit, 0);
  const futuresMargin = futuresPositions.reduce((s, f) => s + f.margin, 0);
  const futuresProfit = futuresPositions.reduce((s, f) => s + f.unrealizedPnl, 0);
  const futuresTotal = futuresMargin + futuresProfit;
  const futuresChartRows = Array.from(
    futuresPositions.reduce((map, f) => {
      map.set(f.symbol, (map.get(f.symbol) ?? 0) + f.margin + f.unrealizedPnl);
      return map;
    }, new Map<string, number>())
  ).map(([symbol, marketValue]) => ({ symbol, marketValue }));
  const hasPrediction = predictionPnl != null && predictionPnl.totalBets > 0;
  const predictionProfit = predictionPnl?.totalPnl ?? 0;
  const hasCrypto = cryptoRows.length > 0;
  const hasBstock = bstockRows.length > 0;
  const hasFutures = futuresPositions.length > 0;
  const hasPositions = hasCrypto || hasBstock || hasFutures || hasPrediction;

  const holdingsTotal = cryptoTotal + bstockTotal + futuresTotal;
  const holdingsProfit = cryptoProfit + bstockProfit + futuresProfit;
  const holdingsItems = [
    { label: 'bStock', value: bstockTotal },
    { label: '币种', value: cryptoTotal },
    { label: '合约', value: futuresTotal },
  ];
  const walletAssets: WalletAsset[] = [
    { name: '持仓', count: holdingsItems, value: holdingsTotal, profit: holdingsProfit, bg: 'linear-gradient(135deg, #4338ca, #635bff)' },
  ];

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
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
      <div className={cn("relative rounded-lg border border-border", panel === 'wallet' || panel === 'profit' ? "" : "overflow-hidden")}
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
            <div className="flex flex-wrap gap-1.5">
              <button
                onClick={() => setPanel(p => p === 'chart' ? null : 'chart')}
                className={cn(
                  "flex items-center gap-1.5 px-2.5 py-2 sm:py-1.5 rounded-lg text-xs font-medium transition-all",
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
                  "flex items-center gap-1.5 px-2.5 py-2 sm:py-1.5 rounded-lg text-xs font-medium transition-all",
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
                  "flex items-center gap-1.5 px-2.5 py-2 sm:py-1.5 rounded-lg text-xs font-medium transition-all",
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
                  "flex items-center gap-1.5 px-2.5 py-2 sm:py-1.5 rounded-lg text-xs font-medium transition-all",
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
                        cryptoPositions={cryptoRows}
                        bstockRows={bstockRows}
                        futuresRows={futuresChartRows}
                        balance={user.balance}
                        gameBalance={user.gameBalance}
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
                            "text-sm sm:text-xs font-bold num",
                            realtimeSnapshot.dailyProfit >= 0 ? "text-gain" : "text-loss"
                          )}>
                            {realtimeSnapshot.dailyProfit >= 0 ? '+' : ''}{fmtNum(realtimeSnapshot.dailyProfit)}
                            <span className="ml-1 opacity-70">
                              ({realtimeSnapshot.dailyProfitPct >= 0 ? '+' : ''}{realtimeSnapshot.dailyProfitPct?.toFixed(2)}%)
                            </span>
                          </span>
                        </div>
                        <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-3 gap-y-2 text-xs">
                          {[
                            { label: '加密', value: realtimeSnapshot.dailyCryptoProfit },
                            { label: '大宗商品', value: realtimeSnapshot.dailyCommodityProfit },
                            { label: 'bStock', value: realtimeSnapshot.dailyBstockProfit },
                            { label: '预测', value: realtimeSnapshot.dailyPredictionProfit },
                            { label: '游戏', value: realtimeSnapshot.dailyGameProfit },
                          ].filter(item => item.value !== 0).map(item => (
                            <div key={item.label} className="flex justify-between">
                              <span className="text-muted-foreground">{item.label}</span>
                              <span className={cn("num font-medium", item.value >= 0 ? "text-gain" : "text-loss")}>
                                {item.value >= 0 ? '+' : ''}{fmtNum(item.value)}
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
                      gameBalance={user.gameBalance}
                      username={user.username}
                      assets={walletAssets}
                      ready={walletReady}
                      onTransfer={() => setTransferOpen(true)}
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
                  <span className="text-3xl font-bold num tracking-tight"><AnimNum value={user.totalAssets} /></span>
                  <span className="text-xs text-muted-foreground font-normal">USDT</span>
                </div>
              </div>

              {/* 分隔线 */}
              <div className="h-px bg-gradient-to-r from-border/60 to-transparent mb-3" />

              {/* 次要指标列表：页面拉宽到 page-shell 后单列行太长，宽屏两列 */}
              <div className="divide-y divide-border/20 lg:grid lg:grid-cols-2 lg:gap-x-12 lg:divide-y-0">
                <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                  <span className="text-[12px] text-muted-foreground">余额钱包</span>
                  <span className="text-[13px] font-semibold num"><AnimNum value={user.balance} /></span>
                </div>

                <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                  <span className="text-[12px] text-muted-foreground">游戏钱包</span>
                  <span className="text-[13px] font-semibold num"><AnimNum value={user.gameBalance} /></span>
                </div>

                <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                  <span className="text-[12px] text-muted-foreground">总盈亏</span>
                  <div className={cn(
                    "flex items-center gap-1 px-2 py-0.5 rounded-md text-[12px] font-bold num",
                    isProfit ? "bg-gain/10 text-gain" : "bg-loss/10 text-loss"
                  )}>
                    {isProfit
                      ? <TrendingUp className="w-3 h-3" />
                      : <TrendingDown className="w-3 h-3" />
                    }
                    <AnimNum value={user.profitPct} prefix={isProfit ? '+' : ''} suffix="%" />
                    <span className="opacity-60 font-normal ml-0.5">(<AnimNum value={user.profit} prefix={isProfit ? '+' : ''} />)</span>
                  </div>
                </div>

                <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                  <span className="text-[12px] text-muted-foreground">杠杆借款</span>
                  <span className={cn(
                    "text-[13px] font-semibold num",
                    user.marginLoanPrincipal > 0 ? "text-warning" : "text-muted-foreground"
                  )}><AnimNum value={user.marginLoanPrincipal} /></span>
                </div>

                <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                  <span className="text-[12px] text-muted-foreground">应计利息</span>
                  <span className={cn(
                    "text-[13px] font-semibold num",
                    user.marginInterestAccrued > 0 ? "text-destructive/80" : "text-muted-foreground"
                  )}><AnimNum value={user.marginInterestAccrued} /></span>
                </div>

                {hasFutures && (
                  <>
                    <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                      <span className="text-[12px] text-muted-foreground">合约保证金</span>
                      <span className="text-[13px] font-semibold num"><AnimNum value={futuresMargin} /></span>
                    </div>
                    <div className="flex items-center justify-between py-2 lg:border-b lg:border-border/20">
                      <span className="text-[12px] text-muted-foreground">合约浮盈</span>
                      <span className={cn(
                        "text-[13px] font-semibold num",
                        futuresProfit >= 0 ? "text-gain" : "text-loss"
                      )}><AnimNum value={futuresProfit} prefix={futuresProfit >= 0 ? '+' : ''} /></span>
                    </div>
                  </>
                )}
              </div>
            </div>
            )}
          </div>
        </div>

        {/* 底部线 */}
        <div className="h-px bg-gradient-to-r from-transparent via-border/40 to-transparent" />
      </div>

      {/* 合约持仓：固定在钱包卡正下方，全宽容纳四列小卡（WS 实时盈亏 + 平仓/加仓/杠杆/止盈损 + 一键全平） */}
      {hasFutures && (
        <FuturesPositionsCard
          refreshKey={refreshNonce}
          showCloseAll
          onOrdersChanged={() => setRefreshNonce(n => n + 1)}
        />
      )}

      {/* 刷新 */}
      <div className="flex items-center justify-end">
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

      {/* 持仓：宽屏两栏一行两卡（行式列表卡拉满全宽太长），汇总条跨全宽 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3 items-start">
        {loading ? (
          <Card className="lg:col-span-2">
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
          <Card className="lg:col-span-2"><CardContent className="p-0"><EmptyState icon={<Briefcase />} text="暂无持仓" /></CardContent></Card>
        ) : (
          <>
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
                    <div className="text-[13px] font-bold num tracking-tight"><AnimNum value={cryptoTotal} /></div>
                    <div className={cn("text-[11px] num font-medium", cryptoProfit >= 0 ? "text-gain" : "text-loss")}>
                      <AnimNum value={cryptoProfit} prefix={cryptoProfit >= 0 ? '+' : ''} />
                    </div>
                  </div>
                </div>
                <CardContent className="p-0 divide-y divide-border/30">
                  {cryptoRows.map((c) => {
                    const coin = getCoin(c.symbol);
                    const Icon = coin.icon;
                    const fmtCryptoPrice = (value?: number | null) => formatCoinPrice(c.symbol, value);
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
                              <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                                <span>持有 {c.quantity}{coin.unitLabel && <span className={`${coin.colorClass}/60 ml-0.5`}>约合 {(c.quantity * coin.unitFactor!).toFixed(1)} {coin.unitLabel}</span>}</span>
                                <span className="text-border">·</span>
                                <span>均价 {fmtCryptoPrice(c.avgCost)}</span>
                                {c.currentPrice > 0 && (<><span className="text-border">·</span><span>现价 {fmtCryptoPrice(c.currentPrice)}</span></>)}
                              </div>
                            </div>
                          </div>
                          <div className="text-right shrink-0 flex items-center gap-2">
                            <div>
                              <div className={cn("text-[13px] font-bold num", up ? "text-gain" : "text-loss")}>
                                {up ? '+' : ''}{fmtNum(c.profit)}
                              </div>
                              <div className={cn("text-[11px] num font-medium", up ? "text-gain/70" : "text-loss/70")}>
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

            {/* bStock 股票持仓 */}
            {hasBstock && (
              <Card className="overflow-hidden">
                <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-primary/[0.06] to-transparent">
                  <div className="flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center ring-1 ring-primary/20">
                      <Landmark className="w-4 h-4 text-primary" />
                    </div>
                    <div>
                      <span className="text-sm font-semibold tracking-tight">股票持仓</span>
                      <span className="text-[11px] text-muted-foreground ml-1.5">{bstockRows.length}只 · bStock</span>
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-[13px] font-bold num tracking-tight"><AnimNum value={bstockTotal} /></div>
                    <div className={cn("text-[11px] num font-medium", bstockProfit >= 0 ? "text-gain" : "text-loss")}>
                      <AnimNum value={bstockProfit} prefix={bstockProfit >= 0 ? '+' : ''} />
                    </div>
                  </div>
                </div>
                <CardContent className="p-0 divide-y divide-border/30">
                  {bstockRows.map((b) => {
                    const up = b.profit >= 0;
                    return (
                      <button
                        type="button"
                        key={b.id}
                        onClick={() => navigate(`/bstock/${b.symbol}`)}
                        className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                      >
                        <div className="flex items-center justify-between gap-3">
                          <div className="flex items-center gap-2.5 min-w-0 flex-1">
                            <div className="w-8 h-8 rounded-lg bg-primary/10 ring-1 ring-primary/20 flex items-center justify-center shrink-0 text-[9px] font-bold text-primary">
                              {b.ticker?.slice(0, 4)}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5 mb-1">
                                <span className="font-semibold text-[13px] truncate group-hover:text-primary transition-colors">{b.name}</span>
                                <span className="text-[11px] text-muted-foreground/70 shrink-0">{b.ticker}</span>
                              </div>
                              <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                                <span>{fmtNum(b.quantity)}股</span>
                                <span className="text-border">·</span>
                                <span>均价 {fmtNum(b.avgCost)}</span>
                                {b.currentPrice > 0 && (<><span className="text-border">·</span><span>现价 {fmtNum(b.currentPrice)}</span></>)}
                              </div>
                            </div>
                          </div>
                          <div className="text-right shrink-0 flex items-center gap-2">
                            <div>
                              <div className={cn("text-[13px] font-bold num", up ? "text-gain" : "text-loss")}>
                                {up ? '+' : ''}{fmtNum(b.profit)}
                              </div>
                              <div className={cn("text-[11px] num font-medium", up ? "text-gain/70" : "text-loss/70")}>
                                {up ? '+' : ''}{b.profitPct.toFixed(2)}%
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
                        <div className={cn("text-[13px] font-bold num", predictionProfit >= 0 ? "text-gain" : "text-loss")}>
                          {predictionProfit >= 0 ? '+' : ''}{fmtNum(predictionProfit)}
                        </div>
                        <div className="text-[11px] text-muted-foreground num">
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
                    <span className={cn("text-[13px] font-semibold num", predictionPnl.realizedPnl >= 0 ? "text-gain" : "text-loss")}>
                      {predictionPnl.realizedPnl >= 0 ? '+' : ''}{fmtNum(predictionPnl.realizedPnl)}
                    </span>
                  </div>
                  {predictionPnl.activeBets > 0 && (
                    <div className="px-4 py-3 flex items-center justify-between">
                      <span className="text-[12px] text-muted-foreground">活跃持仓 ({predictionPnl.activeBets}笔)</span>
                      <div className="flex items-center gap-2">
                        <span className="text-[12px] text-muted-foreground num">成本 {fmtNum(predictionPnl.activeCost)}</span>
                        <span className="text-[13px] font-semibold num">市值 {fmtNum(predictionPnl.activeValue)}</span>
                      </div>
                    </div>
                  )}
                  <div className="px-4 py-3 flex items-center justify-between">
                    <span className="text-[12px] text-muted-foreground">胜/负</span>
                    <span className="text-[13px] num">
                      <span className="text-gain font-semibold">{predictionPnl.wonBets}</span>
                      <span className="text-muted-foreground mx-1">/</span>
                      <span className="text-loss font-semibold">{predictionPnl.lostBets}</span>
                    </span>
                  </div>
                </CardContent>
              </Card>
            )}

            {/* 合计汇总 */}
            {[hasCrypto, hasBstock, hasFutures, hasPrediction].filter(Boolean).length > 1 && (() => {
              const allProfit = cryptoProfit + bstockProfit + futuresProfit + predictionProfit;
              const allTotal = cryptoTotal + bstockTotal + futuresTotal + (predictionPnl?.activeValue ?? 0);
              const up = allProfit >= 0;
              return (
                <div className="lg:col-span-2 rounded-xl border border-dashed border-border/60 bg-card/50 backdrop-blur-sm px-4 py-3 flex items-center justify-between">
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Briefcase className="w-3.5 h-3.5" />
                    <span>持仓合计</span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-sm font-bold num"><AnimNum value={allTotal} /></span>
                    <span className={cn("text-xs font-semibold num px-1.5 py-0.5 rounded", up ? "text-gain bg-gain/10" : "text-loss bg-loss/10")}>
                      <AnimNum value={allProfit} prefix={up ? '+' : ''} />
                    </span>
                  </div>
                </div>
              );
            })()}
          </>
        )}
      </div>

      <WalletTransferModal open={transferOpen} onClose={() => setTransferOpen(false)} />
    </div>
  );
}
