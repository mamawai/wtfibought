import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { stockApi, buffApi, orderApi, cryptoOrderApi, futuresApi } from '../api';
import { StockCard } from '../components/StockCard';
import { DailyBuffCard } from '../components/DailyBuffCard';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import {
  TrendingUp, TrendingDown, LineChart, RefreshCcw, Bell,
  Settings, Gamepad2, List, DollarSign, ArrowRight,
} from 'lucide-react';
import type { Stock, BuffStatus } from '../types';
import { useDedupedEffect } from '../hooks/useDedupedEffect';
import { useUserStore } from '../stores/userStore';
import { cn } from '../lib/utils';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const FUTURES_SIDE_MAP: Record<string, { label: string; tone: 'buy' | 'sell' }> = {
  OPEN_LONG: { label: '开多', tone: 'buy' },
  OPEN_SHORT: { label: '开空', tone: 'sell' },
  CLOSE_LONG: { label: '平多', tone: 'sell' },
  CLOSE_SHORT: { label: '平空', tone: 'buy' },
};
function shouldShowNotice() { const d = localStorage.getItem(HIDE_NOTICE_KEY); return !d || d !== new Date().toDateString(); }
function hideNoticeToday() { localStorage.setItem(HIDE_NOTICE_KEY, new Date().toDateString()); }

function StockCardSkeleton() {
  return (
    <div className="flex justify-between items-center px-4 py-3 border-b border-edge/10 last:border-b-0">
      <div className="flex flex-col gap-2"><Skeleton className="h-4 w-20" /><Skeleton className="h-3 w-14" /></div>
      <Skeleton className="h-7 w-20 rounded-full" />
    </div>
  );
}

function fmtMoney(n: number) {
  if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(2) + '亿';
  if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(2) + '万';
  return n.toFixed(2);
}

