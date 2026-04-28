import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import * as echarts from 'echarts';
import { predictionApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useIsDark } from '../hooks/useIsDark';
import { usePredictionStream } from '../hooks/usePredictionStream';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { TrendingUp, TrendingDown, Loader2, Clock, Wallet, ArrowUpRight, ArrowDownRight, HelpCircle } from 'lucide-react';
import type { PredictionRound, PredictionBet, PageResult } from '../types';

const WINDOW_SECONDS = 300;

function RollingChar({ char, direction }: { char: string; direction: 'up' | 'down' | 'none' }) {
  const prevRef = useRef(char);
  const [prev, setPrev] = useState(char);
  const [animating, setAnimating] = useState(false);

  useEffect(() => {
    if (char === prevRef.current) return;
    setPrev(prevRef.current);
    prevRef.current = char;
    setAnimating(true);
    const t = setTimeout(() => setAnimating(false), 300);
    return () => clearTimeout(t);
  }, [char]);

  const isDigit = /\d/.test(char);
  const w = isDigit ? '0.62em' : undefined;
  const goUp = direction === 'up';
  const showAnim = isDigit && direction !== 'none' && animating;

  return (
    <span className="inline-block relative overflow-hidden align-bottom" style={{ height: '1.2em', width: w, lineHeight: '1.2em' }}>
      {showAnim ? (
        <>
          <span style={{
            position: 'absolute', top: 0, left: 0, width: '100%', textAlign: 'center',
            animation: `${goUp ? 'roll-up-out' : 'roll-down-out'} 0.3s ease-in-out forwards`
          }}>{prev}</span>
          <span style={{
            position: 'absolute', top: 0, left: 0, width: '100%', textAlign: 'center',
            animation: `${goUp ? 'roll-down-out' : 'roll-up-out'} 0.3s ease-in-out reverse forwards`
          }}>{char}</span>
        </>
      ) : (
        <span style={{ display: 'block', textAlign: 'center' }}>{char}</span>
      )}
    </span>
  );
}

function RollingNumber({ value, className }: { value: string; className?: string }) {
  const prev = useRef(value);
  const chars = useMemo(() => {
    const oldChars = prev.current.split('');
    const newChars = value.split('');
    const maxLen = Math.max(oldChars.length, newChars.length);
    const padOld = oldChars.length < maxLen ? Array(maxLen - oldChars.length).fill('').concat(oldChars) : oldChars;
    const padNew = newChars.length < maxLen ? Array(maxLen - newChars.length).fill('').concat(newChars) : newChars;
    return padNew.map((c, i) => {
      const o = padOld[i];
      if (c === o) return { char: c, dir: 'none' as const };
      const cn = parseInt(c), on = parseInt(o);
      if (isNaN(cn) || isNaN(on)) return { char: c, dir: 'none' as const };
      return { char: c, dir: cn > on ? 'up' as const : 'down' as const };
    });
  }, [value]);

  useEffect(() => { prev.current = value; }, [value]);

  return (
    <span className={className}>
      {chars.map((c, i) => <RollingChar key={i} char={c.char} direction={c.dir} />)}
    </span>
  );
}

function formatPrice(n: number | string | undefined | null): string {
  if (n == null) return '--';
  const v = typeof n === 'string' ? parseFloat(n) : n;
  return isNaN(v) ? '--' : v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

function fmtCountdown(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function Tip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [open]);
  return (
    <span ref={ref} className="relative inline-flex">
      <span className="text-muted-foreground cursor-pointer" onClick={e => { e.stopPropagation(); setOpen(v => !v); }}>
        <HelpCircle className="w-3 h-3" />
      </span>
      {open && (
        <span className="absolute z-50 bottom-full left-1/2 -translate-x-1/2 mb-1.5 px-2.5 py-1.5 rounded-md bg-popover border shadow-md text-[11px] text-popover-foreground leading-snug whitespace-pre-line w-48">
          {text}
        </span>
      )}
    </span>
  );
}

