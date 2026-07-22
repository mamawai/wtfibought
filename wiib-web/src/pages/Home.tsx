import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { buffApi, cryptoOrderApi, futuresApi, userApi, quantApi } from '../api';
import { HomeMarketSection } from '../components/HomeMarketSection';
import { DailyBuffModal } from '../components/DailyBuffCard';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { ForceOrdersCard } from '../components/ForceOrdersCard';
import { NewsFlashCard } from '../components/NewsFlashCard';
import { HomeFaq } from '../components/HomeFaq';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { useToast } from '../components/ui/use-toast';
import { SpotlightCard } from '../components/fx/SpotlightCard';
import { DecryptedText } from '../components/fx/DecryptedText';
import { ArcGauge } from '../components/fx/ArcGauge';
import { Sparkline } from '../components/fx/Sparkline';
import {
  RefreshCcw, Bell, Gamepad2, List, DollarSign, ArrowRight, Target, Brain, Gift,
} from 'lucide-react';
import type { BuffStatus, AssetSnapshot, QuantSnapshotView } from '../types';
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

function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return '夜深了';
  if (h < 11) return '早上好';
  if (h < 13) return '中午好';
  if (h < 18) return '下午好';
  return '晚上好';
}

/** volLegsJson 里的单腿：percentile 可能是 0-1 或 0-100，展示前归一 */
interface VolLeg { sigmaBps?: number; percentile?: number; tier?: string; volState?: string }
function legPct(leg: VolLeg | undefined): number {
  const p = leg?.percentile ?? 0;
  return Math.max(0, Math.min(100, p <= 1 ? p * 100 : p));
}

