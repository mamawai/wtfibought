import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { buffApi, cryptoOrderApi, futuresApi } from '../api';
import { HomeMarketSection } from '../components/HomeMarketSection';
import { DailyBuffCard } from '../components/DailyBuffCard';
import { MonitorCarousel } from '../components/MonitorCarousel';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { ForceOrdersCard } from '../components/ForceOrdersCard';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { useToast } from '../components/ui/use-toast';
import {
  TrendingUp, TrendingDown, RefreshCcw, Bell,
  Settings, Gamepad2, List, DollarSign, ArrowRight, Target, Brain,
} from 'lucide-react';
import type { BuffStatus } from '../types';
import { useUserStore } from '../stores/userStore';
import { cn, fmtMoney } from '../lib/utils';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
function shouldShowNotice() { const d = localStorage.getItem(HIDE_NOTICE_KEY); return !d || d !== new Date().toDateString(); }

const FUTURES_SIDE_MAP: Record<string, { label: string; tone: 'buy' | 'sell' }> = {
  OPEN_LONG: { label: '开多', tone: 'buy' },
  OPEN_SHORT: { label: '开空', tone: 'sell' },
  CLOSE_LONG: { label: '平多', tone: 'sell' },
  CLOSE_SHORT: { label: '平空', tone: 'buy' },
};