export function Prediction() {
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const isDark = useIsDark();
  const { toast } = useToast();
  const { btcPrice, round: wsRound, upBid, upAsk, downBid, downAsk, activities } = usePredictionStream();

  const [round, setRound] = useState<PredictionRound | null>(null);
  const [countdown, setCountdown] = useState(0);
  const [amount, setAmount] = useState('');
  const [shares, setShares] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [tradeTab, setTradeTab] = useState<'buy' | 'sell'>('buy');
  const [side, setSide] = useState<'UP' | 'DOWN'>('UP');
  const [tab, setTab] = useState<'bets' | 'rounds'>('bets');
  const [bets, setBets] = useState<PredictionBet[]>([]);
  const [betsPage, setBetsPage] = useState(1);
  const [betsTotalPages, setBetsTotalPages] = useState(1);
  const [rounds, setRounds] = useState<PredictionRound[]>([]);
  const [roundsPage, setRoundsPage] = useState(1);
  const [roundsTotalPages, setRoundsTotalPages] = useState(1);
  const [serverClockOffsetMs, setServerClockOffsetMs] = useState(0);

  const [priceHistory, setPriceHistory] = useState<{ time: number; price: number }[]>([]);
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInst = useRef<echarts.ECharts | null>(null);

  const fetchRound = useCallback(async () => {
    try {
      const sentAt = Date.now();
      const r = await predictionApi.current() as unknown as PredictionRound;
      const receivedAt = Date.now();
      const clockSourceMs = r.officialNowTimeMs ?? r.serverTimeMs;
      if (clockSourceMs != null) {
        const midpoint = sentAt + (receivedAt - sentAt) / 2;
        setServerClockOffsetMs(clockSourceMs - midpoint);
      }
      setRound(r);
    } catch { /* ignore */ }
  }, []);

  const fetchBets = useCallback(async (page = betsPage) => {
    try {
      const res = await predictionApi.bets(page, 10) as unknown as PageResult<PredictionBet>;
      setBets(res.records);
      setBetsTotalPages(res.pages);
    } catch { /* ignore */ }
  }, [betsPage]);

  const fetchRounds = useCallback(async (page = roundsPage) => {
    try {
      const res = await predictionApi.rounds(page, 10) as unknown as PageResult<PredictionRound>;
      setRounds(res.records);
      setRoundsTotalPages(res.pages);
    } catch { /* ignore */ }
  }, [roundsPage]);

  useEffect(() => { fetchRound(); fetchBets(); }, [fetchRound, fetchBets]);

  useEffect(() => {
    predictionApi.priceHistory().then((data: unknown) => {
      const arr = data as { time: number; price: string }[];
      if (Array.isArray(arr) && arr.length > 0) {
        setPriceHistory(arr.map(p => ({ time: p.time, price: parseFloat(p.price) })));
      }
    }).catch(() => {});
  }, []);

  useEffect(() => {
    if (!wsRound) return;
    const clockSourceMs = wsRound.officialNowTimeMs ?? wsRound.serverTimeMs;
    if (clockSourceMs != null) {
      setServerClockOffsetMs(clockSourceMs - Date.now());
    }
    const calibratedNow = Date.now() + serverClockOffsetMs;
    const curWs = round?.windowStart ?? Math.floor(calibratedNow / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS;
    if (wsRound.windowStart && wsRound.windowStart < curWs) {
      // 旧回合结算推送，不覆盖当前回合，只刷新数据
      if (wsRound.status === 'SETTLED') { fetchBets(); fetchUser(); }
      return;
    }
    setRound(prev => ({ ...prev, ...wsRound } as PredictionRound));
  }, [wsRound, fetchBets, fetchUser, round?.windowStart, serverClockOffsetMs]);

  useEffect(() => {
    const tick = () => {
      const calibratedNowMs = Date.now() + serverClockOffsetMs;
      const remaining = round?.officialEndTimeMs
        ? Math.max(0, Math.ceil((round.officialEndTimeMs - calibratedNowMs) / 1000))
        : WINDOW_SECONDS - (Math.floor(calibratedNowMs / 1000) % WINDOW_SECONDS);
      setCountdown(Math.min(WINDOW_SECONDS, remaining));
      if (remaining === 0 || remaining === WINDOW_SECONDS) {
        fetchRound();
      }
    };
    tick();
    const iv = setInterval(tick, 1000);
    return () => clearInterval(iv);
  }, [fetchRound, round?.officialEndTimeMs, serverClockOffsetMs]);

  useEffect(() => {
    if (btcPrice != null) {
      const now = Date.now();
      setPriceHistory(prev => {
        const cutoff = now - 90_000;
        const next = [...prev.filter(p => p.time >= cutoff), { time: now, price: btcPrice }];
        return next;
      });
    }
  }, [btcPrice]);

  useEffect(() => {
    if (!chartRef.current) return;
    if (!chartInst.current) {
      chartInst.current = echarts.init(chartRef.current, isDark ? 'dark' : undefined);
    }
    const chart = chartInst.current;
    const startPrice = round?.startPrice ? parseFloat(round.startPrice) : null;
    const now = Date.now();
    const windowMs = 60_000;
    const visibleData = priceHistory.filter(p => p.time >= now - windowMs);
    const data = visibleData.map(p => [p.time, p.price]);
    const lastPoint = data.length > 0 ? data[data.length - 1] : null;
    const lastPrice = lastPoint ? (lastPoint[1] as number) : null;
    const isUp = startPrice != null && lastPrice != null && lastPrice >= startPrice;
    const lineColor = isUp ? '#22c55e' : '#ef4444';

    chart.setOption({
      backgroundColor: 'transparent',
      grid: { left: 12, right: 56, top: 16, bottom: 28 },
      xAxis: {
        type: 'time', min: now - windowMs, max: now,
        axisLabel: { fontSize: 10, color: '#888' }, axisLine: { show: false }, axisTick: { show: false },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value', scale: true, position: 'right',
        axisLabel: { fontSize: 10, color: '#888' }, axisLine: { show: false }, axisTick: { show: false },
        splitLine: { lineStyle: { opacity: 0.08 } },
      },
      series: [
        {
          type: 'line', data, smooth: 0.3, symbol: 'none',
          lineStyle: { width: 2.5, color: lineColor },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: lineColor + '30' }, { offset: 1, color: lineColor + '02' }
          ]) },
          markLine: startPrice != null ? {
            silent: true, symbol: 'none',
            data: [{ yAxis: startPrice, lineStyle: { color: '#666', type: 'dashed', width: 1 }, label: { formatter: '目标价: $' + formatPrice(startPrice), fontSize: 10, position: 'insideStartTop', color: '#888' } }]
          } : undefined,
        },
        {
          type: 'effectScatter',
          data: lastPoint ? [lastPoint] : [],
          symbolSize: 7,
          rippleEffect: { brushType: 'fill', scale: 3.5, period: 2.5 },
          itemStyle: { color: lineColor, shadowBlur: 8, shadowColor: lineColor + '80' },
          z: 10,
        },
      ],
      tooltip: { trigger: 'axis', formatter: (p: unknown) => {
        const arr = p as { data: [number, number] }[];
        if (!arr?.[0]) return '';
        return `${formatTime(arr[0].data[0])}<br/><b>$${formatPrice(arr[0].data[1])}</b>`;
      }},
    }, false);
  }, [priceHistory, round?.startPrice, isDark]);

  useEffect(() => () => {
    chartInst.current?.dispose();
    chartInst.current = null;
  }, []);

  useEffect(() => {
    const handleResize = () => chartInst.current?.resize();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const handleBuy = async () => {
    const amt = parseFloat(amount);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    setSubmitting(true);
    try {
      await predictionApi.buy({ side, amount: amt });
      toast(`买入 ${side === 'UP' ? '看涨' : '看跌'} 成功`, 'success');
      setAmount('');
      fetchBets();
      fetchUser();
      fetchRound();
    } catch (e: unknown) {
      toast((e as Error).message || '买入失败', 'error');
    } finally { setSubmitting(false); }
  };

  const handleSell = async (betId: number) => {
    try {
      await predictionApi.sell(betId);
      toast('已卖出', 'success');
      fetchBets();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '卖出失败', 'error');
    }
  };

  const handleSellSide = async () => {
    const target = parseFloat(shares) || 0;
    if (target <= 0) { toast('请输入卖出份数', 'error'); return; }
    const activeBets = bets.filter(b => b.status === 'ACTIVE' && b.side === side);
    if (activeBets.length === 0) { toast('暂无持仓', 'error'); return; }
    const available = activeBets.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
    if (target > available + 1e-8) {
      toast(`最多可卖出 ${available.toFixed(2)} 份`, 'error');
      return;
    }
    setSubmitting(true);
    try {
      let remaining = target;
      for (const bet of activeBets) {
        if (remaining <= 0) break;
        const betContracts = parseFloat(String(bet.contracts ?? 0));
        const sellContracts = Math.min(remaining, betContracts);
        if (sellContracts <= 0) continue;
        await predictionApi.sell(bet.id, sellContracts);
        remaining -= sellContracts;
      }
      toast(`卖出 ${side === 'UP' ? '看涨' : '看跌'} 成功`, 'success');
      setShares('');
      fetchBets();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '卖出失败', 'error');
    } finally { setSubmitting(false); }
  };

  const currentPrice = btcPrice;
  const startPrice = round?.startPrice ? parseFloat(round.startPrice) : null;
  const diff = currentPrice != null && startPrice != null ? currentPrice - startPrice : null;
  const isUp = diff != null && diff >= 0;
  const displayUpAsk = upAsk ?? (round?.upPrice ? parseFloat(round.upPrice) : null);
  const displayUpBid = upBid ?? displayUpAsk;
  const displayDownAsk = downAsk ?? (round?.downPrice ? parseFloat(round.downPrice) : null);
  const displayDownBid = downBid ?? displayDownAsk;

  const askPrice = side === 'UP' ? displayUpAsk : displayDownAsk;
  const buyAmt = parseFloat(amount) || 0;
  const toWin = askPrice && buyAmt > 0 ? buyAmt / askPrice : 0;

  const activeBetsForSide = bets.filter(b => b.status === 'ACTIVE' && b.side === side);
  const totalShares = activeBetsForSide.reduce((sum, b) => sum + parseFloat(String(b.contracts ?? 0)), 0);
  const bidPrice = side === 'UP' ? displayUpBid : displayDownBid;
  const sellSharesNum = parseFloat(shares) || 0;
  const youllReceive = bidPrice && sellSharesNum > 0 ? sellSharesNum * bidPrice : 0;

  const pctElapsed = ((WINDOW_SECONDS - countdown) / WINDOW_SECONDS) * 100;
  const urgency = countdown <= 10 ? 'from-red-500 to-red-400' : countdown <= 30 ? 'from-amber-500 to-amber-400' : 'from-emerald-500 to-emerald-400';
  const countdownColor = countdown <= 10 ? 'text-red-500' : countdown <= 30 ? 'text-amber-500' : '';

  return (
    <div className="max-w-5xl mx-auto p-4 md:p-6 space-y-4">

      <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-amber-500/10 border border-amber-500/30 text-amber-600 dark:text-amber-400 text-xs font-bold">
        <span className="w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse" />
        新玩法测试中 — 预测BTC 5分钟涨跌，基于 polymarket.com 实时数据 手续费等相关费用收取可能会不同
      </div>

      {/* ── Hero: 标题 + 倒计时 + 价格 + 图表 ── */}
      <Card className="overflow-hidden">
        <CardContent className="p-0">
          {/* 顶栏 */}
          <div className="flex items-center justify-between px-5 pt-4 pb-3">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-lg bg-amber-500/10 flex items-center justify-center">
                <span className="text-lg font-black text-amber-500">B</span>
              </div>
              <div>
                <h1 className="text-base font-bold leading-tight">BTC 5分钟涨跌预测</h1>
                <span className="text-[10px] text-muted-foreground font-mono">
                  5min &middot; {round?.windowStart ?? Math.floor((Date.now() + serverClockOffsetMs) / 1000 / WINDOW_SECONDS) * WINDOW_SECONDS}
                </span>
              </div>
            </div>
            <div className="flex items-center gap-3">
              {round?.status === 'LOCKED' && <Badge variant="outline" className="text-amber-500 border-amber-500/50 text-[10px] px-1.5 py-0">已锁定</Badge>}
              <div className="flex items-center gap-1.5">
                <Clock className={`w-3.5 h-3.5 text-muted-foreground ${countdownColor}`} />
                <RollingNumber value={fmtCountdown(countdown)} className={`font-mono text-xl font-black tabular-nums ${countdownColor}`} />
              </div>
            </div>
          </div>

          {/* 进度条 */}
          <div className="px-5 pb-3">
            <div className="w-full h-1 rounded-full bg-muted overflow-hidden">
              <div className={`h-full rounded-full transition-all duration-1000 ease-linear bg-gradient-to-r ${urgency}`}
                   style={{ width: `${pctElapsed}%` }} />
            </div>
          </div>

          {/* 价格行 */}
          <div className="flex items-end justify-between px-5 pb-2">
            <div>
              <RollingNumber value={`$${formatPrice(currentPrice)}`} className={`text-2xl font-black tabular-nums ${isUp ? 'text-green-500' : 'text-red-500'}`} />
              {diff != null && (
                <div className={`flex items-center gap-1 mt-0.5 text-xs font-semibold ${isUp ? 'text-green-500' : 'text-red-500'}`}>
                  {isUp ? <ArrowUpRight className="w-3.5 h-3.5" /> : <ArrowDownRight className="w-3.5 h-3.5" />}
                  {isUp ? '+' : ''}{diff.toFixed(2)}
                </div>
              )}
            </div>
            <span className="px-2.5 py-1 rounded bg-muted text-xs font-bold font-mono tabular-nums text-muted-foreground flex items-center gap-1">
              {startPrice != null
                ? <><Tip text="回合开始时的BTC基准价，结束时高于此价为涨，低于为跌" />目标价 ${formatPrice(startPrice)}</>
                : <><Loader2 className="w-3 h-3 animate-spin" />目标价获取中</>}
            </span>
          </div>

          {/* 图表 */}
          <div className="px-3 pb-3">
            <div ref={chartRef} style={{ height: 200, width: '100%' }} />
          </div>
        </CardContent>
      </Card>

      {/* ── 交易面板 + Live Trades ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

        {/* 交易面板 */}
        <div className="lg:col-span-2">
          <Card>
            <CardContent className="p-5">
              {/* 买入 / 卖出 Tab */}
              <div className="flex mb-5 bg-muted rounded-lg p-0.5">
                <button onClick={() => { setTradeTab('buy'); setShares(''); }}
                        className={`flex-1 text-sm font-bold py-2 rounded-md transition-all ${tradeTab === 'buy' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  买入
                </button>
                <button onClick={() => { setTradeTab('sell'); setAmount(''); }}
                        className={`flex-1 text-sm font-bold py-2 rounded-md transition-all ${tradeTab === 'sell' ? 'bg-background shadow-sm' : 'text-muted-foreground hover:text-foreground'}`}>
                  卖出
                </button>
              </div>

              {/* 看涨 / 看跌 */}
              <div className="grid grid-cols-2 gap-3 mb-5">
                <button onClick={() => setSide('UP')}
                        className={`relative rounded-xl border p-4 text-center transition-all ${side === 'UP' ? 'border-green-500 bg-green-500/10 shadow-[0_0_12px_-3px_rgba(34,197,94,0.3)]' : 'border-border hover:border-green-500/40'}`}>
                  <TrendingUp className="mx-auto w-5 h-5 text-green-500 mb-1.5" />
                  <div className="text-[10px] text-muted-foreground tracking-wider mb-0.5 flex items-center justify-center gap-0.5">看涨 <Tip text="概率价格：50¢ = 市场认为50%概率上涨。价格越高代表越看好" /></div>
                  <div className="text-2xl font-black text-green-500 tabular-nums">
                    {tradeTab === 'buy'
                      ? (displayUpAsk != null ? `${(displayUpAsk * 100).toFixed(0)}¢` : '--')
                      : (displayUpBid != null ? `${(displayUpBid * 100).toFixed(0)}¢` : '--')}
                  </div>
                </button>
                <button onClick={() => setSide('DOWN')}
                        className={`relative rounded-xl border p-4 text-center transition-all ${side === 'DOWN' ? 'border-red-500 bg-red-500/10 shadow-[0_0_12px_-3px_rgba(239,68,68,0.3)]' : 'border-border hover:border-red-500/40'}`}>
                  <TrendingDown className="mx-auto w-5 h-5 text-red-500 mb-1.5" />
                  <div className="text-[10px] text-muted-foreground tracking-wider mb-0.5 flex items-center justify-center gap-0.5">看跌 <Tip text="概率价格：50¢ = 市场认为50%概率下跌。价格越高代表越看空" /></div>
                  <div className="text-2xl font-black text-red-500 tabular-nums">
                    {tradeTab === 'buy'
                      ? (displayDownAsk != null ? `${(displayDownAsk * 100).toFixed(0)}¢` : '--')
                      : (displayDownBid != null ? `${(displayDownBid * 100).toFixed(0)}¢` : '--')}
                  </div>
                </button>
              </div>

              {tradeTab === 'buy' ? (
                <>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">
                      <Wallet className="w-3 h-3" />
                      {user ? `${formatPrice(user.balance)} USDT` : '--'}
                    </span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">金额</span>
                      <Input type="number" placeholder="0.00" value={amount}
                             onChange={e => setAmount(e.target.value)}
                             className="w-28 h-8 text-sm text-right font-mono tabular-nums" />
                    </div>
                  </div>
                  <div className="flex gap-1.5 mb-4">
                    {[1, 5, 10, 100].map(v => (
                      <Button key={v} variant="outline" size="sm"
                              className="flex-1 h-7 text-xs font-semibold"
                              onClick={() => setAmount(prev => String((parseFloat(prev) || 0) + v))}>
                        +${v}
                      </Button>
                    ))}
                    <Button variant="outline" size="sm" className="flex-1 h-7 text-xs font-semibold"
                            onClick={() => setAmount(user?.balance ? String(Math.floor(parseFloat(String(user.balance)) / 1.02 * 100) / 100) : '0')}>
                      全部
                    </Button>
                  </div>
                  <div className="flex items-center justify-between py-2.5 px-3 mb-4 rounded-lg bg-muted/50">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">预计收益 <Tip text="若预测正确，每份合约按$1结算。收益 = 金额 ÷ 概率价格" /></span>
                    <span className="text-sm font-bold font-mono tabular-nums">${toWin > 0 ? toWin.toFixed(2) : '--'}</span>
                  </div>
                  <Button onClick={handleBuy} disabled={submitting || !user}
                          className={`w-full h-11 font-bold text-sm ${side === 'UP' ? 'bg-green-600 hover:bg-green-700' : 'bg-red-600 hover:bg-red-700'} text-white`}>
                    {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : `买入 ${side === 'UP' ? '看涨' : '看跌'}`}
                  </Button>
                  {!user && <p className="text-[11px] text-muted-foreground mt-2 text-center">请登录后交易</p>}
                </>
              ) : (
                <>
                  <div className="flex items-center justify-between mb-2.5">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">持仓份数 <Tip text="持有的合约数量，预测正确时每份值$1" />: <span className="font-bold text-foreground">{totalShares.toFixed(2)}</span></span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs text-muted-foreground">份数</span>
                      <Input type="number" placeholder="0" value={shares}
                             onChange={e => setShares(e.target.value)}
                             className="w-28 h-8 text-sm text-right font-mono tabular-nums" />
                    </div>
                  </div>
                  <div className="flex gap-1.5 mb-4">
                    {[0.25, 0.5].map(pct => (
                      <Button key={pct} variant="outline" size="sm"
                              className="flex-1 h-7 text-xs font-semibold"
                              onClick={() => setShares(String(Math.floor(totalShares * pct * 100) / 100))}>
                        {pct * 100}%
                      </Button>
                    ))}
                    <Button variant="outline" size="sm" className="flex-1 h-7 text-xs font-semibold"
                            onClick={() => setShares(String(totalShares))}>
                      全部
                    </Button>
                  </div>
                  <div className="flex items-center justify-between py-2.5 px-3 mb-4 rounded-lg bg-muted/50">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">预计到账 <Tip text="按当前卖出价计算的到手金额（已扣手续费）" /></span>
                    <span className="text-sm font-bold font-mono tabular-nums">${youllReceive > 0 ? youllReceive.toFixed(2) : '--'}</span>
                  </div>
                  <Button onClick={handleSellSide} disabled={submitting || activeBetsForSide.length === 0}
                          className={`w-full h-11 font-bold text-sm ${side === 'UP' ? 'bg-green-600 hover:bg-green-700' : 'bg-red-600 hover:bg-red-700'} text-white`}>
                    {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : `卖出 ${side === 'UP' ? '看涨' : '看跌'}`}
                  </Button>
                  {activeBetsForSide.length === 0 && <p className="text-[11px] text-muted-foreground mt-2 text-center">暂无 {side === 'UP' ? '看涨' : '看跌'} 持仓</p>}
                </>
              )}
            </CardContent>
          </Card>
        </div>

        {/* Live Trades */}
        <Card className="flex flex-col min-h-[200px]">
          <div className="px-4 pt-4 pb-2">
            <span className="text-[10px] text-muted-foreground font-semibold tracking-widest uppercase">实时交易--(拉取真的实时交易数据)</span>
          </div>
          <CardContent className="flex-1 overflow-hidden px-1.5 pb-1.5 relative">
            {activities.length === 0 && <p className="text-xs text-muted-foreground text-center py-8">等待交易...</p>}
            <div className="absolute bottom-0 left-0 right-0 px-1.5 flex flex-col-reverse gap-px overflow-hidden" style={{ maxHeight: '100%' }}>
              {activities.map((a, i) => {
                const up = a.outcome === 'Up' || a.side === 'UP';
                const amt = a.amount != null ? Math.round(a.amount) : 0;
                const isLocal = a.source === 'local';
                return (
                  <div key={`${a.ts}-${i}`}
                       className="flex items-center justify-between py-1.5 px-2 rounded pred-feed-in">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-black shrink-0 ${up ? 'bg-green-500/15 text-green-500' : 'bg-red-500/15 text-red-500'}`}>
                        {up ? '↑' : '↓'}
                      </span>
                      <span className="text-[11px] text-muted-foreground truncate">
                        {isLocal ? (a.username || 'User') : 'Polymarket'}
                      </span>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0">
                      <span className={`text-xs font-mono font-bold tabular-nums ${up ? 'text-green-500' : 'text-red-500'}`}>
                        ${amt}
                      </span>
                      <span className={`text-[9px] font-bold px-1 py-0.5 rounded ${up ? 'bg-green-500/12 text-green-500' : 'bg-red-500/12 text-red-500'}`}>
                        {up ? 'UP' : 'DN'}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ── 底部：我的下注 / 往期回合 ── */}
      <Card>
        <CardContent className="p-0">
          <div className="flex border-b">
            <button onClick={() => { setTab('bets'); setBetsPage(1); fetchBets(1); }}
                    className={`px-5 py-3 text-sm font-medium transition-colors ${tab === 'bets' ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'}`}>
              我的下注
            </button>
            <button onClick={() => { setTab('rounds'); setRoundsPage(1); fetchRounds(1); }}
                    className={`px-5 py-3 text-sm font-medium transition-colors ${tab === 'rounds' ? 'border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'}`}>
              往期回合
            </button>
          </div>

          <div className="p-4">
            {tab === 'bets' && (
              <div className="space-y-1.5">
                {bets.length === 0 && <p className="text-xs text-muted-foreground text-center py-6">暂无下注记录</p>}
                {bets.map(b => {
                  const ws = b.windowStart;
                  const timeRange = ws ? (() => {
                    const fmt = (ts: number) => new Date(ts * 1000).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
                    return `${fmt(ws)} - ${fmt(ws + WINDOW_SECONDS)}`;
                  })() : '';
                  return (
                  <div key={b.id} className="flex items-center gap-3 text-xs py-2.5 px-3 rounded-lg hover:bg-muted/40 transition-colors">
                    {timeRange && <span className="text-muted-foreground font-mono tabular-nums text-[11px] shrink-0">{timeRange}</span>}
                    <span className={`w-10 text-center text-[10px] font-bold py-0.5 rounded ${b.side === 'UP' ? 'bg-green-500/12 text-green-500' : 'bg-red-500/12 text-red-500'}`}>
                      {b.side}
                    </span>
                    <span className="font-mono tabular-nums font-semibold">{b.contracts}</span>
                    <span className="text-muted-foreground">@ {b.avgPrice}</span>
                    <span className="text-muted-foreground font-mono tabular-nums">${b.cost.toFixed(2)}</span>
                    {b.currentValue != null && <span className="text-muted-foreground font-mono tabular-nums">~${b.currentValue.toFixed(2)}</span>}
                    <span className="ml-auto">
                      <Badge variant={b.status === 'LOST' ? 'destructive' : 'outline'}
                             className={`text-[10px] px-1.5 py-0 ${b.status === 'WON' ? 'bg-green-500/15 text-green-500 border-green-500/50' : ''}`}>
                        {b.status}
                      </Badge>
                    </span>
                    {b.payout != null && b.status !== 'ACTIVE' && (
                      <span className={`font-mono tabular-nums font-semibold ${b.payout > b.cost ? 'text-green-500' : b.payout === 0 ? 'text-red-500' : 'text-muted-foreground'}`}>
                        {b.payout > 0 ? `+$${b.payout.toFixed(2)}` : '$0'}
                      </span>
                    )}
                    {b.status === 'ACTIVE' && round?.status === 'OPEN' && (
                      <Button size="sm" variant="outline" onClick={() => handleSell(b.id)} className="text-[10px] h-6 px-2">卖出</Button>
                    )}
                  </div>
                  );
                })}
                {betsTotalPages > 1 && (
                  <div className="flex items-center justify-center gap-3 pt-3">
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={betsPage <= 1}
                            onClick={() => { const p = betsPage - 1; setBetsPage(p); fetchBets(p); }}>上一页</Button>
                    <span className="text-xs text-muted-foreground">{betsPage} / {betsTotalPages}</span>
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={betsPage >= betsTotalPages}
                            onClick={() => { const p = betsPage + 1; setBetsPage(p); fetchBets(p); }}>下一页</Button>
                  </div>
                )}
              </div>
            )}

            {tab === 'rounds' && (
              <div className="space-y-1.5">
                {rounds.length === 0 && <p className="text-xs text-muted-foreground text-center py-6">暂无回合记录</p>}
                {rounds.map(r => (
                  <div key={r.id} className="flex items-center gap-3 text-xs py-2.5 px-3 rounded-lg hover:bg-muted/40 transition-colors">
                    <span className="text-muted-foreground font-mono tabular-nums text-[11px]">
                      {r.windowStart ? new Date(r.windowStart * 1000).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) : '--'}
                    </span>
                    <span className="text-muted-foreground">&rarr;</span>
                    <span className="font-mono tabular-nums">${formatPrice(r.startPrice)}</span>
                    <span className="text-muted-foreground">&rarr;</span>
                    <span className="font-mono tabular-nums">${formatPrice(r.endPrice)}</span>
                    <span className="ml-auto">
                      <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${r.outcome === 'UP' ? 'bg-green-500/12 text-green-500' : r.outcome === 'DOWN' ? 'bg-red-500/12 text-red-500' : 'bg-muted text-muted-foreground'}`}>
                        {r.outcome || '--'}
                      </span>
                    </span>
                  </div>
                ))}
                {roundsTotalPages > 1 && (
                  <div className="flex items-center justify-center gap-3 pt-3">
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={roundsPage <= 1}
                            onClick={() => { const p = roundsPage - 1; setRoundsPage(p); fetchRounds(p); }}>上一页</Button>
                    <span className="text-xs text-muted-foreground">{roundsPage} / {roundsTotalPages}</span>
                    <Button variant="outline" size="sm" className="h-7 text-xs"
                            disabled={roundsPage >= roundsTotalPages}
                            onClick={() => { const p = roundsPage + 1; setRoundsPage(p); fetchRounds(p); }}>下一页</Button>
                  </div>
                )}
              </div>
            )}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