export function Home() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { user, token } = useUserStore();
  const isLoggedIn = !!token && !!user;
  const [gainers, setGainers] = useState<Stock[]>([]);
  const [losers, setLosers] = useState<Stock[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [noticeOpen, setNoticeOpen] = useState(false);
  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  const [tradesLoading, setTradesLoading] = useState(true);
  const requestKey = `home:gainers-losers:limit=5:refresh=${refreshNonce}`;

  useEffect(() => { if (shouldShowNotice()) setNoticeOpen(true); }, []);
  useEffect(() => {
    if (isLoggedIn) buffApi.status().then(setBuffStatus).catch(() => {});
    else setBuffStatus(null);
  }, [isLoggedIn, refreshNonce]);

  useEffect(() => {
    setTradesLoading(true);
    Promise.all([orderApi.live().catch(() => []), cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([so, co, fo]) => {
        const si: TradeItem[] = so.map(o => ({ id: `s-${o.orderId}`, orderSide: o.orderSide, sideTone: o.orderSide === 'BUY' ? 'buy' as const : 'sell' as const, name: o.stockName, quantity: o.quantity, unit: '股', filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, sideTone: o.orderSide === 'BUY' ? 'buy' as const : 'sell' as const, name: o.symbol.replace('USDT', ''), quantity: o.quantity, unit: o.symbol.replace('USDT', ''), filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => { const s = FUTURES_SIDE_MAP[o.orderSide] ?? { label: o.orderSide, tone: 'buy' as const }; const b = o.symbol.replace('USDT', ''); return { id: `f-${o.orderId}`, orderSide: o.orderSide, sideLabel: s.label, sideTone: s.tone, name: `${b} 合约`, quantity: o.quantity, unit: b, filledAmount: o.filledAmount, createdAt: o.createdAt }; });
        setLatestTrades([...si, ...ci, ...fi].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoading(false));
  }, [refreshNonce]);

  useDedupedEffect(requestKey, () => {
    let c = false;
    Promise.all([stockApi.gainers(5), stockApi.losers(5)])
      .then(([g, l]) => { if (!c) { setGainers(g || []); setLosers(l || []); } })
      .catch(() => { if (!c) { setGainers([]); setLosers([]); toast('获取行情失败', 'error', { description: '请稍后重试' }); } })
      .finally(() => { if (!c) setLoading(false); });
    return () => { c = true; };
  }, [requestKey]);

  const isProfit = (user?.profit ?? 0) >= 0;

  return (
    <div className="max-w-5xl mx-auto px-4 md:px-6 py-4 space-y-6">

      {/* ====== Hero ====== */}
      <Card>
        <CardContent className="pt-5">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-5">
            {/* Left: slogan + CTA */}
            <div className="flex-1 space-y-3">
              <div className="inline-flex items-center gap-2 bg-primary/10 border-2 border-edge/20 rounded-full px-3.5 py-1 text-xs font-bold text-primary">
                <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
                模拟交易平台
              </div>
              <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight leading-tight">
                模拟交易,<br />
                <span className="text-primary">随时随地!</span>
              </h2>
              <p className="text-sm text-muted-foreground">体验"如果当初买了会怎样"。股票、BTC、期权、合约全覆盖。</p>
              <div className="flex flex-wrap gap-2 pt-1">
                <Button size="sm" onClick={() => navigate(isLoggedIn ? '/stocks' : '/login')}>
                  {isLoggedIn ? '开始交易' : '免费开始'} <ArrowRight className="w-3.5 h-3.5" />
                </Button>
                <Button variant="outline" size="sm" onClick={() => setNoticeOpen(true)}>玩法说明</Button>
              </div>
            </div>

            {/* Right: total assets or stats */}
            {isLoggedIn ? (
            <div className="md:text-right space-y-2 shrink-0">
              <div className="flex md:justify-end items-center gap-1.5">
                {user?.id === 1 && (
                  <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/admin')}><Settings className="w-3.5 h-3.5" /></Button>
                )}
                <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => setNoticeOpen(true)}><Bell className="w-3.5 h-3.5 text-primary" /></Button>
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
                { num: '5+', label: '币种' },
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
      <div className="grid grid-cols-4 gap-3">
        {[
          { icon: List, label: '股票', to: '/stocks', bg: 'bg-blue-100 dark:bg-blue-500/15', ic: 'text-blue-600 dark:text-blue-400' },
          { icon: DollarSign, label: 'CRYPTO', to: '/coin', bg: 'bg-amber-100 dark:bg-amber-500/15', ic: 'text-amber-600 dark:text-amber-400' },
          { icon: LineChart, label: '期权', to: '/options', bg: 'bg-violet-100 dark:bg-violet-500/15', ic: 'text-violet-600 dark:text-violet-400' },
          { icon: Gamepad2, label: '游戏', to: '/games', bg: 'bg-pink-100 dark:bg-pink-500/15', ic: 'text-pink-600 dark:text-pink-400' },
        ].map(({ icon: Icon, label, to, bg, ic }) => (
          <button
            key={to}
            onClick={() => navigate(to)}
            className="flex flex-col items-center gap-2.5 py-4 rounded-2xl border-[3px] border-edge bg-card shadow-[3px_3px_0_0_var(--color-edge)] hover:shadow-[1px_1px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px] active:shadow-none active:translate-x-[3px] active:translate-y-[3px] transition-all cursor-pointer"
          >
            <div className={cn("w-11 h-11 rounded-xl flex items-center justify-center border-2 border-edge/15", bg)}>
              <Icon className={cn("w-5 h-5", ic)} />
            </div>
            <span className="text-xs font-bold">{label}</span>
          </button>
        ))}
      </div>

      {/* ====== Buff + Trades ====== */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        {isLoggedIn && <DailyBuffCard status={buffStatus} onDrawn={() => buffApi.status().then(setBuffStatus)} />}
        <LatestTradesCard trades={latestTrades} loading={tradesLoading} />
      </div>

      {/* ====== Gainers / Losers ====== */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              <div className="w-7 h-7 rounded-lg bg-gain/10 border-2 border-edge/15 flex items-center justify-center">
                <TrendingUp className="w-3.5 h-3.5 text-gain" />
              </div>
              涨幅榜
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {loading
              ? Array.from({ length: 5 }).map((_, i) => <StockCardSkeleton key={i} />)
              : gainers.length > 0
                ? gainers.map(s => <StockCard key={s.id} stock={s} onClick={() => navigate(`/stock/${s.id}`)} />)
                : <div className="p-6 text-center text-sm text-muted-foreground">暂无数据</div>
            }
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm">
              <div className="w-7 h-7 rounded-lg bg-loss/10 border-2 border-edge/15 flex items-center justify-center">
                <TrendingDown className="w-3.5 h-3.5 text-loss" />
              </div>
              跌幅榜
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {loading
              ? Array.from({ length: 5 }).map((_, i) => <StockCardSkeleton key={i} />)
              : losers.length > 0
                ? losers.map(s => <StockCard key={s.id} stock={s} onClick={() => navigate(`/stock/${s.id}`)} />)
                : <div className="p-6 text-center text-sm text-muted-foreground">暂无数据</div>
            }
          </CardContent>
        </Card>
      </div>

      {/* ====== Notice Dialog ====== */}
      <Dialog open={noticeOpen} onClose={() => setNoticeOpen(false)} className="max-w-xl">
        <DialogHeader>
          <h2 className="text-xl font-extrabold flex items-center gap-2">
            <Bell className="w-5 h-5 text-primary" />
            玩法说明
          </h2>
        </DialogHeader>
        <DialogContent>
          <div className="space-y-4 text-sm leading-relaxed">
            <section><h3 className="font-bold text-base mb-2 text-primary">欢迎</h3><p className="text-muted-foreground">虚拟股票交易模拟器，体验"如果当初买了会怎样"。所有数据均为模拟。</p></section>
            <section className="bg-primary/8 p-3 rounded-xl border-2 border-primary/20"><p className="text-primary/80 text-xs font-medium">仅供娱乐，不构成投资建议。杠杆有风险，可能爆仓。</p></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">交易时间</h3><p className="text-muted-foreground">周一至周五 9:30-11:30、13:00-15:00，每10秒更新行情</p></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">股票交易</h3><ul className="list-disc list-inside text-muted-foreground space-y-1"><li><strong>市价单</strong>：当前价立即成交</li><li><strong>限价单</strong>：设定价格，触发后成交（当日有效）</li><li><strong>手续费</strong>：0.05%，最低$5</li><li><strong>T+1结算</strong>：卖出后资金24小时到账</li><li><strong>初始资金</strong>：$100,000</li></ul></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">杠杆交易</h3><ul className="list-disc list-inside text-muted-foreground space-y-1"><li>市价买入可选1-10倍杠杆</li><li>借款按日计息0.05%</li><li>爆仓：资产低于借款时自动清仓，次日9:00恢复</li></ul></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">期权交易</h3><ul className="list-disc list-inside text-muted-foreground space-y-1"><li><strong>CALL看涨</strong>：涨得越多赚得越多</li><li><strong>PUT看跌</strong>：跌得越多赚得越多</li><li>仅支持买入开仓，到期前可平仓或等待结算</li><li>现金结算，不涉及股票交割</li></ul></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">BTC模拟交易</h3><ul className="list-disc list-inside text-muted-foreground space-y-1"><li><strong>实时行情</strong>：接入Binance WebSocket</li><li><strong>支持小数</strong>：最小0.00001 BTC</li><li><strong>手续费</strong>：0.1%</li><li><strong>限价单</strong>：50%-150%市价，24小时有效</li><li><strong>杠杆</strong>：1-10倍，日息0.05%</li><li><strong>卖出到账</strong>：5分钟到账，优先偿还借款</li></ul></section>
            <section><h3 className="font-bold text-base mb-2 text-primary">每日福利</h3><p className="text-muted-foreground">每天可抽一次，有机会获得红包、股票或折扣券</p></section>
          </div>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => { hideNoticeToday(); setNoticeOpen(false); }}>今日不再显示</Button>
          <Button size="sm" onClick={() => setNoticeOpen(false)}>我知道了</Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}