export function Home() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { user } = useUserStore();
  // 路由已挡住未登录，user 为 null 只可能是 fetchUser 还没回来。
  // 行情/成交那几块不依赖 user，先渲染出来，本人相关的卡等 user 到了再补
  const ready = !!user;
  const [refreshNonce, setRefreshNonce] = useState(0);

  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  // tradesLoading 由"已加载 nonce 是否追上刷新 nonce"派生
  const [tradesLoadedNonce, setTradesLoadedNonce] = useState(-1);
  const tradesLoading = tradesLoadedNonce !== refreshNonce;

  useEffect(() => { if (shouldShowNotice()) navigate('/intro', { replace: true }); }, [navigate]);

  useEffect(() => {
    if (ready) buffApi.status().then(setBuffStatus).catch(() => {});
  }, [ready, refreshNonce]);

  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, sideTone: o.orderSide === 'BUY' ? 'buy' as const : 'sell' as const, name: o.symbol.replace('USDT', ''), quantity: o.quantity, unit: o.symbol.replace('USDT', ''), filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => { const s = FUTURES_SIDE_MAP[o.orderSide] ?? { label: o.orderSide, tone: 'buy' as const }; const b = o.symbol.replace('USDT', ''); return { id: `f-${o.orderId}`, orderSide: o.orderSide, sideLabel: s.label, sideTone: s.tone, name: `${b} 合约`, quantity: o.quantity, unit: b, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }; });
        setLatestTrades([...ci, ...fi].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoadedNonce(refreshNonce));
  }, [refreshNonce]);

  const isProfit = (user?.profit ?? 0) >= 0;

  return (
    <div className="page-shell px-4 md:px-6 py-4 space-y-6">

      {/* ====== Hero ====== */}
      <Card>
        <CardContent className="pt-5">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-5">
            {/* Left: slogan + CTA */}
            <div className="flex-1 space-y-3">
              <div className="inline-flex items-center gap-2 bg-primary/10 rounded-full px-3.5 py-1 text-xs font-bold text-primary neu-flat">
                <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
                模拟交易平台
              </div>
              <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight leading-tight">
                模拟交易,<br />
                <span className="text-primary">随时随地!</span>
              </h2>
              <p className="text-sm text-muted-foreground">体验"如果当初买了会怎样"。股票、BTC、合约全覆盖。</p>
              <div className="flex flex-wrap gap-2 pt-1">
                <Button size="sm" onClick={() => navigate('/bstock')}>
                  开始交易 <ArrowRight className="w-3.5 h-3.5" />
                </Button>
                <Button variant="outline" size="sm" onClick={() => navigate('/intro')}>玩法说明</Button>
                {ready && <Button variant="outline" size="sm" onClick={() => document.getElementById('daily-buff-card')?.scrollIntoView({ behavior: 'smooth' })}>每日福利</Button>}
              </div>
            </div>

            {/* Right: total assets or stats */}
            {ready ? (
            <div className="md:text-right space-y-2 shrink-0">
              <div className="flex md:justify-end items-center gap-1.5">
                {user?.id === 1 && (
                  <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/admin')}><Settings className="w-3.5 h-3.5" /></Button>
                )}
                <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/intro')}><Bell className="w-3.5 h-3.5 text-primary" /></Button>
                <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => { setRefreshNonce(n => n + 1); toast('已刷新', 'info'); }}><RefreshCcw className="w-3.5 h-3.5" /></Button>
              </div>
              <div className="text-xs font-bold text-muted-foreground uppercase tracking-wider">总资产</div>
              <div className="text-3xl sm:text-4xl font-extrabold tabular-nums tracking-tight">
                ${fmtMoney(user!.totalAssets)}
              </div>
              <div className="flex md:justify-end flex-wrap items-center gap-2">
                <span className={cn("inline-flex items-center gap-1 text-sm font-bold tabular-nums", isProfit ? "text-gain" : "text-loss")}>
                  {isProfit ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
                  {isProfit ? '+' : ''}${fmtMoney(user!.profit)}
                </span>
                <span className={cn(
                  "text-[11px] font-bold tabular-nums px-2 py-0.5 rounded-full border-2",
                  isProfit ? "bg-gain/10 text-gain border-gain/20" : "bg-loss/10 text-loss border-loss/20"
                )}>
                  {isProfit ? '+' : ''}{user!.profitPct.toFixed(2)}%
                </span>
              </div>
              <div className="text-xs text-muted-foreground">
                可用 <span className="text-foreground font-bold">${fmtMoney(user!.balance)}</span>
              </div>
            </div>
            ) : (
            <div className="flex gap-6 md:gap-8 shrink-0">
              {[
                { num: '10+', label: '股票' },
                { num: '6', label: '币种' },
                { num: '24/7', label: 'BTC行情' },
              ].map(s => (
                <div key={s.label} className="text-center">
                  <div className="text-2xl font-extrabold">{s.num}</div>
                  <div className="text-xs text-muted-foreground">{s.label}</div>
                </div>
              ))}
            </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* ====== Quick Actions ====== */}
      {/* 移动端 5 列一行放齐 5 个入口，避免 4+1 孤行 */}
      <div className="grid grid-cols-5 md:grid-cols-7 gap-2 sm:gap-3">
        {[
          { icon: List, label: '股票', to: '/bstock', bg: 'bg-blue-100 dark:bg-blue-500/15', ic: 'text-blue-600 dark:text-blue-400' },
          { icon: DollarSign, label: 'CRYPTO', to: '/coin', bg: 'bg-amber-100 dark:bg-amber-500/15', ic: 'text-amber-600 dark:text-amber-400' },
          { icon: Target, label: '预测', to: '/prediction', bg: 'bg-orange-100 dark:bg-orange-500/15', ic: 'text-orange-600 dark:text-orange-400' },
          { icon: Brain, label: 'AI', to: '/ai', bg: 'bg-cyan-100 dark:bg-cyan-500/15', ic: 'text-cyan-600 dark:text-cyan-400' },
          { icon: Gamepad2, label: '游戏', to: '/games', bg: 'bg-pink-100 dark:bg-pink-500/15', ic: 'text-pink-600 dark:text-pink-400' },
        ].map(({ icon: Icon, label, to, bg, ic }) => (
          <button
            key={to}
            onClick={() => navigate(to)}
            className="flex flex-col items-center gap-2.5 py-4 rounded-2xl bg-card neu-btn-sm transition-all cursor-pointer"
          >
            <div className={cn("w-11 h-11 rounded-xl flex items-center justify-center", bg)}>
              <Icon className={cn("w-5 h-5", ic)} />
            </div>
            <span className="text-xs font-bold">{label}</span>
          </button>
        ))}
      </div>

      {/* ====== 市场行情：三分类,点分类头去市场页,点卡片直达交易页 ====== */}
      <HomeMarketSection />

      {/* ====== Buff + Monitor + Trades ====== */}
      {ready && (
        <DailyBuffCard status={buffStatus} onDrawn={() => setRefreshNonce(n => n + 1)} />
      )}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        {ready && <MonitorCarousel />}
        {/* user 没到时没有监控列，成交列表撑满整行防孤列空洞 */}
        <div className={ready ? undefined : 'md:col-span-2'}>
          <LatestTradesCard trades={latestTrades} loading={tradesLoading} />
        </div>
        {/* 爆仓动态：轻量入口横幅，点击进 /force-orders 全量页 */}
        <div className="md:col-span-2">
          <ForceOrdersCard />
        </div>
      </div>

    </div>
  );
}