export function Home() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { user } = useUserStore();
  // 路由已挡住未登录，user 为 null 只可能是 fetchUser 还没回来。
  // 行情/成交那几块不依赖 user，先渲染出来，本人相关的卡等 user 到了再补
  const ready = !!user;
  const [refreshNonce, setRefreshNonce] = useState(0);

  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const [buffOpen, setBuffOpen] = useState(false);
  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  // tradesLoading 由"已加载 nonce 是否追上刷新 nonce"派生
  const [tradesLoadedNonce, setTradesLoadedNonce] = useState(-1);
  const tradesLoading = tradesLoadedNonce !== refreshNonce;

  // 驾驶舱数据：资产曲线(30d) + 实时快照(今日盈亏) + BTC 量化快照(AI 波动画像)
  const [history, setHistory] = useState<AssetSnapshot[]>([]);
  const [realtime, setRealtime] = useState<AssetSnapshot | null>(null);
  const [quantSnap, setQuantSnap] = useState<QuantSnapshotView | null>(null);

  useEffect(() => { if (shouldShowNotice()) navigate('/intro', { replace: true }); }, [navigate]);

  useEffect(() => {
    if (ready) buffApi.status().then(setBuffStatus).catch(() => {});
  }, [ready, refreshNonce]);

  useEffect(() => {
    if (!ready) return;
    userApi.assetHistory(30).then(setHistory).catch(() => {});
    userApi.assetRealtime().then(setRealtime).catch(() => {});
  }, [ready, refreshNonce]);

  useEffect(() => {
    quantApi.latestSnapshot('BTCUSDT').then(setQuantSnap).catch(() => {});
  }, [refreshNonce]);

  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, sideTone: o.orderSide === 'BUY' ? 'buy' as const : 'sell' as const, name: o.symbol.replace('USDT', ''), quantity: o.quantity, unit: o.symbol.replace('USDT', ''), filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => { const s = FUTURES_SIDE_MAP[o.orderSide] ?? { label: o.orderSide, tone: 'buy' as const }; const b = o.symbol.replace('USDT', ''); return { id: `f-${o.orderId}`, orderSide: o.orderSide, sideLabel: s.label, sideTone: s.tone, name: `${b} 合约`, quantity: o.quantity, unit: b, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }; });
        setLatestTrades([...ci, ...fi].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoadedNonce(refreshNonce));
  }, [refreshNonce]);

  const isProfit = (user?.profit ?? 0) >= 0;
  // 曲线尾端接上实时值，让"最新一格"跟着盘面动
  const equityCurve = history.length
    ? [...history.map(h => h.totalAssets), ...(realtime ? [realtime.totalAssets] : [])]
    : [];
  const todayProfit = realtime?.dailyProfit ?? null;
  const todayUp = (todayProfit ?? 0) >= 0;
  const volLegs: Record<string, VolLeg> | null = (() => {
    try { return quantSnap ? JSON.parse(quantSnap.volLegsJson) : null; } catch { return null; }
  })();
  const dateStr = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });

  return (
    <div className="page-shell px-4 md:px-6 py-4 space-y-4">

      {ready ? (
        <>
          {/* ====== 问候行 ====== */}
          <div className="flex items-center gap-3 flex-wrap">
            <DecryptedText
              text={`${greeting()}，${user!.username}`}
              className="text-lg font-extrabold tracking-tight"
            />
            <span className="text-xs text-muted-foreground">{dateStr}</span>
            <div className="ml-auto flex items-center gap-1.5">
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/intro')}><Bell className="w-3.5 h-3.5 text-primary" /></Button>
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => { setRefreshNonce(n => n + 1); toast('已刷新', 'info'); }}><RefreshCcw className="w-3.5 h-3.5" /></Button>
            </div>
          </div>

          {/* ====== 驾驶舱主行：总资产曲线 + 今日盈亏/AI 画像 ====== */}
          <div className="grid lg:grid-cols-[1.7fr_1fr] gap-4 items-stretch">
            <SpotlightCard className="p-4 flex flex-col">
              <div className="flex items-center gap-2">
                <span className="microlabel font-semibold">总资产 · USD</span>
                <span className={cn(
                  'ml-auto num text-[11px] font-bold px-2 py-0.5 rounded-full',
                  isProfit ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
                )}>
                  {isProfit ? '▲ +' : '▼ '}{user!.profitPct.toFixed(2)}%
                </span>
              </div>
              <div className="mt-1.5 flex items-baseline gap-1">
                <span className="num text-lg text-muted-foreground">$</span>
                <NumberFlow
                  value={user!.totalAssets}
                  format={{ minimumFractionDigits: 2, maximumFractionDigits: 2 }}
                  className="num text-3xl sm:text-4xl font-bold tracking-tight"
                />
              </div>
              <div className="mt-1 text-xs text-muted-foreground">
                可用 <span className="num font-semibold text-foreground">${fmtMoney(user!.balance)}</span>
                <span className={cn('num font-semibold ml-3', isProfit ? 'text-gain' : 'text-loss')}>
                  {isProfit ? '+' : ''}${fmtMoney(user!.profit)}
                </span>
                <span className="ml-1">总盈亏</span>
              </div>
              <div className="mt-3 h-20 flex-1 min-h-16">
                {equityCurve.length > 1 && (
                  <Sparkline
                    data={equityCurve}
                    stroke={isProfit ? 'var(--color-gain)' : 'var(--color-loss)'}
                    dot={false}
                    className="w-full h-full"
                  />
                )}
              </div>
            </SpotlightCard>

            <div className="flex flex-col gap-4">
              <Card>
                <CardContent className="pt-4 pb-4">
                  <div className="microlabel font-semibold mb-1.5">今日盈亏</div>
                  {todayProfit != null ? (
                    <div className={cn('num text-xl font-bold', todayUp ? 'text-gain' : 'text-loss')}>
                      {todayUp ? '+' : ''}{fmtMoney(todayProfit)}
                      {realtime?.dailyProfitPct != null && (
                        <span className="text-xs ml-2 font-semibold">
                          {todayUp ? '+' : ''}{realtime.dailyProfitPct.toFixed(2)}%
                        </span>
                      )}
                    </div>
                  ) : (
                    <div className="text-sm text-muted-foreground">—</div>
                  )}
                </CardContent>
              </Card>

              <Card className="flex-1">
                <CardContent className="pt-4 pb-4 flex items-center gap-4">
                  <ArcGauge
                    value={quantSnap?.fragilityScore ?? 0}
                    display={quantSnap ? undefined : '—'}
                    label="FRAGILITY"
                    className="w-28 shrink-0"
                  />
                  <div className="min-w-0 flex-1 space-y-2.5">
                    <button
                      onClick={() => navigate('/ai')}
                      className="microlabel font-semibold hover:text-primary transition-colors cursor-pointer"
                    >
                      AI 波动画像 · BTC →
                    </button>
                    {volLegs ? (
                      ['H6', 'H24'].map(h => (
                        <div key={h}>
                          <div className="flex items-center justify-between text-[11px]">
                            <span className="text-muted-foreground">{h} 波动</span>
                            <span className="num font-semibold">{volLegs[h]?.volState ?? '—'}</span>
                          </div>
                          <div className="h-1 rounded-full bg-secondary overflow-hidden mt-1">
                            <div className="h-full rounded-full bg-primary transition-[width] duration-500" style={{ width: `${legPct(volLegs[h])}%` }} />
                          </div>
                        </div>
                      ))
                    ) : (
                      <div className="text-[11px] text-muted-foreground">暂无量化快照</div>
                    )}
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>
        </>
      ) : (
        /* ====== 游客 Hero（fetchUser 未返回时也短暂走这支） ====== */
        <Card>
          <CardContent className="pt-5">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-5">
              <div className="flex-1 space-y-3">
                <div className="inline-flex items-center gap-2 border border-primary/30 bg-primary/10 rounded-full px-3.5 py-1 text-xs font-bold text-primary">
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
                </div>
              </div>
              <div className="flex gap-6 md:gap-8 shrink-0">
                {[
                  { num: '10+', label: '股票' },
                  { num: '6', label: '币种' },
                  { num: '24/7', label: 'BTC行情' },
                ].map(s => (
                  <div key={s.label} className="text-center">
                    <div className="text-2xl font-extrabold num">{s.num}</div>
                    <div className="text-xs text-muted-foreground">{s.label}</div>
                  </div>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* ====== 快捷入口：压成一行小件（含每日福利，点开弹窗） ====== */}
      <div className="flex flex-wrap gap-2">
        {[
          { icon: List, label: '股票', to: '/bstock', ic: 'text-blue-600 dark:text-blue-400' },
          { icon: DollarSign, label: 'Crypto', to: '/coin', ic: 'text-amber-600 dark:text-amber-400' },
          { icon: Target, label: '预测', to: '/prediction', ic: 'text-primary' },
          { icon: Brain, label: 'AI', to: '/ai', ic: 'text-cyan-600 dark:text-cyan-400' },
          { icon: Gamepad2, label: '游戏', to: '/games', ic: 'text-pink-600 dark:text-pink-400' },
        ].map(({ icon: Icon, label, to, ic }) => (
          <button
            key={to}
            onClick={() => navigate(to)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border bg-card hover:bg-surface-hover hover:border-foreground/20 text-xs font-semibold transition-colors cursor-pointer"
          >
            <Icon className={cn('w-3.5 h-3.5', ic)} />
            {label}
          </button>
        ))}
        {ready && (
          <button
            onClick={() => setBuffOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border bg-card hover:bg-surface-hover hover:border-foreground/20 text-xs font-semibold transition-colors cursor-pointer"
          >
            <Gift className="w-3.5 h-3.5 text-primary" />
            福利
            {/* 今日未抽 → 亮灯提醒 */}
            {buffStatus?.canDraw && <span className="led" />}
          </button>
        )}
      </div>

      {/* ====== 市场行情：三分类终端表，点分类头去市场页，点行直达交易页 ====== */}
      <HomeMarketSection />

      {/* ====== 成交 + 快讯 + 爆仓 + FAQ ====== */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 items-start">
        <LatestTradesCard trades={latestTrades} loading={tradesLoading} />
        {/* 实时快讯：BlockBeats 缓存（quant 侧），与最新成交并列 */}
        <NewsFlashCard />
        {/* 爆仓动态：轻量入口横幅，点击进 /force-orders 全量页 */}
        <div className="md:col-span-2">
          <ForceOrdersCard />
        </div>
        {/* 新手教学 FAQ */}
        <div className="md:col-span-2">
          <HomeFaq />
        </div>
      </div>

      {/* 每日福利弹窗（快捷入口触发） */}
      {ready && (
        <DailyBuffModal
          status={buffStatus}
          open={buffOpen}
          onClose={() => setBuffOpen(false)}
          onDrawn={() => setRefreshNonce(n => n + 1)}
        />
      )}

    </div>
  );
}
